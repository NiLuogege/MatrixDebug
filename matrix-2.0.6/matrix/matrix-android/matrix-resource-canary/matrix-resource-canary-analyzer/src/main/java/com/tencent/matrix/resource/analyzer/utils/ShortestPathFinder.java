/*
 * Copyright (C) 2015 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.tencent.matrix.resource.analyzer.utils;

import com.squareup.haha.perflib.ArrayInstance;
import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Field;
import com.squareup.haha.perflib.HahaSpy;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.RootType;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.Type;
import com.tencent.matrix.resource.analyzer.model.ExcludedRefs;
import com.tencent.matrix.resource.analyzer.model.Exclusion;
import com.tencent.matrix.resource.analyzer.model.ReferenceChain;
import com.tencent.matrix.resource.analyzer.model.ReferenceNode;
import com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static com.squareup.haha.perflib.HahaHelper.extendsThread;
import static com.squareup.haha.perflib.HahaHelper.fieldToString;
import static com.squareup.haha.perflib.HahaHelper.isPrimitiveOrWrapperArray;
import static com.squareup.haha.perflib.HahaHelper.isPrimitiveWrapper;
import static com.squareup.haha.perflib.HahaHelper.threadName;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.ARRAY;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.CLASS;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.OBJECT;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Holder.THREAD;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.ARRAY_ENTRY;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.INSTANCE_FIELD;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.LOCAL;
import static com.tencent.matrix.resource.analyzer.model.ReferenceTraceElement.Type.STATIC_FIELD;

/**
 * This class is ported from LeakCanary.
 *
 * Not thread safe.
 *
 * Finds the shortest path from a reference to a gc root, ignoring excluded
 * refs first and then including the ones that are not "always ignorable" as needed if no path is
 * found.
 */
public final class ShortestPathFinder {
    private static final String ANONYMOUS_CLASS_NAME_PATTERN = "^.+\\$\\d+$";

    //可忽略的引用链
    private final ExcludedRefs excludedRefs;
    private final Queue<ReferenceNode> toVisitQueue;
    private final Queue<ReferenceNode> toVisitIfNoPathQueue;
    private final Set<Instance> toVisitSet;
    private final Set<Instance> toVisitIfNoPathSet;
    private final Set<Instance> visitedSet;
    private boolean canIgnoreStrings;

    /**
     * @param excludedRefs 可忽略的引用链
     */
    public ShortestPathFinder(ExcludedRefs excludedRefs) {
        this.excludedRefs = excludedRefs;
        toVisitQueue = new LinkedList<>();
        toVisitIfNoPathQueue = new LinkedList<>();
        toVisitSet = new HashSet<>();
        toVisitIfNoPathSet = new HashSet<>();
        visitedSet = new HashSet<>();
    }

    public static final class Result {
        //引用链的头结点
        public final ReferenceNode referenceChainHead;
        public final boolean excludingKnown;

        Result(ReferenceNode referenceChainHead, boolean excludingKnown) {
            this.referenceChainHead = referenceChainHead;
            this.excludingKnown = excludingKnown;
        }

        //构建引用链
        public ReferenceChain buildReferenceChain() {
            List<ReferenceTraceElement> elements = new ArrayList<>();
            // We iterate from the leak to the GC root
            ReferenceNode node = new ReferenceNode(null,
                    null, referenceChainHead, null, null);
            while (node != null) {
                ReferenceTraceElement element = buildReferenceTraceElement(node);
                if (element != null) {
                    elements.add(0, element);
                }
                node = node.parent;
            }
            return new ReferenceChain(elements);
        }

