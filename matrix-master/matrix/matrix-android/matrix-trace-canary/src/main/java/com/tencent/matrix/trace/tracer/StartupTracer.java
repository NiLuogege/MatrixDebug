package com.tencent.matrix.trace.tracer;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.tencent.matrix.Matrix;
import com.tencent.matrix.report.Issue;
import com.tencent.matrix.trace.TracePlugin;
import com.tencent.matrix.trace.config.SharePluginInfo;
import com.tencent.matrix.trace.config.TraceConfig;
import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.core.AppMethodBeat;
import com.tencent.matrix.trace.hacker.ActivityThreadHacker;
import com.tencent.matrix.trace.items.MethodItem;
import com.tencent.matrix.trace.listeners.IAppMethodBeatListener;
import com.tencent.matrix.trace.util.TraceDataUtils;
import com.tencent.matrix.util.DeviceUtil;
import com.tencent.matrix.util.MatrixHandlerThread;
import com.tencent.matrix.util.MatrixLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static android.os.SystemClock.uptimeMillis;

/**
 * Created by caichongyang on 2019/3/04.
 * <p>
 * firstMethod.i       LAUNCH_ACTIVITY   onWindowFocusChange   LAUNCH_ACTIVITY    onWindowFocusChange
 * ^                         ^                   ^                     ^                  ^
 * |                         |                   |                     |                  |
 * |---------app---------|---|---firstActivity---|---------...---------|---careActivity---|
 * |<--applicationCost-->|
 * |<--------------firstScreenCost-------------->|
 * |<---------------------------------------coldCost------------------------------------->|
 * .                         |<-----warmCost---->|
 *
 * </p>
 */

/**
 * 监测 APP启动 及 Activity启动
 */
public class StartupTracer extends Tracer implements IAppMethodBeatListener, Application.ActivityLifecycleCallbacks {

    private static final String TAG = "Matrix.StartupTracer";
    private final TraceConfig config;
    private long firstScreenCost = 0;
    private long coldCost = 0;
    private int activeActivityCount;//存活activity的数量
    private boolean isWarmStartUp;//是否是暖启动
    private boolean hasShowSplashActivity;
    private boolean isStartupEnable;
    private Set<String> splashActivities;
    private long coldStartupThresholdMs;//默认冷启动阈值
    private long warmStartupThresholdMs;//默认暖启动阈值


    public StartupTracer(TraceConfig config) {
        this.config = config;
        //是否可用
        this.isStartupEnable = config.isStartupEnable();
        //SplashActivities
        this.splashActivities = config.getSplashActivities();
        this.coldStartupThresholdMs = config.getColdStartupThresholdMs();
        this.warmStartupThresholdMs = config.getWarmStartupThresholdMs();
    }

    @Override
    protected void onAlive() {
        super.onAlive();
        MatrixLog.i(TAG, "[onAlive] isStartupEnable:%s", isStartupEnable);
        if (isStartupEnable) {
            //添加监听 可以感知 activity获得焦点 和 activity的生命周期
            AppMethodBeat.getInstance().addListener(this);
            Matrix.with().getApplication().registerActivityLifecycleCallbacks(this);
        }
    }

    @Override
    protected void onDead() {
        super.onDead();
        if (isStartupEnable) {
            //移除监听
            AppMethodBeat.getInstance().removeListener(this);
            Matrix.with().getApplication().unregisterActivityLifecycleCallbacks(this);
        }
    }

    //当有activity可以获取焦点（可操作） 时调用
    @Override
    public void onActivityFocused(String activity) {
        if (isColdStartup()) {
            if (firstScreenCost == 0) {
                //首屏启动时间=当前时间点-APP启动时间点
                this.firstScreenCost = uptimeMillis() - ActivityThreadHacker.getEggBrokenTime();
            }
            if (hasShowSplashActivity) {
                //冷启动耗时 = （MainActivity启动的时间）当前时间-蛋碎时间
                //类注释上画了，coldCost = 第二个activity onWindowFocusChange时的时间，
                coldCost = uptimeMillis() - ActivityThreadHacker.getEggBrokenTime();
            } else {
                if (splashActivities.contains(activity)) {
                    hasShowSplashActivity = true;
                } else if (splashActivities.isEmpty()) {//未配置 splashActivities，冷启动时间 == 第一屏时间
                    MatrixLog.i(TAG, "default splash activity[%s]", activity);
                    coldCost = firstScreenCost;
                } else {
                    MatrixLog.w(TAG, "pass this activity[%s] at duration of start up! splashActivities=%s", activity, splashActivities);
                }
            }
            if (coldCost > 0) {
                analyse(ActivityThreadHacker.getApplicationCost(), firstScreenCost, coldCost, false);
            }

        } else if (isWarmStartUp()) {
            isWarmStartUp = false;
            //暖启动时间=当前时间- 最近一个activity被启动的时间
            long warmCost = uptimeMillis() - ActivityThreadHacker.getLastLaunchActivityTime();
            if (warmCost > 0) {
                analyse(ActivityThreadHacker.getApplicationCost(), firstScreenCost, warmCost, true);
            }
        }

    }

