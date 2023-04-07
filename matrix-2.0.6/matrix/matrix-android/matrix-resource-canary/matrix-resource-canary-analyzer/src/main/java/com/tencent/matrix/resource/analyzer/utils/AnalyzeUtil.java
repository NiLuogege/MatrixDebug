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

package com.tencent.matrix.resource.analyzer.utils;

import com.squareup.haha.perflib.RootObj;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.trove.THashMap;
import com.squareup.haha.trove.TObjectProcedure;

import java.util.Collection;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Created by tangyinsheng on 2017/6/6.
 */

public final class AnalyzeUtil {

    /**
     * Pruning duplicates reduces memory pressure from hprof bloat added in Marshmallow.
     *  对棉花糖系统做优化，不是核心流程
     */
    public static void deduplicateGcRoots(Snapshot snapshot) {
        // THashMap has a smaller memory footprint than HashMap.
        final THashMap<String, RootObj> uniqueRootMap = new THashMap<>();

        //获取到 GC roots
        final Collection<RootObj> gcRoots = snapshot.getGCRoots();
        //这里是 一个GCroot 只要一份 ， 对棉花糖系统做优化，不是核心流程
        for (RootObj root : gcRoots) {
            String key = generateRootKey(root);
//            System.out.println("GcRoot="+root.toString() + " key= " + key);
            if (!uniqueRootMap.containsKey(key)) {
                uniqueRootMap.put(key, root);
            }
        }

        // Repopulate snapshot with unique GC roots.
        //重新设置 gcRoot
        gcRoots.clear();
        uniqueRootMap.forEach(new TObjectProcedure<String>() {
            @Override
            public boolean execute(String key) {
                return gcRoots.add(uniqueRootMap.get(key));
            }
        });
    }

    private static String generateRootKey(RootObj root) {
        return String.format("%s@0x%08x", root.getRootType().getName(), root.getId());
    }

    public static long since(long analysisStartNanoTime) {
        return NANOSECONDS.toMillis(System.nanoTime() - analysisStartNanoTime);
    }

    private AnalyzeUtil() {
        throw new UnsupportedOperationException();
    }
}
