package com.tencent.matrix.trace;

import com.tencent.matrix.javalib.util.Log;
import com.tencent.matrix.trace.item.TraceMethod;
import com.tencent.matrix.trace.retrace.MappingCollector;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class MethodCollector {

    private static final String TAG = "MethodCollector";

    private final ExecutorService executor;
    private final MappingCollector mappingCollector;

    //存储 类->父类 的map（用于查找Activity的子类）
    private final ConcurrentHashMap<String, String> collectedClassExtendMap = new ConcurrentHashMap<>();
    //存储 被忽略方法名 -> 该方法TraceMethod 的映射关系
    private final ConcurrentHashMap<String, TraceMethod> collectedIgnoreMethodMap = new ConcurrentHashMap<>();
    //存储 需要插桩方法名 -> 该方法TraceMethod 的映射关系
    private final ConcurrentHashMap<String, TraceMethod> collectedMethodMap;
    private final Configuration configuration;
    private final AtomicInteger methodId;
    // 被忽略方法计数器
    private final AtomicInteger ignoreCount = new AtomicInteger();
    //需要插桩方法 计数器
    private final AtomicInteger incrementCount = new AtomicInteger();

    public MethodCollector(ExecutorService executor, MappingCollector mappingCollector, AtomicInteger methodId,
                           Configuration configuration, ConcurrentHashMap<String, TraceMethod> collectedMethodMap) {
        this.executor = executor;
        this.mappingCollector = mappingCollector;
        this.configuration = configuration;
        this.methodId = methodId;
        this.collectedMethodMap = collectedMethodMap;
    }

    public ConcurrentHashMap<String, String> getCollectedClassExtendMap() {
        return collectedClassExtendMap;
    }

    public ConcurrentHashMap<String, TraceMethod> getCollectedMethodMap() {
        return collectedMethodMap;
    }

    /**
     *
     * @param srcFolderList 原始文件集合
     * @param dependencyJarList 原始 jar 集合
     * @throws ExecutionException
     * @throws InterruptedException
     */
    public void collect(Set<File> srcFolderList, Set<File> dependencyJarList) throws ExecutionException, InterruptedException {
        List<Future> futures = new LinkedList<>();

        for (File srcFile : srcFolderList) {
            //将所有源文件添加到 classFileList 中
            ArrayList<File> classFileList = new ArrayList<>();
            if (srcFile.isDirectory()) {
                listClassFiles(classFileList, srcFile);
            } else {
                classFileList.add(srcFile);
            }

            for (File classFile : classFileList) {
                // 每个源文件执行 CollectSrcTask
                futures.add(executor.submit(new CollectSrcTask(classFile)));
            }
        }

        for (File jarFile : dependencyJarList) {
            // 每个jar 源文件执行 CollectJarTask
            futures.add(executor.submit(new CollectJarTask(jarFile)));
        }

        for (Future future : futures) {
            future.get();
        }
        futures.clear();

        futures.add(executor.submit(new Runnable() {
            @Override
            public void run() {
                //存储不需要插桩的方法信息到文件（包括黑名单中的方法）
                saveIgnoreCollectedMethod(mappingCollector);
            }
        }));

        futures.add(executor.submit(new Runnable() {
            @Override
            public void run() {
                //存储待插桩的方法信息到文件
                saveCollectedMethod(mappingCollector);
            }
        }));

        for (Future future : futures) {
            future.get();
        }
        futures.clear();

    }


    class CollectSrcTask implements Runnable {

        File classFile;

        CollectSrcTask(File classFile) {
            this.classFile = classFile;
        }

        @Override
        public void run() {
            InputStream is = null;
            try {
                is = new FileInputStream(classFile);
                ClassReader classReader = new ClassReader(is);
                ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                //收集Method信息
                ClassVisitor visitor = new TraceClassAdapter(Opcodes.ASM5, classWriter);
                classReader.accept(visitor, 0);

            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    is.close();
                } catch (Exception e) {
                }
            }
        }
    }

    class CollectJarTask implements Runnable {

        File fromJar;

        CollectJarTask(File jarFile) {
            this.fromJar = jarFile;
        }

        @Override
        public void run() {
            ZipFile zipFile = null;

            try {
                zipFile = new ZipFile(fromJar);
                Enumeration<? extends ZipEntry> enumeration = zipFile.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry zipEntry = enumeration.nextElement();
                    String zipEntryName = zipEntry.getName();
                    if (isNeedTraceFile(zipEntryName)) {//是需要被插桩的文件
                        InputStream inputStream = zipFile.getInputStream(zipEntry);
                        ClassReader classReader = new ClassReader(inputStream);
                        ClassWriter classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                        //进行扫描
                        ClassVisitor visitor = new TraceClassAdapter(Opcodes.ASM5, classWriter);
                        classReader.accept(visitor, 0);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                try {
                    zipFile.close();
                } catch (Exception e) {
                    Log.e(TAG, "close stream err! fromJar:%s", fromJar.getAbsolutePath());
                }
            }
        }
    }

    /**
     * 将被忽略的 方法名 存入 ignoreMethodMapping.txt 中
     * @param mappingCollector
     */
    private void saveIgnoreCollectedMethod(MappingCollector mappingCollector) {

        //创建 ignoreMethodMapping.txt 文件对象
        File methodMapFile = new File(configuration.ignoreMethodMapFilePath);
        //如果他爸不存在就创建
        if (!methodMapFile.getParentFile().exists()) {
            methodMapFile.getParentFile().mkdirs();
        }
        List<TraceMethod> ignoreMethodList = new ArrayList<>();
        ignoreMethodList.addAll(collectedIgnoreMethodMap.values());
        Log.i(TAG, "[saveIgnoreCollectedMethod] size:%s path:%s", collectedIgnoreMethodMap.size(), methodMapFile.getAbsolutePath());

        //通过class名字进行排序
        Collections.sort(ignoreMethodList, new Comparator<TraceMethod>() {
            @Override
            public int compare(TraceMethod o1, TraceMethod o2) {
                return o1.className.compareTo(o2.className);
            }
        });

        PrintWriter pw = null;
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(methodMapFile, false);
            Writer w = new OutputStreamWriter(fileOutputStream, "UTF-8");
            pw = new PrintWriter(w);
            pw.println("ignore methods:");
            for (TraceMethod traceMethod : ignoreMethodList) {
                //将 混淆过的数据 转换为 原始数据
                traceMethod.revert(mappingCollector);
                //输出忽略信息到 文件中
                pw.println(traceMethod.toIgnoreString());
            }
        } catch (Exception e) {
            Log.e(TAG, "write method map Exception:%s", e.getMessage());
            e.printStackTrace();
        } finally {
            if (pw != null) {
                pw.flush();
                pw.close();
            }
        }
    }


    private void saveCollectedMethod(MappingCollector mappingCollector) {
        File methodMapFile = new File(configuration.methodMapFilePath);
        if (!methodMapFile.getParentFile().exists()) {
            methodMapFile.getParentFile().mkdirs();
        }
        List<TraceMethod> methodList = new ArrayList<>();

        TraceMethod extra = TraceMethod.create(TraceBuildConstants.METHOD_ID_DISPATCH, Opcodes.ACC_PUBLIC, "android.os.Handler",
                "dispatchMessage", "(Landroid.os.Message;)V");
        collectedMethodMap.put(extra.getMethodName(), extra);

        methodList.addAll(collectedMethodMap.values());

        Log.i(TAG, "[saveCollectedMethod] size:%s incrementCount:%s path:%s", collectedMethodMap.size(), incrementCount.get(), methodMapFile.getAbsolutePath());

        Collections.sort(methodList, new Comparator<TraceMethod>() {
            @Override
            public int compare(TraceMethod o1, TraceMethod o2) {
                return o1.id - o2.id;
            }
        });

        PrintWriter pw = null;
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(methodMapFile, false);
            Writer w = new OutputStreamWriter(fileOutputStream, "UTF-8");
            pw = new PrintWriter(w);
            for (TraceMethod traceMethod : methodList) {
                traceMethod.revert(mappingCollector);
                pw.println(traceMethod.toString());
            }
        } catch (Exception e) {
            Log.e(TAG, "write method map Exception:%s", e.getMessage());
            e.printStackTrace();
        } finally {
            if (pw != null) {
                pw.flush();
                pw.close();
            }
        }
    }

    private class TraceClassAdapter extends ClassVisitor {
        private String className;
        private boolean isABSClass = false;
        private boolean hasWindowFocusMethod = false;

        TraceClassAdapter(int i, ClassVisitor classVisitor) {
            super(i, classVisitor);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            super.visit(version, access, name, signature, superName, interfaces);
            this.className = name;
            //如果是虚拟类或者接口 isABSClass =true
            if ((access & Opcodes.ACC_ABSTRACT) > 0 || (access & Opcodes.ACC_INTERFACE) > 0) {
                this.isABSClass = true;
            }
            //存到 collectedClassExtendMap 中
            collectedClassExtendMap.put(className, superName);
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String desc,
                                         String signature, String[] exceptions) {
            if (isABSClass) {//如果是虚拟类或者接口 就不管
                return super.visitMethod(access, name, desc, signature, exceptions);
            } else {
                if (!hasWindowFocusMethod) {
                    //该方法是否与onWindowFocusChange方法的签名一致
                    // （该类中是否复写了onWindowFocusChange方法，Activity不用考虑Class混淆)
                    hasWindowFocusMethod = isWindowFocusChangeMethod(name, desc);
                }
                //CollectMethodNode中执行method收集操作
                return new CollectMethodNode(className, access, name, desc, signature, exceptions);
            }
        }
    }

    private class CollectMethodNode extends MethodNode {
        private String className;
        private boolean isConstructor;


        CollectMethodNode(String className, int access, String name, String desc,
                          String signature, String[] exceptions) {
            super(Opcodes.ASM5, access, name, desc, signature, exceptions);
            this.className = className;
        }

        @Override
        public void visitEnd() {
            super.visitEnd();
            //创建TraceMethod
            TraceMethod traceMethod = TraceMethod.create(0, access, className, name, desc);

            //如果是构造方法
            if ("<init>".equals(name)) {
                isConstructor = true;
            }

            //判断类是否 被配置在了 黑名单中
            boolean isNeedTrace = isNeedTrace(configuration, traceMethod.className, mappingCollector);
            //忽略空方法、get/set方法、没有局部变量的简单方法
            if ((isEmptyMethod() || isGetSetMethod() || isSingleMethod())
                    && isNeedTrace) {
                //忽略方法递增
                ignoreCount.incrementAndGet();
                //加入到被忽略方法 map
                collectedIgnoreMethodMap.put(traceMethod.getMethodName(), traceMethod);
                return;
            }

            //不在黑名单中而且没在在methodMapping中配置过的方法加入待插桩的集合；
            if (isNeedTrace && !collectedMethodMap.containsKey(traceMethod.getMethodName())) {
                traceMethod.id = methodId.incrementAndGet();
                collectedMethodMap.put(traceMethod.getMethodName(), traceMethod);
                incrementCount.incrementAndGet();
            } else if (!isNeedTrace && !collectedIgnoreMethodMap.containsKey(traceMethod.className)) {//在黑名单中而且没在在methodMapping中配置过的方法加入ignore插桩的集合
                ignoreCount.incrementAndGet();
                collectedIgnoreMethodMap.put(traceMethod.getMethodName(), traceMethod);
            }

        }

        /**
         * 判断是否是 get方法
         * @return
         */
        private boolean isGetSetMethod() {
            int ignoreCount = 0;
            ListIterator<AbstractInsnNode> iterator = instructions.iterator();
            while (iterator.hasNext()) {
                AbstractInsnNode insnNode = iterator.next();
                int opcode = insnNode.getOpcode();
                if (-1 == opcode) {
                    continue;
                }
                if (opcode != Opcodes.GETFIELD
                        && opcode != Opcodes.GETSTATIC
                        && opcode != Opcodes.H_GETFIELD
                        && opcode != Opcodes.H_GETSTATIC

                        && opcode != Opcodes.RETURN
                        && opcode != Opcodes.ARETURN
                        && opcode != Opcodes.DRETURN
                        && opcode != Opcodes.FRETURN
                        && opcode != Opcodes.LRETURN
                        && opcode != Opcodes.IRETURN

                        && opcode != Opcodes.PUTFIELD
                        && opcode != Opcodes.PUTSTATIC
                        && opcode != Opcodes.H_PUTFIELD
                        && opcode != Opcodes.H_PUTSTATIC
                        && opcode > Opcodes.SALOAD) {
                    if (isConstructor && opcode == Opcodes.INVOKESPECIAL) {
                        ignoreCount++;
                        if (ignoreCount > 1) {
                            return false;
                        }
                        continue;
                    }
                    return false;
                }
            }
            return true;
        }

        private boolean isSingleMethod() {
            ListIterator<AbstractInsnNode> iterator = instructions.iterator();
            while (iterator.hasNext()) {
                AbstractInsnNode insnNode = iterator.next();
                int opcode = insnNode.getOpcode();
                if (-1 == opcode) {
                    continue;
                } else if (Opcodes.INVOKEVIRTUAL <= opcode && opcode <= Opcodes.INVOKEDYNAMIC) {
                    return false;
                }
            }
            return true;
        }


        private boolean isEmptyMethod() {
            ListIterator<AbstractInsnNode> iterator = instructions.iterator();
            while (iterator.hasNext()) {
                AbstractInsnNode insnNode = iterator.next();
                int opcode = insnNode.getOpcode();
                if (-1 == opcode) {
                    continue;
                } else {
                    return false;
                }
            }
            return true;
        }

    }

    public static boolean isWindowFocusChangeMethod(String name, String desc) {
        return null != name && null != desc && name.equals(TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD) && desc.equals(TraceBuildConstants.MATRIX_TRACE_ON_WINDOW_FOCUS_METHOD_ARGS);
    }

    /**
     * 是否需要 被插桩代码
     * @param configuration
     * @param clsName
     * @param mappingCollector
     * @return
     */
    public static boolean isNeedTrace(Configuration configuration, String clsName, MappingCollector mappingCollector) {
        boolean isNeed = true;
        //该类是否在黑名单中
        if (configuration.blackSet.contains(clsName)) {
            isNeed = false;
        } else {
            if (null != mappingCollector) {
                //通过混淆过的 类型 获取原始类名
                clsName = mappingCollector.originalClassName(clsName, clsName);
            }
            clsName = clsName.replaceAll("/", ".");
            for (String packageName : configuration.blackSet) {
                //是否属于黑名单中的配置
                if (clsName.startsWith(packageName.replaceAll("/", "."))) {
                    isNeed = false;
                    break;
                }
            }
        }
        return isNeed;
    }


    private void listClassFiles(ArrayList<File> classFiles, File folder) {
        File[] files = folder.listFiles();
        if (null == files) {
            Log.e(TAG, "[listClassFiles] files is null! %s", folder.getAbsolutePath());
            return;
        }
        for (File file : files) {
            if (file == null) {
                continue;
            }
            if (file.isDirectory()) {
                listClassFiles(classFiles, file);
            } else if (isNeedTraceFile(file.getName())) {
                classFiles.add(file);
            }
        }
    }

    /**
     * 判断是否是需要被插桩的文件
     * @param fileName
     * @return
     */
    public static boolean isNeedTraceFile(String fileName) {
        if (fileName.endsWith(".class")) {
            for (String unTraceCls : TraceBuildConstants.UN_TRACE_CLASS) {
                if (fileName.contains(unTraceCls)) {
                    return false;
                }
            }
        } else {
            return false;
        }
        return true;
    }

}
