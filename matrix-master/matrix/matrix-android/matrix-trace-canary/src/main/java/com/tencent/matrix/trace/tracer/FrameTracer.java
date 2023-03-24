package com.tencent.matrix.trace.tracer;

import android.os.Handler;
import android.os.SystemClock;

import com.tencent.matrix.Matrix;
import com.tencent.matrix.report.Issue;
import com.tencent.matrix.trace.TracePlugin;
import com.tencent.matrix.trace.config.SharePluginInfo;
import com.tencent.matrix.trace.config.TraceConfig;
import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.core.UIThreadMonitor;
import com.tencent.matrix.trace.listeners.IDoFrameListener;
import com.tencent.matrix.trace.util.Utils;
import com.tencent.matrix.util.DeviceUtil;
import com.tencent.matrix.util.MatrixHandlerThread;
import com.tencent.matrix.util.MatrixLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 用于监测 帧率 FPS
 */
public class FrameTracer extends Tracer {

    private static final String TAG = "Matrix.FrameTracer";
    private final HashSet<IDoFrameListener> listeners = new HashSet<>();
    private final long frameIntervalMs; //每帧间隔时间 一般就是16.7
    private final TraceConfig config; //配置
    private long timeSliceMs;// fps 的上报时间阈值
    private boolean isFPSEnable;//FPS 监控是否开启
    private long frozenThreshold; //一秒钟 掉帧 42帧 为 FROZEN
    private long highThreshold; //一秒钟 掉帧 24帧 为 HIGH
    private long middleThreshold;//一秒钟 掉帧 9帧 为 MIDDLE
    private long normalThreshold;//一秒钟 掉帧 3帧 为 NORMAL

    public FrameTracer(TraceConfig config) {
        this.config = config;
        //每帧间隔时间 一般就是16.7
        this.frameIntervalMs = TimeUnit.MILLISECONDS.convert(UIThreadMonitor.getMonitor().getFrameIntervalNanos(), TimeUnit.NANOSECONDS) + 1;
        //fps 的上报时间阈值
        this.timeSliceMs = config.getTimeSliceMs();
        //FPS 监控是否开启
        this.isFPSEnable = config.isFPSEnable();
        //一秒钟 掉帧 42帧 为 FROZEN
        this.frozenThreshold = config.getFrozenThreshold();
        //一秒钟 掉帧 24帧 为 HIGH
        this.highThreshold = config.getHighThreshold();
        //一秒钟 掉帧 3帧 为 NORMAL
        this.normalThreshold = config.getNormalThreshold();
        //一秒钟 掉帧 9帧 为 MIDDLE
        this.middleThreshold = config.getMiddleThreshold();

        MatrixLog.i(TAG, "[init] frameIntervalMs:%s isFPSEnable:%s", frameIntervalMs, isFPSEnable);
        if (isFPSEnable) {
            //添加 FPS 收集器
            addListener(new FPSCollector());
        }
    }

