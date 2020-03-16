package com.tencent.matrix.trace.transform;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.pipeline.TransformTask;
import com.android.build.gradle.internal.scope.GlobalScope;
import com.android.build.gradle.internal.scope.VariantScope;
import com.android.build.gradle.internal.variant.BaseVariantData;
import com.android.utils.FileUtils;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.hash.Hashing;
import com.tencent.matrix.javalib.util.IOUtil;
import com.tencent.matrix.javalib.util.Log;
import com.tencent.matrix.javalib.util.ReflectUtil;
import com.tencent.matrix.javalib.util.Util;
import com.tencent.matrix.trace.Configuration;
import com.tencent.matrix.trace.MethodCollector;
import com.tencent.matrix.trace.MethodTracer;
import com.tencent.matrix.trace.TraceBuildConstants;
import com.tencent.matrix.trace.extension.MatrixTraceExtension;
import com.tencent.matrix.trace.item.TraceMethod;
import com.tencent.matrix.trace.retrace.MappingCollector;
import com.tencent.matrix.trace.retrace.MappingReader;

import org.gradle.api.Project;
import org.gradle.api.Task;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static com.android.builder.model.AndroidProject.FD_OUTPUTS;

public class MatrixTraceTransform extends Transform {

    private static final String TAG = "MatrixTraceTransform";
    private Configuration config;
    private Transform origTransform;
    //创建大小为16的线程池
    private ExecutorService executor = Executors.newFixedThreadPool(16);

