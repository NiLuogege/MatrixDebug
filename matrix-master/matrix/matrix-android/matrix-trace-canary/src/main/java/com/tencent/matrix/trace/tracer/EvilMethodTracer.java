package com.tencent.matrix.trace.tracer;

import android.os.Process;

import com.tencent.matrix.Matrix;
import com.tencent.matrix.report.Issue;
import com.tencent.matrix.trace.TracePlugin;
import com.tencent.matrix.trace.config.SharePluginInfo;
import com.tencent.matrix.trace.config.TraceConfig;
import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.core.AppMethodBeat;
import com.tencent.matrix.trace.core.UIThreadMonitor;
import com.tencent.matrix.trace.items.MethodItem;
import com.tencent.matrix.trace.util.TraceDataUtils;
import com.tencent.matrix.trace.util.Utils;
import com.tencent.matrix.util.DeviceUtil;
import com.tencent.matrix.util.MatrixHandlerThread;
import com.tencent.matrix.util.MatrixLog;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * 监测 耗时函数
 */
public class EvilMethodTracer extends Tracer {

    private static final String TAG = "Matrix.EvilMethodTracer";
    private final TraceConfig config;
    private AppMethodBeat.IndexRecord indexRecord;
    //记录各个Type 执行耗时
    private long[] queueTypeCosts = new long[3];
    private long evilThresholdMs;
    private boolean isEvilMethodTraceEnable;

    public EvilMethodTracer(TraceConfig config) {
        this.config = config;
        this.evilThresholdMs = config.getEvilThresholdMs();
        this.isEvilMethodTraceEnable = config.isEvilMethodTraceEnable();
    }

    @Override
    public void onAlive() {
        super.onAlive();
        if (isEvilMethodTraceEnable) {
            //添加 LooperObserver 监听
            UIThreadMonitor.getMonitor().addObserver(this);
        }

    }

    @Override
    public void onDead() {
        super.onDead();
        if (isEvilMethodTraceEnable) {
            //移除 LooperObserver 监听
            UIThreadMonitor.getMonitor().removeObserver(this);
        }
    }


    @Override
    public void dispatchBegin(long beginMs, long cpuBeginMs, long token) {
        super.dispatchBegin(beginMs, cpuBeginMs, token);
        //记录 "EvilMethodTracer#dispatchBegin" 类型的节点
        indexRecord = AppMethodBeat.getInstance().maskIndex("EvilMethodTracer#dispatchBegin");
    }


    @Override
    public void doFrame(String focusedActivityName, long start, long end, long frameCostMs, long inputCostNs, long animationCostNs, long traversalCostNs) {
        queueTypeCosts[0] = inputCostNs;
        queueTypeCosts[1] = animationCostNs;
        queueTypeCosts[2] = traversalCostNs;
    }


    @Override
    public void dispatchEnd(long beginMs, long cpuBeginMs, long endMs, long cpuEndMs, long token, boolean isBelongFrame) {
        super.dispatchEnd(beginMs, cpuBeginMs, endMs, cpuEndMs, token, isBelongFrame);
        long start = config.isDevEnv() ? System.currentTimeMillis() : 0;
        try {
            long dispatchCost = endMs - beginMs;
            if (dispatchCost >= evilThresholdMs) {//Looper中一个loop的时间，耗时超过阈值
                long[] data = AppMethodBeat.getInstance().copyData(indexRecord);
                long[] queueCosts = new long[3];
                System.arraycopy(queueTypeCosts, 0, queueCosts, 0, 3);
                String scene = AppMethodBeat.getVisibleScene();
                //子线程解析数据
                MatrixHandlerThread.getDefaultHandler().post(new AnalyseTask(isForeground(), scene, data, queueCosts, cpuEndMs - cpuBeginMs, endMs - beginMs, endMs));
            }
        } finally {
            //释放资源
            indexRecord.release();
            if (config.isDevEnv()) {
                //计算主线程的cpu消耗程度
                String usage = Utils.calculateCpuUsage(cpuEndMs - cpuBeginMs, endMs - beginMs);
                MatrixLog.v(TAG, "[dispatchEnd] token:%s cost:%sms cpu:%sms usage:%s innerCost:%s",
                        token, endMs - beginMs, cpuEndMs - cpuBeginMs, usage, System.currentTimeMillis() - start);
            }
        }
    }

    public void modifyEvilThresholdMs(long evilThresholdMs) {
        this.evilThresholdMs = evilThresholdMs;
    }

    private class AnalyseTask implements Runnable {
        long[] queueCost;
        long[] data;
        long cpuCost;
        long cost;
        long endMs;
        String scene;
        boolean isForeground;