        private ReferenceTraceElement buildReferenceTraceElement(ReferenceNode node) {
            if (node.parent == null) {
                // Ignore any root node.
                return null;
            }
            Instance holder = node.parent.instance;

            if (holder instanceof RootObj) {
                return null;
            }
            ReferenceTraceElement.Type type = node.referenceType;
            String referenceName = node.referenceName;

            ReferenceTraceElement.Holder holderType;
            String className;
            String extra = null;
            List<String> fields = describeFields(holder);

            className = getClassName(holder);

            if (holder instanceof ClassObj) {
                holderType = CLASS;
            } else if (holder instanceof ArrayInstance) {
                holderType = ARRAY;
            } else {
                ClassObj classObj = holder.getClassObj();
                if (extendsThread(classObj)) {
                    holderType = THREAD;
                    String threadName = threadName(holder);
                    extra = "(named '" + threadName + "')";
                } else if (className.matches(ANONYMOUS_CLASS_NAME_PATTERN)) {
                    String parentClassName = classObj.getSuperClassObj().getClassName();
                    if (Object.class.getName().equals(parentClassName)) {
                        holderType = OBJECT;
                        try {
                            // This is an anonymous class implementing an interface. The API does not give access
                            // to the interfaces implemented by the class. We check if it's in the class path and
                            // use that instead.
                            Class<?> actualClass = Class.forName(classObj.getClassName());
                            Class<?>[] interfaces = actualClass.getInterfaces();
                            if (interfaces.length > 0) {
                                Class<?> implementedInterface = interfaces[0];
                                extra = "(anonymous implementation of " + implementedInterface.getName() + ")";
                            } else {
                                extra = "(anonymous subclass of java.lang.Object)";
                            }
                        } catch (ClassNotFoundException ignored) {
                            // Ignored.
                        }
                    } else {
                        holderType = OBJECT;
                        // Makes it easier to figure out which anonymous class we're looking at.
                        extra = "(anonymous subclass of " + parentClassName + ")";
                    }
                } else {
                    holderType = OBJECT;
                }
            }
            return new ReferenceTraceElement(referenceName, type, holderType,
                    className, extra, node.exclusion, fields);
        }