    public static void inject(Project project, MatrixTraceExtension extension, VariantScope variantScope) {

        GlobalScope globalScope = variantScope.getGlobalScope();
        BaseVariantData variant = variantScope.getVariantData();
        //mapping文件存储目录 如： app\build\outputs\mapping\debug
        //在该目录下 Trace 会生成两个 mapping文件 一个是 methodMapping.txt，一个是 ignoreMethodMapping.txt
        String mappingOut = Joiner.on(File.separatorChar).join(
                String.valueOf(globalScope.getBuildDir()),
                FD_OUTPUTS,
                "mapping",
                variantScope.getVariantConfiguration().getDirName());

        //插桩后的 class存储目录 如： app\build\outputs\mapping\debug\traceClass
        String traceClassOut = Joiner.on(File.separatorChar).join(
                String.valueOf(globalScope.getBuildDir()),
                FD_OUTPUTS,
                "traceClassOut",
                variantScope.getVariantConfiguration().getDirName());

        //收集配置信息
        Configuration config = new Configuration.Builder()
                .setPackageName(variant.getApplicationId())//包名
                .setBaseMethodMap(extension.getBaseMethodMapFile())//build.gradle 中配置的 baseMethodMapFile ,保存的是 我们指定需要被 插桩的方法
                .setBlackListFile(extension.getBlackListFile())//build.gradle 中配置的 blackListFile ，保存的是 不需要插桩的文件
                .setMethodMapFilePath(mappingOut + "/methodMapping.txt")// 记录插桩 methodId 和 method的 关系
                .setIgnoreMethodMapFilePath(mappingOut + "/ignoreMethodMapping.txt")// 记录 没有被 插桩的方法
                .setMappingPath(mappingOut) //mapping文件存储目录
                .setTraceClassOut(traceClassOut)//插桩后的 class存储目录
                .build();

        try {
            // 获取 TransformTask.. 具体名称 如：transformClassesWithDexBuilderForDebug 和 transformClassesWithDexForDebug
            // 具体是哪一个 应该和 gradle的版本有关
            // 在该 task之前  proguard 操作 已经完成
            String[] hardTask = getTransformTaskName(extension.getCustomDexTransformName(), variant.getName());
            for (Task task : project.getTasks()) {
                for (String str : hardTask) {
                    // 找到 task 并进行 hook
                    if (task.getName().equalsIgnoreCase(str) && task instanceof TransformTask) {
                        TransformTask transformTask = (TransformTask) task;
                        Log.i(TAG, "successfully inject task:" + transformTask.getName());
                        Field field = TransformTask.class.getDeclaredField("transform");
                        field.setAccessible(true);
                        // 将 系统的  "transformClassesWithDexBuilderFor.."和"transformClassesWithDexFor.."
                        // 中的 transform 替换为 MatrixTraceTransform(也就是当前类)
                        field.set(task, new MatrixTraceTransform(config, transformTask.getTransform()));
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, e.toString());
        }

    }


    private static String[] getTransformTaskName(String customDexTransformName, String buildTypeSuffix) {
        if (!Util.isNullOrNil(customDexTransformName)) {
            return new String[]{customDexTransformName + "For" + buildTypeSuffix};
        } else {
            String[] names = new String[]{
                    "transformClassesWithDexBuilderFor" + buildTypeSuffix,
                    "transformClassesWithDexFor" + buildTypeSuffix,
            };
            return names;
        }
    }

    public MatrixTraceTransform(Configuration config, Transform origTransform) {
        this.config = config;
        this.origTransform = origTransform;
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    public Set<QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    public void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        super.transform(transformInvocation);
        long start = System.currentTimeMillis();
        try {
            doTransform(transformInvocation); // hack
        } catch (ExecutionException e) {
            e.printStackTrace();
        }
        long cost = System.currentTimeMillis() - start;
        long begin = System.currentTimeMillis();
        //执行原来应该执行的 Transform 的 transform 方法
        origTransform.transform(transformInvocation);
        long origTransformCost = System.currentTimeMillis() - begin;
        Log.i("Matrix." + getName(), "[transform] cost time: %dms %s:%sms MatrixTraceTransform:%sms", System.currentTimeMillis() - start, origTransform.getClass().getSimpleName(), origTransformCost, cost);
    }

    private void doTransform(TransformInvocation transformInvocation) throws ExecutionException, InterruptedException {
        //是否增量编译
        final boolean isIncremental = transformInvocation.isIncremental() && this.isIncremental();

        /**
         * step 1
         * 1. 解析mapping 文件混淆后方法对应关系
         * 2. 替换文件目录
         */
        long start = System.currentTimeMillis();

        List<Future> futures = new LinkedList<>();

        // 存储 混淆前方法、混淆后方法的映射关系
        final MappingCollector mappingCollector = new MappingCollector();
        // methodId 计数器
        final AtomicInteger methodId = new AtomicInteger(0);
        // 存储 需要插桩的 方法名 和 方法的封装对象TraceMethod
        final ConcurrentHashMap<String, TraceMethod> collectedMethodMap = new ConcurrentHashMap<>();

        // 将 ParseMappingTask 放入线程池
        futures.add(executor.submit(new ParseMappingTask(mappingCollector, collectedMethodMap, methodId)));

        //存放原始 源文件 和 输出 源文件的 对应关系
        Map<File, File> dirInputOutMap = new ConcurrentHashMap<>();

        //存放原始jar文件和 输出jar文件 对应关系
        Map<File, File> jarInputOutMap = new ConcurrentHashMap<>();
        Collection<TransformInput> inputs = transformInvocation.getInputs();

        for (TransformInput input : inputs) {

            for (DirectoryInput directoryInput : input.getDirectoryInputs()) {
                futures.add(executor.submit(new CollectDirectoryInputTask(dirInputOutMap, directoryInput, isIncremental)));
            }

            for (JarInput inputJar : input.getJarInputs()) {
                futures.add(executor.submit(new CollectJarInputTask(inputJar, isIncremental, jarInputOutMap, dirInputOutMap)));
            }
        }

        for (Future future : futures) {
            // 等待所有线程 运行完毕
            future.get();
        }
        //清空任务
        futures.clear();

        Log.i(TAG, "[doTransform] Step(1)[Parse]... cost:%sms", System.currentTimeMillis() - start);


        /**
         * step 2
         * 1. 收集需要插桩和不需要插桩的方法，并记录在 mapping文件中
         * 2. 收集类之间的继承关系
         */
        start = System.currentTimeMillis();
        //收集需要插桩的方法信息，每个插桩信息封装成TraceMethod对象
        MethodCollector methodCollector = new MethodCollector(executor, mappingCollector, methodId, config, collectedMethodMap);
        methodCollector.collect(dirInputOutMap.keySet(), jarInputOutMap.keySet());
        Log.i(TAG, "[doTransform] Step(2)[Collection]... cost:%sms", System.currentTimeMillis() - start);

        /**
         * step 3 插桩字节码
         */
        start = System.currentTimeMillis();
        //执行插桩逻辑，在需要插桩方法的入口、出口添加MethodBeat的i/o逻辑
        MethodTracer methodTracer = new MethodTracer(executor, mappingCollector, config, methodCollector.getCollectedMethodMap(), methodCollector.getCollectedClassExtendMap());
        methodTracer.trace(dirInputOutMap, jarInputOutMap);
        Log.i(TAG, "[doTransform] Step(3)[Trace]... cost:%sms", System.currentTimeMillis() - start);

    }


    //    解析 app\build\outputs\mapping\debug\mapping.txt 文件
    private class ParseMappingTask implements Runnable {

        //存储 混淆前方法、混淆后方法的映射关系
        final MappingCollector mappingCollector;
        // 存储 需要插桩的 方法名 和 方法的封装对象TraceMethod
        final ConcurrentHashMap<String, TraceMethod> collectedMethodMap;
        // methodId 计数器
        private final AtomicInteger methodId;

        ParseMappingTask(MappingCollector mappingCollector, ConcurrentHashMap<String, TraceMethod> collectedMethodMap, AtomicInteger methodId) {
            this.mappingCollector = mappingCollector;
            this.collectedMethodMap = collectedMethodMap;
            this.methodId = methodId;
        }

        @Override
        public void run() {
            try {
                long start = System.currentTimeMillis();

                //获取 mapping.txt 文件对象(混淆后的 记录混淆后前后关系的文件)
                File mappingFile = new File(config.mappingDir, "mapping.txt");
                //如果混淆文件存在
                if (mappingFile.exists() && mappingFile.isFile()) {
                    MappingReader mappingReader = new MappingReader(mappingFile);
                    // 解析 mapping.txt 文件 并将解析出来的 数据保存到 MappingCollector中
                    mappingReader.read(mappingCollector);
                }
                // 解析 黑名单 文件 并保存到 Configuration.blackSet 中
                int size = config.parseBlackFile(mappingCollector);

                // 获取用户配置的 methodMapping 文件（里面包含 用户自定义的需要 插桩的方法）
                File baseMethodMapFile = new File(config.baseMethodMapPath);
                // 解析 用户配置的 methodMapping 文件 并将内容保存在 collectedMethodMap中
                getMethodFromBaseMethod(baseMethodMapFile, collectedMethodMap);

                //将collectedMethodMap中的原始内容 转换为混淆后的内容 并重新保存到 collectedMethodMap
                retraceMethodMap(mappingCollector, collectedMethodMap);

                Log.i(TAG, "[ParseMappingTask#run] cost:%sms, black size:%s, collect %s method from %s", System.currentTimeMillis() - start, size, collectedMethodMap.size(), config.baseMethodMapPath);

            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private void retraceMethodMap(MappingCollector processor, ConcurrentHashMap<String, TraceMethod> methodMap) {
            if (null == processor || null == methodMap) {
                return;
            }
            HashMap<String, TraceMethod> retraceMethodMap = new HashMap<>(methodMap.size());
            for (Map.Entry<String, TraceMethod> entry : methodMap.entrySet()) {
                TraceMethod traceMethod = entry.getValue();
                traceMethod.proguard(processor);
                retraceMethodMap.put(traceMethod.getMethodName(), traceMethod);
            }
            methodMap.clear();
            methodMap.putAll(retraceMethodMap);
            retraceMethodMap.clear();
        }

        private void getMethodFromBaseMethod(File baseMethodFile, ConcurrentHashMap<String, TraceMethod> collectedMethodMap) {
            if (!baseMethodFile.exists()) {
                Log.w(TAG, "[getMethodFromBaseMethod] not exist!%s", baseMethodFile.getAbsolutePath());
                return;
            }
            Scanner fileReader = null;
            try {
                fileReader = new Scanner(baseMethodFile, "UTF-8");
                while (fileReader.hasNext()) {
                    String nextLine = fileReader.nextLine();
                    if (!Util.isNullOrNil(nextLine)) {
                        nextLine = nextLine.trim();
                        if (nextLine.startsWith("#")) {
                            Log.i("[getMethodFromBaseMethod] comment %s", nextLine);
                            continue;
                        }
                        String[] fields = nextLine.split(",");
                        TraceMethod traceMethod = new TraceMethod();
                        traceMethod.id = Integer.parseInt(fields[0]);
                        traceMethod.accessFlag = Integer.parseInt(fields[1]);
                        String[] methodField = fields[2].split(" ");
                        traceMethod.className = methodField[0].replace("/", ".");
                        traceMethod.methodName = methodField[1];
                        if (methodField.length > 2) {
                            traceMethod.desc = methodField[2].replace("/", ".");
                        }
                        //将 baseMethodMapFile 中配置的 内容解析并 添加到 collectedMethodMap中
                        collectedMethodMap.put(traceMethod.getMethodName(), traceMethod);
                        if (methodId.get() < traceMethod.id && traceMethod.id != TraceBuildConstants.METHOD_ID_DISPATCH) {
                            //设置 methodId 计数器
                            methodId.set(traceMethod.id);
                        }

                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "[getMethodFromBaseMethod] err!");
            } finally {
                if (fileReader != null) {
                    fileReader.close();
                }
            }
        }
    }


    private class CollectDirectoryInputTask implements Runnable {

        Map<File, File> dirInputOutMap;//存放原始 源文件 和 输出 源文件的 对应关系
        DirectoryInput directoryInput;// 输入文件
        boolean isIncremental;
        String traceClassOut;

        CollectDirectoryInputTask(Map<File, File> dirInputOutMap, DirectoryInput directoryInput, boolean isIncremental) {
            this.dirInputOutMap = dirInputOutMap;
            this.directoryInput = directoryInput;
            this.isIncremental = isIncremental;
            this.traceClassOut = config.traceClassOut;
        }

        @Override
        public void run() {
            try {
                handle();
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("Matrix." + getName(), "%s", e.toString());
            }
        }

        private void handle() throws IOException, IllegalAccessException, NoSuchFieldException, ClassNotFoundException {
            //获取原始文件
            final File dirInput = directoryInput.getFile();
            //创建输出文件
            final File dirOutput = new File(traceClassOut, dirInput.getName());
            final String inputFullPath = dirInput.getAbsolutePath();
            final String outputFullPath = dirOutput.getAbsolutePath();

            if (!dirOutput.exists()) {
                dirOutput.mkdirs();
            }

            if (!dirInput.exists() && dirOutput.exists()) {
                if (dirOutput.isDirectory()) {
                    FileUtils.deleteFolder(dirOutput);
                } else {
                    FileUtils.delete(dirOutput);
                }
            }

            if (isIncremental) {//增量更新，只 操作有改动的文件
                Map<File, Status> fileStatusMap = directoryInput.getChangedFiles();

                //保存输出文件和其状态的 map
                final Map<File, Status> outChangedFiles = new HashMap<>();

                for (Map.Entry<File, Status> entry : fileStatusMap.entrySet()) {
                    final Status status = entry.getValue();
                    final File changedFileInput = entry.getKey();

                    final String changedFileInputFullPath = changedFileInput.getAbsolutePath();
                    //增量编译模式下之前的build输出已经重定向到dirOutput；替换成output的目录
                    final File changedFileOutput = new File(changedFileInputFullPath.replace(inputFullPath, outputFullPath));

                    if (status == Status.ADDED || status == Status.CHANGED) {
                        //新增、修改的Class文件，此次需要扫描
                        dirInputOutMap.put(changedFileInput, changedFileOutput);
                    } else if (status == Status.REMOVED) {
                        //删除的Class文件，将文件直接删除
                        changedFileOutput.delete();
                    }
                    outChangedFiles.put(changedFileOutput, status);
                }

                //使用反射 替换directoryInput的  改动文件目录
                replaceChangedFile(directoryInput, outChangedFiles);

            } else {
                //全量编译模式下，所有的Class文件都需要扫描
                dirInputOutMap.put(dirInput, dirOutput);
            }
            //反射input，将dirOutput设置为其输出目录
            replaceFile(directoryInput, dirOutput);
        }
    }

    private class CollectJarInputTask implements Runnable {
        JarInput inputJar;
        boolean isIncremental;
        Map<File, File> jarInputOutMap;
        Map<File, File> dirInputOutMap;

        CollectJarInputTask(JarInput inputJar, boolean isIncremental, Map<File, File> jarInputOutMap, Map<File, File> dirInputOutMap) {
            this.inputJar = inputJar;
            this.isIncremental = isIncremental;
            this.jarInputOutMap = jarInputOutMap;
            this.dirInputOutMap = dirInputOutMap;
        }

        @Override
        public void run() {
            try {
                handle();
            } catch (Exception e) {
                e.printStackTrace();
                Log.e("Matrix." + getName(), "%s", e.toString());
            }
        }

        private void handle() throws IllegalAccessException, NoSuchFieldException, ClassNotFoundException, IOException {
            // traceClassOut 文件夹地址
            String traceClassOut = config.traceClassOut;

            final File jarInput = inputJar.getFile();
            //创建唯一的 文件
            final File jarOutput = new File(traceClassOut, getUniqueJarName(jarInput));
            if (jarOutput.exists()) {
                jarOutput.delete();
            }
            if (!jarOutput.getParentFile().exists()) {
                jarOutput.getParentFile().mkdirs();
            }

            if (IOUtil.isRealZipOrJar(jarInput)) {
                if (isIncremental) {//是增量
                    if (inputJar.getStatus() == Status.ADDED || inputJar.getStatus() == Status.CHANGED) {
                        //存放到 jarInputOutMap 中
                        jarInputOutMap.put(jarInput, jarOutput);
                    } else if (inputJar.getStatus() == Status.REMOVED) {
                        jarOutput.delete();
                    }

                } else {
                    //存放到 jarInputOutMap 中
                    jarInputOutMap.put(jarInput, jarOutput);
                }

            } else {// 专门用于 处理 WeChat AutoDex.jar 文件 可以略过，意义不大
                Log.i(TAG, "Special case for WeChat AutoDex. Its rootInput jar file is actually a txt file contains path list.");
                // Special case for WeChat AutoDex. Its rootInput jar file is actually
                // a txt file contains path list.
                BufferedReader br = null;
                BufferedWriter bw = null;
                try {
                    br = new BufferedReader(new InputStreamReader(new FileInputStream(jarInput)));
                    bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(jarOutput)));
                    String realJarInputFullPath;
                    while ((realJarInputFullPath = br.readLine()) != null) {
                        // src jar.
                        final File realJarInput = new File(realJarInputFullPath);
                        // dest jar, moved to extra guard intermediate output dir.
                        final File realJarOutput = new File(traceClassOut, getUniqueJarName(realJarInput));

                        if (realJarInput.exists() && IOUtil.isRealZipOrJar(realJarInput)) {
                            jarInputOutMap.put(realJarInput, realJarOutput);
                        } else {
                            realJarOutput.delete();
                            if (realJarInput.exists() && realJarInput.isDirectory()) {
                                File realJarOutputDir = new File(traceClassOut, realJarInput.getName());
                                if (!realJarOutput.exists()) {
                                    realJarOutput.mkdirs();
                                }
                                dirInputOutMap.put(realJarInput, realJarOutputDir);
                            }

                        }
                        // write real output full path to the fake jar at rootOutput.
                        final String realJarOutputFullPath = realJarOutput.getAbsolutePath();
                        bw.write(realJarOutputFullPath);
                        bw.newLine();
                    }
                } catch (FileNotFoundException e) {
                    Log.e("Matrix." + getName(), "FileNotFoundException:%s", e.toString());
                } finally {
                    IOUtil.closeQuietly(br);
                    IOUtil.closeQuietly(bw);
                }
                jarInput.delete(); // delete raw inputList
            }

            //将 inputJar 的 file 属性替换为 jarOutput
            replaceFile(inputJar, jarOutput);

        }
    }

    /**
     * 对jar包路径进行 hash ,并拼接成 新的 名称， 对路径进行hash，肯定比 整个jar包进行hash 效率高得多
     * @param jarFile
     * @return
     */
    protected String getUniqueJarName(File jarFile) {
        final String origJarName = jarFile.getName();
        final String hashing = Hashing.sha1().hashString(jarFile.getPath(), Charsets.UTF_16LE).toString();
        final int dotPos = origJarName.lastIndexOf('.');
        if (dotPos < 0) {
            return String.format("%s_%s", origJarName, hashing);
        } else {
            final String nameWithoutDotExt = origJarName.substring(0, dotPos);
            final String dotExt = origJarName.substring(dotPos);
            return String.format("%s_%s%s", nameWithoutDotExt, hashing, dotExt);
        }
    }

    // //反射input，将dirOutput设置为其输出目录
    private void replaceFile(QualifiedContent input, File newFile) throws NoSuchFieldException, ClassNotFoundException, IllegalAccessException {
        final Field fileField = ReflectUtil.getDeclaredFieldRecursive(input.getClass(), "file");
        fileField.set(input, newFile);
    }

    //使用反射 替换directoryInput的  改动文件目录
    private void replaceChangedFile(DirectoryInput dirInput, Map<File, Status> changedFiles) throws NoSuchFieldException, ClassNotFoundException, IllegalAccessException {
        final Field changedFilesField = ReflectUtil.getDeclaredFieldRecursive(dirInput.getClass(), "changedFiles");
        changedFilesField.set(dirInput, changedFiles);
    }

}
