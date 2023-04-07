/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.tencent.matrix.resource.analyzer.model;

import com.squareup.haha.perflib.HprofParser;
import com.squareup.haha.perflib.Snapshot;
import com.squareup.haha.perflib.io.HprofBuffer;
import com.squareup.haha.perflib.io.MemoryMappedFileBuffer;
import com.tencent.matrix.resource.analyzer.utils.AnalyzeUtil;

import java.io.File;
import java.io.IOException;

import static com.tencent.matrix.resource.common.utils.Preconditions.checkNotNull;


/**
 * Created by tangyinsheng on 2017/7/4.
 */

public class HeapSnapshot {

    private final File mHprofFile;
    private final Snapshot mSnapshot;

    public HeapSnapshot(File hprofFile) throws IOException {
        mHprofFile = checkNotNull(hprofFile, "hprofFile");
        mSnapshot = initSnapshot(hprofFile);
    }

    public File getHprofFile() {
        return mHprofFile;
    }

    public Snapshot getSnapshot() {
        return mSnapshot;
    }

    private static Snapshot initSnapshot(File hprofFile) throws IOException {
        //这里使用 haha库 进行 .hprof 文件的解析
        final HprofBuffer buffer = new MemoryMappedFileBuffer(hprofFile);
        final HprofParser parser = new HprofParser(buffer);
        //解析并返回结果
        final Snapshot result = parser.parse();

        //自己调试的方法，先注释掉
//        testDump(result);

        //这里是对 gcroot 进行修整？以减小内存占用？？
        AnalyzeUtil.deduplicateGcRoots(result);
        return result;
    }

    private static void testDump(Snapshot result) {
        System.out.println("--dumpInstanceCounts--");
        /**
         * 输出对象及其数量 如：
         *
         * com.google.android.material.textview.MaterialTextView: 4
         * androidx.lifecycle.LifecycleRegistry: 3
         * android.os.MessageQueue$IdleHandler[]: 1
         * com.niluogege.mytestapp.MainActivity$1: 1
         */
        result.dumpInstanceCounts();
        System.out.println("--dumpSizes--");
        /**
         * 输出 对象及对象占用内存大小 base为对象占用内存大小（如果是引用只计算引用的地址占用） ，
         * composite 为对象占用总大小，包含所有依赖的对象的总和 如：
         *
         * androidx.appcompat.widget.AppCompatImageButton: base 124, composite 4809118
         * com.niluogege.mytestapp.MainActivity: base 128, composite 4809118
         */
        result.dumpSizes();
        System.out.println("--dumpSubclasses--");
        /**
         * 输出直系子类 如：
         *
         * androidx.appcompat.app.AppCompatActivity
         *      com.niluogege.mytestapp.MainActivity
         *      com.niluogege.mytestapp.matrix.issue.IssuesListActivity
         *      com.niluogege.mytestapp.SplashActivity
         */
        result.dumpSubclasses();
    }
}