        private List<String> describeFields(Instance instance) {
            List<String> fields = new ArrayList<>();

            if (instance instanceof ClassObj) {
                ClassObj classObj = (ClassObj) instance;
                for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
                    Field field = entry.getKey();
                    Object value = entry.getValue();
                    fields.add("static " + field.getName() + " = " + value);
                }
            } else if (instance instanceof ArrayInstance) {
                ArrayInstance arrayInstance = (ArrayInstance) instance;
                if (arrayInstance.getArrayType() == Type.OBJECT) {
                    Object[] values = arrayInstance.getValues();
                    for (int i = 0; i < values.length; i++) {
                        fields.add("[" + i + "] = " + values[i]);
                    }
                }
            } else {
                ClassObj classObj = instance.getClassObj();
                for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
                    fields.add("static " + fieldToString(entry));
                }
                ClassInstance classInstance = (ClassInstance) instance;
                for (ClassInstance.FieldValue field : classInstance.getValues()) {
                    fields.add(fieldToString(field));
                }
            }
            return fields;
        }

        private String getClassName(Instance instance) {
            String className;
            if (instance instanceof ClassObj) {
                ClassObj classObj = (ClassObj) instance;
                className = classObj.getClassName();
            } else if (instance instanceof ArrayInstance) {
                ArrayInstance arrayInstance = (ArrayInstance) instance;
                className = arrayInstance.getClassObj().getClassName();
            } else {
                ClassObj classObj = instance.getClassObj();
                className = classObj.getClassName();
            }
            return className;
        }
    }

    /**
     * 查找最短引用链
     * @param snapshot
     * @param targetReference 泄漏的Activity的 Instance 对象
     * @return
     */
    public Result findPath(Snapshot snapshot, Instance targetReference) {
        final List<Instance> targetRefList = new ArrayList<>();
        targetRefList.add(targetReference);
        //调用 重载方法 进行查找
        final Map<Instance, Result> results = findPath(snapshot, targetRefList);
        if (results == null || results.isEmpty()) {
            return new Result(null, false);
        } else {
            return results.get(targetReference);
        }
    }
    /**
     * 查找最短引用链 ，核心思想是
     * 通过所有的Gcroot开始查找 泄漏点 ，直到找到泄漏位置
     *
     *
     * @param snapshot
     * @param targetReferences 一般只有一个泄漏的Activity的 Instance 对象
     * @return
     */
    public Map<Instance, Result> findPath(Snapshot snapshot, Collection<Instance> targetReferences) {
        final Map<Instance, Result> results = new HashMap<>();

        if (targetReferences.isEmpty()) {
            return results;
        }

        //重置状态
        clearState();
        //将gcroot 添加到 引用链集合中
        enqueueGcRoots(snapshot);

        //是否可以忽略String，一般都是可以忽略的
        canIgnoreStrings = true;
        for (Instance targetReference : targetReferences) {
            //如果泄漏点是String 则不忽略String类型 ，一般泄漏点都不是String
            if (isString(targetReference)) {
                canIgnoreStrings = false;
                break;
            }
        }

        final Set<Instance> targetRefSet = new HashSet<>(targetReferences);

        while (!toVisitQueue.isEmpty() || !toVisitIfNoPathQueue.isEmpty()) {
            ReferenceNode node;
            //拿到一个引用，第一次取的话就是GCRoot
            if (!toVisitQueue.isEmpty()) {
                //从队列头取出一个
                node = toVisitQueue.poll();
            } else {
                node = toVisitIfNoPathQueue.poll();
                if (node.exclusion == null) {
                    throw new IllegalStateException("Expected node to have an exclusion " + node);
                }
            }

            // Termination
            // 引用链已经触及到 泄漏点了，可以终止了
            if (targetRefSet.contains(node.instance)) {
                //找到了 泄漏点，这个已经保存了整个引用链了
                results.put(node.instance, new Result(node, node.exclusion != null));
                //删除泄漏点
                targetRefSet.remove(node.instance);
                if (targetRefSet.isEmpty()) {
                    break;
                }
            }

            //避免重复记录
            if (checkSeen(node)) {
                continue;
            }


            if (node.instance instanceof RootObj) {//处理 gcRoot
                visitRootObj(node);
            } else if (node.instance instanceof ClassObj) {//处理字节码对象
                visitClassObj(node);
            } else if (node.instance instanceof ClassInstance) {//处理类实例对象
                visitClassInstance(node);
            } else if (node.instance instanceof ArrayInstance) {//处理数组对象
                visitArrayInstance(node);
            } else {
                throw new IllegalStateException("Unexpected type for " + node.instance);
            }
        }
        return results;
    }

    private void clearState() {
        toVisitQueue.clear();
        toVisitIfNoPathQueue.clear();
        toVisitSet.clear();
        toVisitIfNoPathSet.clear();
        visitedSet.clear();
    }

    /**
     * 将需要分析的Gc root 加入到 引用链集合中
     * @param snapshot
     */
    private void enqueueGcRoots(Snapshot snapshot) {
        for (RootObj rootObj : snapshot.getGCRoots()) {
            switch (rootObj.getRootType()) {
                case JAVA_LOCAL:
                    Instance thread = HahaSpy.allocatingThread(rootObj);
                    String threadName = threadName(thread);
                    Exclusion params = excludedRefs.threadNames.get(threadName);
                    if (params == null || !params.alwaysExclude) {
                        enqueue(params, null, rootObj, null, null);
                    }
                    break;
                case INTERNED_STRING:
                case DEBUGGER:
                case INVALID_TYPE:
                    // An object that is unreachable from any other root, but not a root itself.
                case UNREACHABLE:
                case UNKNOWN:
                    // An object that is in a queue, waiting for a finalizer to run.
                case FINALIZING:
                    //上面这些不做分析
                    break;
                case SYSTEM_CLASS:
                case VM_INTERNAL:
                    // A local variable in native code.
                case NATIVE_LOCAL:
                    // A global variable in native code.
                case NATIVE_STATIC:
                    // An object that was referenced from an active thread block.
                case THREAD_BLOCK:
                    // Everything that called the wait() or notify() methods, or that is synchronized.
                case BUSY_MONITOR:
                case NATIVE_MONITOR:
                case REFERENCE_CLEANUP:
                    // Input or output parameters in native code.
                case NATIVE_STACK:
                case JAVA_STATIC:
                    //上面这些 直接加入到队列
                    enqueue(null, null, rootObj, null, null);
                    break;
                default:
                    throw new UnsupportedOperationException("Unknown root type:" + rootObj.getRootType());
            }
        }
    }

    private boolean checkSeen(ReferenceNode node) {
        return !visitedSet.add(node.instance);
    }

    //将 Gcroot 依赖的对象加入到引用链队列中
    private void visitRootObj(ReferenceNode node) {
        RootObj rootObj = (RootObj) node.instance;
//        System.out.println("visitRootObj "+rootObj.toString());
        //获取到被gcroot 引用的 对象
        Instance child = rootObj.getReferredInstance();

        //如果是 java本地栈 类型则会排除一些东西，不过这都不重要，重要的是将 Gcroot 依赖的对象加入到引用链队列中
        if (rootObj.getRootType() == RootType.JAVA_LOCAL) {
            Instance holder = HahaSpy.allocatingThread(rootObj);
            // We switch the parent node with the thread instance that holds
            // the local reference.
            Exclusion exclusion = null;
            if (node.exclusion != null) {
                exclusion = node.exclusion;
            }
            ReferenceNode parent = new ReferenceNode(null, holder, null, null, null);
            enqueue(exclusion, parent, child, "<Java Local>", LOCAL);
        } else {
            enqueue(null, node, child, null, null);
        }
    }

    //将字节码文件的静态变量对象加入到引用链队列中
    private void visitClassObj(ReferenceNode node) {
        ClassObj classObj = (ClassObj) node.instance;
//        System.out.println("visitClassObj "+classObj.getClassName());
        Map<String, Exclusion> ignoredStaticFields =
                excludedRefs.staticFieldNameByClassName.get(classObj.getClassName());
        for (Map.Entry<Field, Object> entry : classObj.getStaticFieldValues().entrySet()) {
            Field field = entry.getKey();
            if (field.getType() != Type.OBJECT) {
                continue;
            }
            String fieldName = field.getName();
            if ("$staticOverhead".equals(fieldName)) {
                continue;
            }
            Instance child = (Instance) entry.getValue();
            boolean visit = true;
            if (ignoredStaticFields != null) {
                Exclusion params = ignoredStaticFields.get(fieldName);
                if (params != null) {
                    visit = false;
                    if (!params.alwaysExclude) {
                        enqueue(params, node, child, fieldName, STATIC_FIELD);
                    }
                }
            }
            if (visit) {
                enqueue(null, node, child, fieldName, STATIC_FIELD);
            }
        }
    }

    //将实例对象 加入到引用链队列中
    private void visitClassInstance(ReferenceNode node) {
        ClassInstance classInstance = (ClassInstance) node.instance;
//        System.out.println("visitClassInstance "+classInstance.toString());
        Map<String, Exclusion> ignoredFields = new LinkedHashMap<>();
        ClassObj superClassObj = classInstance.getClassObj();
        Exclusion classExclusion = null;
        while (superClassObj != null) {
            Exclusion params = excludedRefs.classNames.get(superClassObj.getClassName());
            if (params != null && (classExclusion == null || !classExclusion.alwaysExclude)) {
                // true overrides null or false.
                classExclusion = params;
            }
            Map<String, Exclusion> classIgnoredFields =
                    excludedRefs.fieldNameByClassName.get(superClassObj.getClassName());
            if (classIgnoredFields != null) {
                ignoredFields.putAll(classIgnoredFields);
            }
            superClassObj = superClassObj.getSuperClassObj();
        }

        if (classExclusion != null && classExclusion.alwaysExclude) {
            return;
        }

        for (ClassInstance.FieldValue fieldValue : classInstance.getValues()) {
            Exclusion fieldExclusion = classExclusion;
            Field field = fieldValue.getField();
            if (field.getType() != Type.OBJECT) {
                continue;
            }
            Instance child = (Instance) fieldValue.getValue();
            String fieldName = field.getName();
            Exclusion params = ignoredFields.get(fieldName);
            // If we found a field exclusion and it's stronger than a class exclusion
            if (params != null && (fieldExclusion == null || (params.alwaysExclude
                    && !fieldExclusion.alwaysExclude))) {
                fieldExclusion = params;
            }
            enqueue(fieldExclusion, node, child, fieldName, INSTANCE_FIELD);
        }
    }

    //将对象数组加入到 引用链队列中
    private void visitArrayInstance(ReferenceNode node) {
        ArrayInstance arrayInstance = (ArrayInstance) node.instance;
//        System.out.println("visitArrayInstance "+arrayInstance.toString());
        Type arrayType = arrayInstance.getArrayType();
        if (arrayType == Type.OBJECT) {
            Object[] values = arrayInstance.getValues();
            for (int i = 0; i < values.length; i++) {
                Instance child = (Instance) values[i];
                enqueue(null, node, child, "[" + i + "]", ARRAY_ENTRY);
            }
        }
    }

    /**
     *
     * @param exclusion
     * @param parent
     * @param child 子引用
     * @param referenceName
     * @param referenceType
     */
    private void enqueue(Exclusion exclusion, ReferenceNode parent, Instance child, String referenceName,
                         ReferenceTraceElement.Type referenceType) {
        if (child == null) {
            return;
        }
        if (isPrimitiveOrWrapperArray(child) || isPrimitiveWrapper(child)) {
            return;
        }
        // Whether we want to visit now or later, we should skip if this is already to visit.
        if (toVisitSet.contains(child)) {
            return;
        }
        boolean visitNow = exclusion == null;
        if (!visitNow && toVisitIfNoPathSet.contains(child)) {
            return;
        }
        if (canIgnoreStrings && isString(child)) {
            return;
        }
        if (visitedSet.contains(child)) {
            return;
        }
        //创建一个引用节点
        ReferenceNode childNode = new ReferenceNode(exclusion, child, parent, referenceName, referenceType);
        //添加到 引用链集合中
        if (visitNow) {
            toVisitSet.add(child);
            //放到队列尾部
            toVisitQueue.add(childNode);
        } else {
            toVisitIfNoPathSet.add(child);
            toVisitIfNoPathQueue.add(childNode);
        }
    }

    private static boolean isString(Instance instance) {
        return instance.getClassObj() != null && instance.getClassObj()
                .getClassName()
                .equals(String.class.getName());
    }
}