    private boolean isColdStartup() {
        return coldCost == 0;
    }

    private boolean isWarmStartUp() {
        return isWarmStartUp;
    }

    /**
     * @param applicationCost: application启动用时
     * @param firstScreenCost: 首屏启动时间
     * @param allCost          ：冷启动耗时 或者 暖启动耗时
     * @param isWarmStartUp    ：是冷启动还是暖启动
     */
    private void analyse(long applicationCost, long firstScreenCost, long allCost, boolean isWarmStartUp) {
        MatrixLog.i(TAG, "[report] applicationCost:%s firstScreenCost:%s allCost:%s isWarmStartUp:%s", applicationCost, firstScreenCost, allCost, isWarmStartUp);
        long[] data = new long[0];
        if (!isWarmStartUp && allCost >= coldStartupThresholdMs) { //冷启动时间>阈值
            data = AppMethodBeat.getInstance().copyData(ActivityThreadHacker.sApplicationCreateBeginMethodIndex);
            ActivityThreadHacker.sApplicationCreateBeginMethodIndex.release();

        } else if (isWarmStartUp && allCost >= warmStartupThresholdMs) {//暖启动时间>阈值
            data = AppMethodBeat.getInstance().copyData(ActivityThreadHacker.sLastLaunchActivityMethodIndex);
            ActivityThreadHacker.sLastLaunchActivityMethodIndex.release();
        }

        MatrixHandlerThread.getDefaultHandler().post(new AnalyseTask(data, applicationCost, firstScreenCost, allCost, isWarmStartUp, ActivityThreadHacker.sApplicationCreateScene));

    }

    private class AnalyseTask implements Runnable {

        long[] data;
        long applicationCost;
        long firstScreenCost;
        long allCost;
        boolean isWarmStartUp;
        int scene;

        /**
         * @param data: ActivityThreadHacker.sApplicationCreateBeginMethodIndex 或者
         *            ActivityThreadHacker.sLastLaunchActivityMethodIndex 的 AppMethodBeat.IndexRecord 对象
         * @param applicationCost: application启动用时
         * @param firstScreenCost: 首屏启动时间
         * @param allCost          ：冷启动耗时 或者 暖启动耗时
         * @param isWarmStartUp    ：是冷启动还是暖启动
         * @param scene:app        启动时的场景（可分为 activity ，service ，brodcast ）
         */
        AnalyseTask(long[] data, long applicationCost, long firstScreenCost, long allCost, boolean isWarmStartUp, int scene) {
            this.data = data;
            this.scene = scene;
            this.applicationCost = applicationCost;
            this.firstScreenCost = firstScreenCost;
            this.allCost = allCost;
            this.isWarmStartUp = isWarmStartUp;
        }