        /**
         *
         * @param isForeground App是否处于前台
         * @param scene 当前可见Activity名称
         * @param data 从AppMethodBeat中截取的方法耗时数据
         * @param queueCost 帧刷新时 记录各个Type 执行耗时
         * @param cpuCost  主线程运行的毫秒数
         * @param cost 任务总耗时
         * @param endMs 任务（dispatch）的结束时间
         */
        AnalyseTask(boolean isForeground, String scene, long[] data, long[] queueCost, long cpuCost, long cost, long endMs) {
            this.isForeground = isForeground;
            this.scene = scene;
            this.cost = cost;
            this.cpuCost = cpuCost;
            this.data = data;
            this.queueCost = queueCost;
            this.endMs = endMs;
        }

        void analyse() {

            // process
            int[] processStat = Utils.getProcessPriority(Process.myPid());
            MatrixLog.d(TAG,"进程优先级= %s",processStat.toString());
            String usage = Utils.calculateCpuUsage(cpuCost, cost);
            LinkedList<MethodItem> stack = new LinkedList();
            if (data.length > 0) {
                // 根据之前 data 查到的 methodId ，拿到对应插桩函数的执行时间、执行深度，将每个函数的信息封装成 MethodItem，然后存储到 stack 链表当中
                TraceDataUtils.structuredDataToStack(data, stack, true, endMs);
                //根据规则 裁剪 stack 中的数据，
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
            //获取最大的启动时间
            long stackCost = Math.max(cost, TraceDataUtils.stackToString(stack, reportBuilder, logcatBuilder));
            //查询出最耗时的 方法id
            String stackKey = TraceDataUtils.getTreeKey(stack, stackCost);

            MatrixLog.w(TAG, "%s", printEvil(scene, processStat, isForeground, logcatBuilder, stack.size(), stackKey, usage, queueCost[0], queueCost[1], queueCost[2], cost)); // for logcat

            // report
            try {
                TracePlugin plugin = Matrix.with().getPluginByClass(TracePlugin.class);
                if (null == plugin) {
                    return;
                }
                JSONObject jsonObject = new JSONObject();
                jsonObject = DeviceUtil.getDeviceInfo(jsonObject, Matrix.with().getApplication());

                jsonObject.put(SharePluginInfo.ISSUE_STACK_TYPE, Constants.Type.NORMAL);
                jsonObject.put(SharePluginInfo.ISSUE_COST, stackCost);
                jsonObject.put(SharePluginInfo.ISSUE_CPU_USAGE, usage);
                jsonObject.put(SharePluginInfo.ISSUE_SCENE, scene);
                jsonObject.put(SharePluginInfo.ISSUE_TRACE_STACK, reportBuilder.toString());
                jsonObject.put(SharePluginInfo.ISSUE_STACK_KEY, stackKey);

                Issue issue = new Issue();
                issue.setTag(SharePluginInfo.TAG_PLUGIN_EVIL_METHOD);
                issue.setContent(jsonObject);
                plugin.onDetectIssue(issue);

            } catch (JSONException e) {
                MatrixLog.e(TAG, "[JSONException error: %s", e);
            }

        }

        @Override
        public void run() {
            analyse();
        }

        private String printEvil(String scene, int[] processStat, boolean isForeground, StringBuilder stack, long stackSize, String stackKey, String usage, long inputCost,
                                 long animationCost, long traversalCost, long allCost) {
            StringBuilder print = new StringBuilder();
            print.append(String.format("-\n>>>>>>>>>>>>>>>>>>>>> maybe happens Jankiness!(%sms) <<<<<<<<<<<<<<<<<<<<<\n", allCost));
            print.append("|* scene: ").append(scene).append("\n");
            print.append("|* [ProcessStat]").append("\n");
            print.append("|*\t\tPriority: ").append(processStat[0]).append("\n");
            print.append("|*\t\tNice: ").append(processStat[1]).append("\n");
            print.append("|*\t\tForeground: ").append(isForeground).append("\n");
            print.append("|* [CPU]").append("\n");
            print.append("|*\t\tusage: ").append(usage).append("\n");
            print.append("|* [doFrame]").append("\n");
            print.append("|*\t\tinputCost: ").append(inputCost).append("\n");
            print.append("|*\t\tanimationCost: ").append(animationCost).append("\n");
            print.append("|*\t\ttraversalCost: ").append(traversalCost).append("\n");
            print.append("|* [Trace]").append("\n");
            print.append("|*\t\tStackSize: ").append(stackSize).append("\n");
            print.append("|*\t\tStackKey: ").append(stackKey).append("\n");

            if (config.isDebug()) {
                print.append(stack.toString());
            }

            print.append("=========================================================================");
            return print.toString();
        }
    }

}
