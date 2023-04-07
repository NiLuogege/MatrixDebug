/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.matrix.resource.analyzer;

import com.squareup.haha.perflib.ClassInstance;
import com.squareup.haha.perflib.ClassObj;
import com.squareup.haha.perflib.Instance;
import com.squareup.haha.perflib.Snapshot;
import com.tencent.matrix.resource.analyzer.model.ActivityLeakResult;
import com.tencent.matrix.resource.analyzer.model.ExcludedRefs;
import com.tencent.matrix.resource.analyzer.model.HeapSnapshot;
import com.tencent.matrix.resource.analyzer.model.ReferenceChain;
import com.tencent.matrix.resource.analyzer.utils.AnalyzeUtil;
import com.tencent.matrix.resource.analyzer.utils.ShortestPathFinder;

import java.util.ArrayList;
import java.util.List;

import static com.squareup.haha.perflib.HahaHelper.asString;
import static com.squareup.haha.perflib.HahaHelper.classInstanceValues;
import static com.squareup.haha.perflib.HahaHelper.fieldValue;

/**
 * Created by tangyinsheng on 2017/6/2.
 *
 * This class is ported from LeakCanary.
 */

public class ActivityLeakAnalyzer implements HeapSnapshotAnalyzer<ActivityLeakResult> {
    private static final String DESTROYED_ACTIVITY_INFO_CLASSNAME
            = "com.tencent.matrix.resource.analyzer.model.DestroyedActivityInfo";
    private static final String ACTIVITY_REFERENCE_KEY_FIELDNAME = "mKey";
    private static final String ACTIVITY_REFERENCE_FIELDNAME = "mActivityRef";

    private final String mRefKey;
    private final ExcludedRefs mExcludedRefs;

    /**
     * @param refKey 泄漏点
     * @param excludedRefs 可忽略的引用链
     */
    public ActivityLeakAnalyzer(String refKey, ExcludedRefs excludedRefs) {
        mRefKey = refKey;
        mExcludedRefs = excludedRefs;
    }

    /**
     * 开始进行分析 ，并返回泄漏链
     */
    @Override
    public ActivityLeakResult analyze(HeapSnapshot heapSnapshot) {
        return checkForLeak(heapSnapshot, mRefKey);
    }

    /**
     * Searches the heap dump for a <code>DestroyedActivityInfo</code> instance with the corresponding key,
     * and then computes the shortest strong reference path from the leaked activity that instance holds
     * to the GC roots.
     *
     * 通过 refKey 查找到 它对应的 DestroyedActivityInfo 并返回引用链
     */
    private ActivityLeakResult checkForLeak(HeapSnapshot heapSnapshot, String refKey) {
        long analysisStartNanoTime = System.nanoTime();

        try {
            //获取到 .hprof 文件的解析结果
            final Snapshot snapshot = heapSnapshot.getSnapshot();
            //查找泄漏了的 Activity 的 Instance 对象
            final Instance leakingRef = findLeakingReference(refKey, snapshot);

            // False alarm, weak reference was cleared in between key check and heap dump.
            //异常情况，堆转储的时候 泄漏点被gc 了
            if (leakingRef == null) {
                return ActivityLeakResult.noLeak(AnalyzeUtil.since(analysisStartNanoTime));
            }

            //查找最短引用 ，并返回结果
            return findLeakTrace(analysisStartNanoTime, snapshot, leakingRef);
        } catch (Throwable e) {
            e.printStackTrace();
            return ActivityLeakResult.failure(e, AnalyzeUtil.since(analysisStartNanoTime));
        }
    }

    /**
     *
     * @param key 泄漏点标识
     * @param snapshot
     * @return 返回的是泄漏了的 Activity 的 Instance 对象
     */
    private Instance findLeakingReference(String key, Snapshot snapshot) {
        //获取到 com.tencent.matrix.resource.analyzer.model.DestroyedActivityInfo 的字节码对象
        // 这个就是我们再Activity onDestory的时候将Activity引用封装的对象
        final ClassObj infoClass = snapshot.findClass(DESTROYED_ACTIVITY_INFO_CLASSNAME);
        if (infoClass == null) {
            throw new IllegalStateException("Unabled to find destroy activity info class with name: "
                    + DESTROYED_ACTIVITY_INFO_CLASSNAME);
        }
        List<String> keysFound = new ArrayList<>();
        //获取这个字节码文件所有的实例
        for (Instance infoInstance : infoClass.getInstancesList()) {
            //获取所有成员变量的信息 并封装到FieldValue 对象中，，里面包含了 变量名和变量值
            final List<ClassInstance.FieldValue> values = classInstanceValues(infoInstance);
            //获取成员变量名为 mKey 的值
            final String keyCandidate = asString(fieldValue(values, ACTIVITY_REFERENCE_KEY_FIELDNAME));
            //当值和我们传入的泄漏点 key ，一致的时候就说明找到了 泄露对象
            if (keyCandidate.equals(key)) {
                //获取包装Activity的 弱引用对象
                final Instance weakRefObj = fieldValue(values, ACTIVITY_REFERENCE_FIELDNAME);
                if (weakRefObj == null) {
                    continue;
                }
                //获取弱引用对象的 所有成员变量的信息 并封装到FieldValue 对象中，，里面包含了 变量名和变量值
                final List<ClassInstance.FieldValue> activityRefs = classInstanceValues(weakRefObj);
                //获取到 弱引用的referent成员变量中保存的对象，其实就是那个泄漏了的 Activity ，并返回
                return fieldValue(activityRefs, "referent");
            }
            keysFound.add(keyCandidate);
        }
        throw new IllegalStateException(
                "Could not find weak reference with key " + key + " in " + keysFound);
    }

    /**
     * 查找最短引用链并 返回结果
     *
     * @param analysisStartNanoTime
     * @param snapshot
     * @param leakingRef 泄漏的Activity的 Instance 对象
     * @return
     */
    private ActivityLeakResult findLeakTrace(long analysisStartNanoTime, Snapshot snapshot,
                                         Instance leakingRef) {

        //创建最短引用查找器，并传入可忽略的引用链
        ShortestPathFinder pathFinder = new ShortestPathFinder(mExcludedRefs);
        //查找最短引用链并 返回结果
        ShortestPathFinder.Result result = pathFinder.findPath(snapshot, leakingRef);

        // False alarm, no strong reference path to GC Roots.
        //异常流程不用太关心
        if (result.referenceChainHead == null) {
            return ActivityLeakResult.noLeak(AnalyzeUtil.since(analysisStartNanoTime));
        }

        //构建引用链
        final ReferenceChain referenceChain = result.buildReferenceChain();
        final String className = leakingRef.getClassObj().getClassName();
        if (result.excludingKnown || referenceChain.isEmpty()) {
            return ActivityLeakResult.noLeak(AnalyzeUtil.since(analysisStartNanoTime));
        } else {
            //返回泄漏点引用链 查找结果
            return ActivityLeakResult.leakDetected(false, className, referenceChain,
                    AnalyzeUtil.since(analysisStartNanoTime));
        }
    }
}
