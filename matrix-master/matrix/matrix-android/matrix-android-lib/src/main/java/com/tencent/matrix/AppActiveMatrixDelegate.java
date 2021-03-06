package com.tencent.matrix;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.ArrayMap;

import com.tencent.matrix.listeners.IAppForeground;
import com.tencent.matrix.util.MatrixHandlerThread;
import com.tencent.matrix.util.MatrixLog;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

// 主要功能是 监控APP和Activity生命周期，为 各个模块提供支持
//通过枚举的方式 实现的单例模式（奇技淫巧啊）
public enum AppActiveMatrixDelegate {

    INSTANCE;

    private static final String TAG = "Matrix.AppActiveDelegate";
    private final Set<IAppForeground> listeners = new HashSet();
    // 某个 Activity 是否处于前台（基本上可以认为 当前APP是否 可见）
    private boolean isAppForeground = false;
    //当前Activity 名称
    private String visibleScene = "default";
    private Controller controller = new Controller();
    private boolean isInit = false;
    private String currentFragmentName;
    //子线程handler
    private Handler handler;

    public void init(Application application) {
        if (isInit) {
            MatrixLog.e(TAG, "has inited!");
            return;
        }
        this.isInit = true;
        //获取 子线程 handler
        this.handler = new Handler(MatrixHandlerThread.getDefaultHandlerThread().getLooper());
        //监听 onConfigurationChanged 和 onLowMemory
        application.registerComponentCallbacks(controller);
        //监听activity生命周期
        application.registerActivityLifecycleCallbacks(controller);
    }

    public String getCurrentFragmentName() {
        return currentFragmentName;
    }

    /**
     * must set after {@link Activity#onStart()}
     *
     * @param fragmentName
     */
    public void setCurrentFragmentName(String fragmentName) {
        MatrixLog.i(TAG, "[setCurrentFragmentName] fragmentName:%s", fragmentName);
        this.currentFragmentName = fragmentName;
        updateScene(fragmentName);
    }

    public String getVisibleScene() {
        return visibleScene;
    }

    private void onDispatchForeground(String visibleScene) {
        if (isAppForeground || !isInit) {
            return;
        }

        MatrixLog.i(TAG, "onForeground... visibleScene[%s]", visibleScene);
        handler.post(new Runnable() {
            @Override
            public void run() {
                isAppForeground = true;
                synchronized (listeners) {
                    for (IAppForeground listener : listeners) {
                        listener.onForeground(true);
                    }
                }
            }
        });

    }

    private void onDispatchBackground(String visibleScene) {
        if (!isAppForeground || !isInit) {
            return;
        }

        MatrixLog.i(TAG, "onBackground... visibleScene[%s]", visibleScene);

        handler.post(new Runnable() {
            @Override
            public void run() {
                isAppForeground = false;
                synchronized (listeners) {
                    for (IAppForeground listener : listeners) {
                        listener.onForeground(false);
                    }
                }
            }
        });


    }

    public boolean isAppForeground() {
        return isAppForeground;
    }

    public void addListener(IAppForeground listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(IAppForeground listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }


    private final class Controller implements Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

        @Override
        public void onActivityStarted(Activity activity) {
            //更新当前页面名称
            updateScene(activity);
            //广播 APP切换到前台的 事件
            onDispatchForeground(getVisibleScene());
        }


        @Override
        public void onActivityStopped(Activity activity) {
            if (getTopActivityName() == null) {
                //广播 APP切换到后台的 事件
                onDispatchBackground(getVisibleScene());
            }
        }


        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {

        }

        @Override
        public void onActivityDestroyed(Activity activity) {

        }

        @Override
        public void onActivityResumed(Activity activity) {

        }

        @Override
        public void onActivityPaused(Activity activity) {

        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

        }

        @Override
        public void onConfigurationChanged(Configuration newConfig) {

        }

        @Override
        public void onLowMemory() {

        }

        @Override
        public void onTrimMemory(int level) {
            MatrixLog.i(TAG, "[onTrimMemory] level:%s", level);
            if (level == TRIM_MEMORY_UI_HIDDEN && isAppForeground) { // fallback
                //广播 APP切换到后台的 事件
                onDispatchBackground(visibleScene);
            }
        }
    }

    private void updateScene(Activity activity) {
        visibleScene = activity.getClass().getName();
    }

    private void updateScene(String currentFragmentName) {
        StringBuilder ss = new StringBuilder();
        ss.append(TextUtils.isEmpty(currentFragmentName) ? "?" : currentFragmentName);
        visibleScene = ss.toString();
    }

    //通过反射获得 topActivity
    public static String getTopActivityName() {
        long start = System.currentTimeMillis();
        try {
            Class activityThreadClass = Class.forName("android.app.ActivityThread");
            Object activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null);
            Field activitiesField = activityThreadClass.getDeclaredField("mActivities");
            activitiesField.setAccessible(true);

            Map<Object, Object> activities;
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
                activities = (HashMap<Object, Object>) activitiesField.get(activityThread);
            } else {
                activities = (ArrayMap<Object, Object>) activitiesField.get(activityThread);
            }
            if (activities.size() < 1) {
                return null;
            }
            for (Object activityRecord : activities.values()) {
                Class activityRecordClass = activityRecord.getClass();
                Field pausedField = activityRecordClass.getDeclaredField("paused");
                pausedField.setAccessible(true);
                if (!pausedField.getBoolean(activityRecord)) {
                    Field activityField = activityRecordClass.getDeclaredField("activity");
                    activityField.setAccessible(true);
                    Activity activity = (Activity) activityField.get(activityRecord);
                    return activity.getClass().getName();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            long cost = System.currentTimeMillis() - start;
            MatrixLog.d(TAG, "[getTopActivityName] Cost:%s", cost);
        }
        return null;
    }

}
