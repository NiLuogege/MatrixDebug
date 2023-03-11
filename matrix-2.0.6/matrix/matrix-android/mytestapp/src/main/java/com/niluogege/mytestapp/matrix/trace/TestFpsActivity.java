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

package com.niluogege.mytestapp.matrix.trace;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.print.PrinterId;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.niluogege.mytestapp.R;
import com.niluogege.mytestapp.matrix.issue.IssueFilter;
import com.tencent.matrix.Matrix;
import com.tencent.matrix.plugin.Plugin;
import com.tencent.matrix.trace.TracePlugin;
import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.listeners.IDoFrameListener;
import com.tencent.matrix.util.MatrixLog;

import java.util.Random;
import java.util.concurrent.Executor;


/**
 * Created by caichongyang on 2017/11/14.
 *
 * 这个demo主要是 教我们怎么去在Matrix框架下监听帧率
 */

public class TestFpsActivity extends Activity {
    private static final String TAG = "Matrix.TestFpsActivity";
    private ListView mListView;
    private static HandlerThread sHandlerThread = new HandlerThread("test");

    static {
        sHandlerThread.start();
    }

    private long time = System.currentTimeMillis();
    private IDoFrameListener mDoFrameListener = new IDoFrameListener(new Executor() {
        Handler handler = new Handler(sHandlerThread.getLooper());

        @Override
        public void execute(Runnable command) {
            handler.post(command);
        }
    }) {

        @Override
        public void doFrameAsync(String focusedActivity, long startNs, long endNs, int dropFrame, boolean isVsyncFrame, long intendedFrameTimeNs, long inputCostNs, long animationCostNs, long traversalCostNs) {
            super.doFrameAsync(focusedActivity, startNs, endNs, dropFrame, isVsyncFrame, intendedFrameTimeNs, inputCostNs, animationCostNs, traversalCostNs);
            MatrixLog.i(TAG, "[doFrameAsync]" + " costMs=" + (endNs - intendedFrameTimeNs) / Constants.TIME_MILLIS_TO_NANO
                    + " dropFrame=" + dropFrame + " isVsyncFrame=" + isVsyncFrame + " offsetVsync=" + ((startNs - intendedFrameTimeNs) / Constants.TIME_MILLIS_TO_NANO) + " [%s:%s:%s]", inputCostNs, animationCostNs, traversalCostNs);
        }

    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.test_fps_layout);

        IssueFilter.setCurrentFilter(IssueFilter.ISSUE_TRACE);

        //开启监听
        Matrix.with().getPluginByClass(TracePlugin.class).getFrameTracer().onStartTrace();
        //注册帧率监听
        Matrix.with().getPluginByClass(TracePlugin.class).getFrameTracer().addListener(mDoFrameListener);

        time = System.currentTimeMillis();
        mListView = (ListView) findViewById(R.id.list_view);
        String[] data = new String[200];
        for (int i = 0; i < 200; i++) {
            data[i] = "MatrixTrace:" + i;
        }
        mListView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent motionEvent) {
                MatrixLog.i(TAG, "onTouch=" + motionEvent);
                //每次滚动睡 80ms
                SystemClock.sleep(80);
                return false;
            }
        });
        mListView.setAdapter(new ArrayAdapter(this, android.R.layout.simple_list_item_1, data));
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
        //移除监听
        Matrix.with().getPluginByClass(TracePlugin.class).getFrameTracer().removeListener(mDoFrameListener);
        //关闭监听
        Matrix.with().getPluginByClass(TracePlugin.class).getFrameTracer().onCloseTrace();
    }
}
