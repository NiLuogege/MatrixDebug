package com.tencent.matrix.trace.tracer;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;

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
    private long firstScreenCost = 0;//首屏启动时间
    private long coldCost = 0;
    private int activeActivityCount;//存活activity的数量
    private boolean isWarmStartUp;//是否是暖启动
    private boolean hasShowSplashActivity;//是否已经展示了 splashActivity
    private boolean isStartupEnable;
    private Set<String> splashActivities;//虽然可以设置多个，但是只有第一个有效
    private long coldStartupThresholdMs;//默认冷启动阈值
    private long warmStartupThresholdMs;//默认暖启动阈值


    public StartupTracer(TraceConfig config) {
        this.config = config;
        //是否可用
        this.isStartupEnable = config.isStartupEnable();
        //SplashActivities
        this.splashActivities = config.getSplashActivities();
        this.coldStartupThresholdMs = 0;
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
        if (isColdStartup()) {//判断条件是 coldCost == 0 所以只会进来一次
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

            //调试
//            AppMethodBeat.getInstance().printIndexRecord();

            //获取 AppMethodBeat.sBuffer 中记录的数据
            data = AppMethodBeat.getInstance().copyData(ActivityThreadHacker.sApplicationCreateBeginMethodIndex);
            //移除 sApplicationCreateBeginMethodIndex 节点
            ActivityThreadHacker.sApplicationCreateBeginMethodIndex.release();

        } else if (isWarmStartUp && allCost >= warmStartupThresholdMs) {//暖启动时间>阈值
            data = AppMethodBeat.getInstance().copyData(ActivityThreadHacker.sLastLaunchActivityMethodIndex);
            //移除 sApplicationCreateBeginMethodIndex 节点
            ActivityThreadHacker.sLastLaunchActivityMethodIndex.release();
        }



//        Log.d(TAG, "isWarmStartUp:" + isWarmStartUp);
//        //调试
//        AppMethodBeat.getInstance().printIndexRecord();

        //执行 AnalyseTask
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
         * @param data:            ActivityThreadHacker.sApplicationCreateBeginMethodIndex 或者
         *                         ActivityThreadHacker.sLastLaunchActivityMethodIndex 的 AppMethodBeat.IndexRecord 对象
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
                //根据规则 裁剪 stack 中的数据，
                TraceDataUtils.trimStack(stack, Constants.TARGET_EVIL_METHOD_STACK, new TraceDataUtils.IStructuredDataFilter() {
                    @Override
                    public boolean isFilter(long during, int filterCount) {
                        //如果 耗时小于 预设值 则进行裁剪
                        return during < filterCount * Constants.TIME_UPDATE_CYCLE_MS;
                    }

                    @Override
                    public int getFilterMaxCount() {
                        //最大方法裁剪数 60
                        return Constants.FILTER_STACK_MAX_COUNT;
                    }

                    @Override
                    public void fallback(List<MethodItem> stack, int size) {//降级策略
                        MatrixLog.w(TAG, "[fallback] size:%s targetSize:%s stack:%s", size, Constants.TARGET_EVIL_METHOD_STACK, stack);
                        //循环删除 多余的shuju8
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
            //获取最大的启动时间
            long stackCost = Math.max(allCost, TraceDataUtils.stackToString(stack, reportBuilder, logcatBuilder));
            //查询出最耗时的 方法id
            String stackKey = TraceDataUtils.getTreeKey(stack, stackCost);

            // 如果超过阈值 打印log
            if ((allCost > coldStartupThresholdMs && !isWarmStartUp)
                    || (allCost > warmStartupThresholdMs && isWarmStartUp)) {
                MatrixLog.w(TAG, "stackKey:%s \n%s", stackKey, logcatBuilder.toString());
            }

            // report
            report(applicationCost, firstScreenCost, reportBuilder, stackKey, stackCost, isWarmStartUp, scene);
        }

        /**
         * @param applicationCost：Application 启动时间
         * @param firstScreenCost：首屏启动时间
         * @param reportBuilder：需要上报的         method信息
         * @param stackKey                    ：主要耗时方法id
         * @param allCost:                    冷启动耗时 或者 暖启动耗时
         * @param isWarmStartUp：是否是           暖启动
         * @param scene：app                   启动时的场景（可分为 activity ，service ，brodcast ）
         */
        private void report(long applicationCost, long firstScreenCost, StringBuilder reportBuilder, String stackKey,
                            long allCost, boolean isWarmStartUp, int scene) {

            TracePlugin plugin = Matrix.with().getPluginByClass(TracePlugin.class);
            if (null == plugin) {
                return;
            }
            //上报正常启动信息
            try {
                JSONObject costObject = new JSONObject();
                //添加设备信息
                costObject = DeviceUtil.getDeviceInfo(costObject, Matrix.with().getApplication());
                //Application 启动时间
                costObject.put(SharePluginInfo.STAGE_APPLICATION_CREATE, applicationCost);
                //Application 启动场景
                costObject.put(SharePluginInfo.STAGE_APPLICATION_CREATE_SCENE, scene);
                //首屏启动时间
                costObject.put(SharePluginInfo.STAGE_FIRST_ACTIVITY_CREATE, firstScreenCost);
                //冷启动时间 或者 暖启动时间
                costObject.put(SharePluginInfo.STAGE_STARTUP_DURATION, allCost);
                //冷启动 or 暖启动
                costObject.put(SharePluginInfo.ISSUE_IS_WARM_START_UP, isWarmStartUp);
                Issue issue = new Issue();
                issue.setTag(SharePluginInfo.TAG_PLUGIN_STARTUP);
                issue.setContent(costObject);
                //上报
                plugin.onDetectIssue(issue);
            } catch (JSONException e) {
                MatrixLog.e(TAG, "[JSONException for StartUpReportTask error: %s", e);
            }


            //上报 启动速度超过预设阈值的信息
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
