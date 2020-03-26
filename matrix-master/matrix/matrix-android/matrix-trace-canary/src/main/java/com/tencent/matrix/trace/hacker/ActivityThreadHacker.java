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

package com.tencent.matrix.trace.hacker;

import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;

import com.tencent.matrix.trace.core.AppMethodBeat;
import com.tencent.matrix.util.MatrixLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Created by caichongyang on 2017/5/26.
 **/
public class ActivityThreadHacker {
    private static final String TAG = "Matrix.ActivityThreadHacker";
    private static long sApplicationCreateBeginTime = 0L;//APP启动时间
    private static long sApplicationCreateEndTime = 0L;//第一次 启动activity ，service ，brodcast 的时候被认为是 APP启动结束的时间
    private static long sLastLaunchActivityTime = 0L;//最近一个activity的启动时间
    public static AppMethodBeat.IndexRecord sLastLaunchActivityMethodIndex = new AppMethodBeat.IndexRecord();
    public static AppMethodBeat.IndexRecord sApplicationCreateBeginMethodIndex = new AppMethodBeat.IndexRecord();
    public static int sApplicationCreateScene = -100;//app 启动时的场景（可分为 activity ，service ，brodcast ）

    public static void hackSysHandlerCallback() {
        try {
            //当前方法加载的时间 被认为是 APP启动时间，
            sApplicationCreateBeginTime = SystemClock.uptimeMillis();
            //记录这第一个方法，
            sApplicationCreateBeginMethodIndex = AppMethodBeat.getInstance().maskIndex("ApplicationCreateBeginMethodIndex");

            //替换 ActivityThread 中handler的 callBack 方法
            Class<?> forName = Class.forName("android.app.ActivityThread");
            Field field = forName.getDeclaredField("sCurrentActivityThread");
            field.setAccessible(true);
            //获得 ActivityThread 对象
            Object activityThreadValue = field.get(forName);
            Field mH = forName.getDeclaredField("mH");
            mH.setAccessible(true);
            //获得handler对象
            Object handler = mH.get(activityThreadValue);
            Class<?> handlerClass = handler.getClass().getSuperclass();
            Field callbackField = handlerClass.getDeclaredField("mCallback");
            callbackField.setAccessible(true);
            //获得 Handler.Callback 对象
            Handler.Callback originalCallback = (Handler.Callback) callbackField.get(handler);
            HackCallback callback = new HackCallback(originalCallback);
            //设置新的callback对象
            callbackField.set(handler, callback);
            MatrixLog.i(TAG, "hook system handler completed. start:%s SDK_INT:%s", sApplicationCreateBeginTime, Build.VERSION.SDK_INT);
        } catch (Exception e) {
            MatrixLog.e(TAG, "hook system handler err! %s", e.getCause().toString());
        }
    }

    //获取 Application 启动时间
    public static long getApplicationCost() {
        return ActivityThreadHacker.sApplicationCreateEndTime - ActivityThreadHacker.sApplicationCreateBeginTime;
    }

    //记录的应该是 Application启动时间 ，看到这个方法名（蛋碎时间），不得不说大厂程序员文化程度真高啊。
    public static long getEggBrokenTime() {
        return ActivityThreadHacker.sApplicationCreateBeginTime;
    }

    //最近一个activity被启动的时间
    public static long getLastLaunchActivityTime() {
        return ActivityThreadHacker.sLastLaunchActivityTime;
    }


    private final static class HackCallback implements Handler.Callback {
        private static final int LAUNCH_ACTIVITY = 100;
        private static final int CREATE_SERVICE = 114;
        private static final int RECEIVER = 113;
        public static final int EXECUTE_TRANSACTION = 159; // for Android 9.0
        private static boolean isCreated = false; //记录Application是否已经启动
        private static int hasPrint = 10;

        private final Handler.Callback mOriginalCallback;

        HackCallback(Handler.Callback callback) {
            this.mOriginalCallback = callback;
        }

        @Override
        public boolean handleMessage(Message msg) {

            if (!AppMethodBeat.isRealTrace()) {
                //将 handleMessage 的控制权交还给 mOriginalCallback
                return null != mOriginalCallback && mOriginalCallback.handleMessage(msg);
            }

            //是否是打开activity的handler信息
            boolean isLaunchActivity = isLaunchActivity(msg);
            if (hasPrint > 0) {//控制打印十条log?
                MatrixLog.i(TAG, "[handleMessage] msg.what:%s begin:%s isLaunchActivity:%s", msg.what, SystemClock.uptimeMillis(), isLaunchActivity);
                hasPrint--;
            }
            //打开一次activity，就记录一次数据
            if (isLaunchActivity) {
                ActivityThreadHacker.sLastLaunchActivityTime = SystemClock.uptimeMillis();
                ActivityThreadHacker.sLastLaunchActivityMethodIndex = AppMethodBeat.getInstance().maskIndex("LastLaunchActivityMethodIndex");
            }

            if (!isCreated) {
                if (isLaunchActivity || msg.what == CREATE_SERVICE || msg.what == RECEIVER) { // 如果是启动activity、service，receiver
                    //发送启动Activity等消息，认为是Application onCreate的结束时间
                    ActivityThreadHacker.sApplicationCreateEndTime = SystemClock.uptimeMillis();
                    ActivityThreadHacker.sApplicationCreateScene = msg.what;
                    isCreated = true;
                }
            }

            //将 handleMessage 的控制权交还给 mOriginalCallback
            return null != mOriginalCallback && mOriginalCallback.handleMessage(msg);
        }

        private Method method = null;

        /**
         * 判断 主线程 handle 的信息是否是 要 打开 一个Activity
         *
         * @param msg
         * @return
         */
        private boolean isLaunchActivity(Message msg) {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
                if (msg.what == EXECUTE_TRANSACTION && msg.obj != null) {
                    try {
                        if (null == method) {
                            Class clazz = Class.forName("android.app.servertransaction.ClientTransaction");
                            method = clazz.getDeclaredMethod("getCallbacks");
                            method.setAccessible(true);
                        }
                        List list = (List) method.invoke(msg.obj);
                        if (!list.isEmpty()) {
                            return list.get(0).getClass().getName().endsWith(".LaunchActivityItem");
                        }
                    } catch (Exception e) {
                        MatrixLog.e(TAG, "[isLaunchActivity] %s", e);
                    }
                }
                return msg.what == LAUNCH_ACTIVITY;
            } else {
                return msg.what == LAUNCH_ACTIVITY;
            }
        }
    }


}