    public void addListener(IDoFrameListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(IDoFrameListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    @Override
    public void onAlive() {
        super.onAlive();
        //添加 Observer 到 UIThreadMonitor 会在 dispatchBegin 和 dispatchEnd  中回调
        UIThreadMonitor.getMonitor().addObserver(this);
    }

    @Override
    public void onDead() {
        super.onDead();
        UIThreadMonitor.getMonitor().removeObserver(this);
    }


    @Override
    public void doFrame(String focusedActivityName, long start, long end, long frameCostMs, long inputCostNs, long animationCostNs, long traversalCostNs) {
        //处于前台
        if (isForeground()) {
            notifyListener(focusedActivityName, end - start, frameCostMs, frameCostMs >= 0);
        }
    }

    /**
     * @param visibleScene    当前Activity名
     * @param taskCostMs      这一帧在主线程looper中执行的时间 其实也就是这一帧花费的时间
     * @param frameCostMs     该帧耗时 在Looper中的小时是帧刷新是  taskCostMs 其实是 等于 frameCostMs 的
     * @param isContainsFrame 是否是帧刷新
     */
    private void notifyListener(final String visibleScene, final long taskCostMs, final long frameCostMs, final boolean isContainsFrame) {
        long start = System.currentTimeMillis();
        try {
            synchronized (listeners) {
                for (final IDoFrameListener listener : listeners) {
                    if (config.isDevEnv()) {
                        listener.time = SystemClock.uptimeMillis();
                    }
                    //当前事件 消耗的帧数 其实也就是掉帧数
                    final int dropFrame = (int) (taskCostMs / frameIntervalMs);
                    //同步 回调 doFrameSync 方法
                    listener.doFrameSync(visibleScene, taskCostMs, frameCostMs, dropFrame, isContainsFrame);
                    if (null != listener.getExecutor()) {
                        listener.getExecutor().execute(new Runnable() {
                            @Override
                            public void run() {
                                //异步回调 doFrameAsync 方法
                                listener.doFrameAsync(visibleScene, taskCostMs, frameCostMs, dropFrame, isContainsFrame);
                            }
                        });
                    }
                    if (config.isDevEnv()) {
                        listener.time = SystemClock.uptimeMillis() - listener.time;
                        MatrixLog.d(TAG, "[notifyListener] cost:%sms listener:%s", listener.time, listener);
                    }
                }
            }
        } finally {
            long cost = System.currentTimeMillis() - start;
            if (config.isDebug() && cost > frameIntervalMs) {
                MatrixLog.w(TAG, "[notifyListener] warm! maybe do heavy work in doFrameSync! size:%s cost:%sms", listeners.size(), cost);
            }
        }
    }


    /**
     * FPS 的收集器
     */
    private class FPSCollector extends IDoFrameListener {


        private Handler frameHandler = new Handler(MatrixHandlerThread.getDefaultHandlerThread().getLooper());

        Executor executor = new Executor() {
            @Override
            public void execute(Runnable command) {
                frameHandler.post(command);
            }
        };

        private HashMap<String, FrameCollectItem> map = new HashMap<>();

        @Override
        public Executor getExecutor() {
            // 设置 executor 说明 FPSCollector 是使用异步处理 FPS的
            return executor;
        }

        /**
         *
         * @param visibleScene 当前Activity名
         * @param taskCost 整个任务耗时
         * @param frameCostMs 该帧耗时
         * @param droppedFrames 消耗帧数 其实也就是掉帧数
         * @param isContainsFrame 是否属于帧刷新
         */
        @Override
        public void doFrameAsync(String visibleScene, long taskCost, long frameCostMs, int droppedFrames, boolean isContainsFrame) {
            super.doFrameAsync(visibleScene, taskCost, frameCostMs, droppedFrames, isContainsFrame);
            if (Utils.isEmpty(visibleScene)) {
                return;
            }

            FrameCollectItem item = map.get(visibleScene);
            if (null == item) {
                item = new FrameCollectItem(visibleScene);
                map.put(visibleScene, item);
            }

            item.collect(droppedFrames, isContainsFrame);

            //每个visibleScene（页面）监控的 总时间超过 预设阀值 就 进行报告，并重置
            if (item.sumFrameCost >= timeSliceMs) {
                map.remove(visibleScene);
                item.report();
            }
        }
    }

    private class FrameCollectItem {
        String visibleScene; //当前activity
        long sumFrameCost; //总消耗 总时间
        int sumFrame = 0;//doFrameAsync 回调次数
        int sumTaskFrame = 0;////除过 刷新帧 事件外，其他 事件 doFrameAsync 回调次数
        int sumDroppedFrames;// 总下降帧数
        // record the level of frames dropped each time
        int[] dropLevel = new int[DropStatus.values().length];
        //记录每种类型的 下降次数
        int[] dropSum = new int[DropStatus.values().length];

        FrameCollectItem(String visibleScene) {
            this.visibleScene = visibleScene;
        }

        /**
         * @param droppedFrames   下降帧数
         * @param isContainsFrame
         */
        void collect(int droppedFrames, boolean isContainsFrame) {
            long frameIntervalCost = UIThreadMonitor.getMonitor().getFrameIntervalNanos();
            //积累的 总时间 ms值 ,这里不够一帧当一帧计算
            sumFrameCost += (droppedFrames + 1) * frameIntervalCost / Constants.TIME_MILLIS_TO_NANO;
            //下降的总帧数
            sumDroppedFrames += droppedFrames;
            //doFrameAsync 回调次数
            sumFrame++;
            if (!isContainsFrame) {
                //除过 刷新帧 事件外，其他 事件数
                sumTaskFrame++;
            }

            if (droppedFrames >= frozenThreshold) {//frozen
                dropLevel[DropStatus.DROPPED_FROZEN.index]++;// 冻结数+1
                dropSum[DropStatus.DROPPED_FROZEN.index] += droppedFrames;
            } else if (droppedFrames >= highThreshold) {
                dropLevel[DropStatus.DROPPED_HIGH.index]++;
                dropSum[DropStatus.DROPPED_HIGH.index] += droppedFrames;
            } else if (droppedFrames >= middleThreshold) {
                dropLevel[DropStatus.DROPPED_MIDDLE.index]++;
                dropSum[DropStatus.DROPPED_MIDDLE.index] += droppedFrames;
            } else if (droppedFrames >= normalThreshold) {
                dropLevel[DropStatus.DROPPED_NORMAL.index]++;
                dropSum[DropStatus.DROPPED_NORMAL.index] += droppedFrames;
            } else {
                dropLevel[DropStatus.DROPPED_BEST.index]++;
                dropSum[DropStatus.DROPPED_BEST.index] += (droppedFrames < 0 ? 0 : droppedFrames);
            }
        }

        //组建json 并上报
        void report() {
            //计算 fps 一秒内的平均帧率
            float fps = Math.min(60.f, 1000.f * sumFrame / sumFrameCost);
            MatrixLog.i(TAG, "[report] FPS:%s %s", fps, toString());

            try {
                TracePlugin plugin = Matrix.with().getPluginByClass(TracePlugin.class);
                if (null == plugin) {
                    return;
                }
                //记录卡顿级别，及其出现的次数
                JSONObject dropLevelObject = new JSONObject();
                dropLevelObject.put(DropStatus.DROPPED_FROZEN.name(), dropLevel[DropStatus.DROPPED_FROZEN.index]);
                dropLevelObject.put(DropStatus.DROPPED_HIGH.name(), dropLevel[DropStatus.DROPPED_HIGH.index]);
                dropLevelObject.put(DropStatus.DROPPED_MIDDLE.name(), dropLevel[DropStatus.DROPPED_MIDDLE.index]);
                dropLevelObject.put(DropStatus.DROPPED_NORMAL.name(), dropLevel[DropStatus.DROPPED_NORMAL.index]);
                dropLevelObject.put(DropStatus.DROPPED_BEST.name(), dropLevel[DropStatus.DROPPED_BEST.index]);

                //记录卡顿级别，及掉帧总次数
                JSONObject dropSumObject = new JSONObject();
                dropSumObject.put(DropStatus.DROPPED_FROZEN.name(), dropSum[DropStatus.DROPPED_FROZEN.index]);
                dropSumObject.put(DropStatus.DROPPED_HIGH.name(), dropSum[DropStatus.DROPPED_HIGH.index]);
                dropSumObject.put(DropStatus.DROPPED_MIDDLE.name(), dropSum[DropStatus.DROPPED_MIDDLE.index]);
                dropSumObject.put(DropStatus.DROPPED_NORMAL.name(), dropSum[DropStatus.DROPPED_NORMAL.index]);
                dropSumObject.put(DropStatus.DROPPED_BEST.name(), dropSum[DropStatus.DROPPED_BEST.index]);

                JSONObject resultObject = new JSONObject();
                resultObject = DeviceUtil.getDeviceInfo(resultObject, plugin.getApplication());

                resultObject.put(SharePluginInfo.ISSUE_SCENE, visibleScene);
                resultObject.put(SharePluginInfo.ISSUE_DROP_LEVEL, dropLevelObject);
                resultObject.put(SharePluginInfo.ISSUE_DROP_SUM, dropSumObject);
                resultObject.put(SharePluginInfo.ISSUE_FPS, fps);
                resultObject.put(SharePluginInfo.ISSUE_SUM_TASK_FRAME, sumTaskFrame);

                Issue issue = new Issue();
                issue.setTag(SharePluginInfo.TAG_PLUGIN_FPS);
                issue.setContent(resultObject);
                plugin.onDetectIssue(issue);

            } catch (JSONException e) {
                MatrixLog.e(TAG, "json error", e);
            } finally {
                sumFrame = 0;
                sumDroppedFrames = 0;
                sumFrameCost = 0;
                sumTaskFrame = 0;
            }
        }


        @Override
        public String toString() {
            return "visibleScene=" + visibleScene
                    + ", sumFrame=" + sumFrame
                    + ", sumDroppedFrames=" + sumDroppedFrames
                    + ", sumFrameCost=" + sumFrameCost
                    + ", dropLevel=" + Arrays.toString(dropLevel);
        }
    }

    public enum DropStatus {
        DROPPED_FROZEN(4),
        DROPPED_HIGH(3),
        DROPPED_MIDDLE(2),
        DROPPED_NORMAL(1),
        DROPPED_BEST(0);

        public int index;

        DropStatus(int index) {
            this.index = index;
        }

    }
}
