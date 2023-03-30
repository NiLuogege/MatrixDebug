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

package com.tencent.matrix.resource;

import static com.tencent.matrix.resource.common.utils.StreamUtil.closeQuietly;
import static com.tencent.matrix.resource.common.utils.StreamUtil.copyFileToStream;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.tencent.matrix.Matrix;
import com.tencent.matrix.resource.analyzer.model.HeapDump;
import com.tencent.matrix.resource.dumper.DumpStorageManager;
import com.tencent.matrix.resource.hproflib.HprofBufferShrinker;
import com.tencent.matrix.util.MatrixLog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Created by tangyinsheng on 2017/7/11.
 *
 */
public class CanaryWorkerService extends MatrixJobIntentService {
    private static final String TAG = "Matrix.CanaryWorkerService";

    private static final int JOB_ID = 0xFAFBFCFD;
    private static final String ACTION_SHRINK_HPROF = "com.tencent.matrix.resource.worker.action.SHRINK_HPROF";
    private static final String EXTRA_PARAM_HEAPDUMP = "com.tencent.matrix.resource.worker.param.HEAPDUMP";

    /**
     * @param heapDump 内部包含了 dump下来的内存快照和 泄漏的activity 类名
     */
    public static void shrinkHprofAndReport(Context context, HeapDump heapDump) {
        final Intent intent = new Intent(context, CanaryWorkerService.class);
        //代表要裁剪hprof文件
        intent.setAction(ACTION_SHRINK_HPROF);
        intent.putExtra(EXTRA_PARAM_HEAPDUMP, heapDump);
        // 通过 CanaryWorkerService 来裁剪 .hprof 文件
        // 这会通过 JobScheduler 进行后台任务进行执行  ,这里我们不用关心 JobScheduler ,只需要知道会执行 CanaryWorkerService.onHandleWork方法 就行
        enqueueWork(context, CanaryWorkerService.class, JOB_ID, intent);
    }

    @Override
    protected void onHandleWork(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();
            //代表要裁剪hprof文件
            if (ACTION_SHRINK_HPROF.equals(action)) {
                try {
                    intent.setExtrasClassLoader(this.getClassLoader());
                    //获取 HeapDump 对象
                    final HeapDump heapDump = (HeapDump) intent.getSerializableExtra(EXTRA_PARAM_HEAPDUMP);
                    if (heapDump != null) {
                        //进行裁剪
                        doShrinkHprofAndReport(heapDump);
                    } else {
                        MatrixLog.e(TAG, "failed to deserialize heap dump, give up shrinking and reporting.");
                    }
                } catch (Throwable thr) {
                    MatrixLog.printErrStackTrace(TAG, thr,  "failed to deserialize heap dump, give up shrinking and reporting.");
                }
            }
        }
    }

    private void doShrinkHprofAndReport(HeapDump heapDump) {
        //获取 .hprof 文件 的父文件夹
        final File hprofDir = heapDump.getHprofFile().getParentFile();
        //创建裁剪后 .hprof 文件对象
        final File shrinkedHProfFile = new File(hprofDir, getShrinkHprofName(heapDump.getHprofFile()));
        //创建 最终上报的压缩文件对象 路径 如：dump_result_10305_20230330152524.zip
        final File zipResFile = new File(hprofDir, getResultZipName("dump_result_" + android.os.Process.myPid()));
        //获取原始 .hprof 文件
        final File hprofFile = heapDump.getHprofFile();
        ZipOutputStream zos = null;
        try {
            long startTime = System.currentTimeMillis();
            //真正的进行裁剪，源文件是 hprofFile ，裁剪后的文件是 shrinkedHProfFile
            new HprofBufferShrinker().shrink(hprofFile, shrinkedHProfFile);
            MatrixLog.i(TAG, "shrink hprof file %s, size: %dk to %s, size: %dk, use time:%d",
                    hprofFile.getPath(), hprofFile.length() / 1024, shrinkedHProfFile.getPath(), shrinkedHProfFile.length() / 1024, (System.currentTimeMillis() - startTime));

            //创建zip流
            zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipResFile)));

            //创建信息文件
            final ZipEntry resultInfoEntry = new ZipEntry("result.info");
            final ZipEntry shrinkedHProfEntry = new ZipEntry(shrinkedHProfFile.getName());

            //信息文件写入内容，并压缩到 压缩文件中
            zos.putNextEntry(resultInfoEntry);
            final PrintWriter pw = new PrintWriter(new OutputStreamWriter(zos, Charset.forName("UTF-8")));
            pw.println("# Resource Canary Result Infomation. THIS FILE IS IMPORTANT FOR THE ANALYZER !!");
            pw.println("sdkVersion=" + Build.VERSION.SDK_INT);
            pw.println("manufacturer=" + Matrix.with().getPluginByClass(ResourcePlugin.class).getConfig().getManufacture());
            pw.println("hprofEntry=" + shrinkedHProfEntry.getName());
            pw.println("leakedActivityKey=" + heapDump.getReferenceKey());
            pw.flush();
            zos.closeEntry();

            zos.putNextEntry(shrinkedHProfEntry);
            copyFileToStream(shrinkedHProfFile, zos);
            zos.closeEntry();

            shrinkedHProfFile.delete();
            hprofFile.delete();

            MatrixLog.i(TAG, "process hprof file use total time:%d", (System.currentTimeMillis() - startTime));

            //上报
            CanaryResultService.reportHprofResult(this, zipResFile.getAbsolutePath(), heapDump.getActivityName());
        } catch (IOException e) {
            MatrixLog.printErrStackTrace(TAG, e, "");
        } finally {
            closeQuietly(zos);
        }
    }

    //创建裁剪后 .hprof 文件路径
    private String getShrinkHprofName(File origHprof) {
        final String origHprofName = origHprof.getName();
        final int extPos = origHprofName.indexOf(DumpStorageManager.HPROF_EXT);
        final String namePrefix = origHprofName.substring(0, extPos);
        return namePrefix + "_shrink" + DumpStorageManager.HPROF_EXT;
    }

    //创建 最终上报的压缩文件 路径 如：
    //dump_result_10305_20230330152524.zip
    private String getResultZipName(String prefix) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append('_')
                .append(new SimpleDateFormat("yyyyMMddHHmmss", Locale.ENGLISH).format(new Date()))
                .append(".zip");
        return sb.toString();
    }
}