        @Override
        public void run() {
            LinkedList<MethodItem> stack = new LinkedList();
            if (data.length > 0) {
                //根据之前 data 查到的 methodId ，拿到对应插桩函数的执行时间、执行深度，将每个函数的信息封装成 MethodItem，然后存储到 stack 集合当中
                TraceDataUtils.structuredDataToStack(data, stack, false, -1);
                //根据 stack 集合数据，分析出是哪个方法是慢函数，并回调给 fallback 打印出来
                TraceDataUtils.trimStack(stack, Constants.TARGET_EVIL_METHOD_STACK, new TraceDataUtils.IStructuredDataFilter() {
                    @Override
                    public boolean isFilter(long during, int filterCount) {
                        return during < filterCount * Constants.TIME_UPDATE_CYCLE_MS;
                    }

                    @Override
                    public int getFilterMaxCount() {
                        return Constants.FILTER_STACK_MAX_COUNT;
                    }

                    @Override
                    public void fallback(List<MethodItem> stack, int size) {
                        MatrixLog.w(TAG, "[fallback] size:%s targetSize:%s stack:%s", size, Constants.TARGET_EVIL_METHOD_STACK, stack);
                        Iterator iterator = stack.listIterator(Math.min(size, Constants.TARGET_EVIL_METHOD_STACK));
                        while (iterator.hasNext()) {
                            iterator.next();
                            iterator.remove();
                        }

                    }
                });
            }

            StringBuilder reportBuilder = new StringBuilder();
            StringBuilder logcatBuilder = new StringBuilder();
            long stackCost = Math.max(allCost, TraceDataUtils.stackToString(stack, reportBuilder, logcatBuilder));
            String stackKey = TraceDataUtils.getTreeKey(stack, stackCost);

            // for logcat
            if ((allCost > coldStartupThresholdMs && !isWarmStartUp)
                    || (allCost > warmStartupThresholdMs && isWarmStartUp)) {
                MatrixLog.w(TAG, "stackKey:%s \n%s", stackKey, logcatBuilder.toString());
            }

            // report
            report(applicationCost, firstScreenCost, reportBuilder, stackKey, stackCost, isWarmStartUp, scene);
        }

        private void report(long applicationCost, long firstScreenCost, StringBuilder reportBuilder, String stackKey,
                            long allCost, boolean isWarmStartUp, int scene) {

            TracePlugin plugin = Matrix.with().getPluginByClass(TracePlugin.class);
            if (null == plugin) {
                return;
            }
            try {
                JSONObject costObject = new JSONObject();
                costObject = DeviceUtil.getDeviceInfo(costObject, Matrix.with().getApplication());
                costObject.put(SharePluginInfo.STAGE_APPLICATION_CREATE, applicationCost);
                costObject.put(SharePluginInfo.STAGE_APPLICATION_CREATE_SCENE, scene);
                costObject.put(SharePluginInfo.STAGE_FIRST_ACTIVITY_CREATE, firstScreenCost);
                costObject.put(SharePluginInfo.STAGE_STARTUP_DURATION, allCost);
                costObject.put(SharePluginInfo.ISSUE_IS_WARM_START_UP, isWarmStartUp);
                Issue issue = new Issue();
                issue.setTag(SharePluginInfo.TAG_PLUGIN_STARTUP);
                issue.setContent(costObject);
                plugin.onDetectIssue(issue);
            } catch (JSONException e) {
                MatrixLog.e(TAG, "[JSONException for StartUpReportTask error: %s", e);
            }


            if ((allCost > coldStartupThresholdMs && !isWarmStartUp)
                    || (allCost > warmStartupThresholdMs && isWarmStartUp)) {

                try {
                    JSONObject jsonObject = new JSONObject();
                    jsonObject = DeviceUtil.getDeviceInfo(jsonObject, Matrix.with().getApplication());
                    jsonObject.put(SharePluginInfo.ISSUE_STACK_TYPE, Constants.Type.STARTUP);
                    jsonObject.put(SharePluginInfo.ISSUE_COST, allCost);
                    jsonObject.put(SharePluginInfo.ISSUE_TRACE_STACK, reportBuilder.toString());
                    jsonObject.put(SharePluginInfo.ISSUE_STACK_KEY, stackKey);
                    jsonObject.put(SharePluginInfo.ISSUE_SUB_TYPE, isWarmStartUp ? 2 : 1);
                    Issue issue = new Issue();
                    issue.setTag(SharePluginInfo.TAG_PLUGIN_EVIL_METHOD);
                    issue.setContent(jsonObject);
                    plugin.onDetectIssue(issue);

                } catch (JSONException e) {
                    MatrixLog.e(TAG, "[JSONException error: %s", e);
                }
            }
        }
    }


    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        //activeActivityCount == 0 && coldCost > 0 说明曾经已经冷启动过，这是没有activity了，但是进程还在
        if (activeActivityCount == 0 && coldCost > 0) {
            isWarmStartUp = true;
        }
        activeActivityCount++;
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        activeActivityCount--;
    }

    @Override
    public void onActivityStarted(Activity activity) {

    }

    @Override
    public void onActivityResumed(Activity activity) {

    }

    @Override
    public void onActivityPaused(Activity activity) {

    }

    @Override
    public void onActivityStopped(Activity activity) {

    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {

    }
}
