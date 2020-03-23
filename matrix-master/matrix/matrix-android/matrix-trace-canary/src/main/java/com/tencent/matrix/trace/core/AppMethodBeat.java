package com.tencent.matrix.trace.core;

import android.app.Activity;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.tencent.matrix.AppActiveMatrixDelegate;
import com.tencent.matrix.trace.constants.Constants;
import com.tencent.matrix.trace.hacker.ActivityThreadHacker;
import com.tencent.matrix.trace.listeners.IAppMethodBeatListener;
import com.tencent.matrix.trace.util.Utils;
import com.tencent.matrix.util.MatrixHandlerThread;
import com.tencent.matrix.util.MatrixLog;

import java.util.HashSet;
import java.util.Set;

/**
 * 方法监控，查看某个方法 是否 执行时间过长
 */
public class AppMethodBeat implements BeatLifecycle {

    public interface MethodEnterListener {
        void enter(int method, long threadId);
    }

    private static final String TAG = "Matrix.AppMethodBeat";
    public static boolean isDev = false;
    private static AppMethodBeat sInstance = new AppMethodBeat();
    private static final int STATUS_DEFAULT = Integer.MAX_VALUE;
    private static final int STATUS_STARTED = 2; //启动
    private static final int STATUS_READY = 1; // 准备好
    private static final int STATUS_STOPPED = -1; //停止
    private static final int STATUS_EXPIRED_START = -2;// 已过期
    private static final int STATUS_OUT_RELEASE = -3;//已释放

    private static volatile int status = STATUS_DEFAULT;
    private final static Object statusLock = new Object();
    public static MethodEnterListener sMethodEnterListener;
    //long占用8byte 所以 sBuffer 占用内存大小为 8* BUFFER_SIZE(100 * 10000) =7.6
    private static long[] sBuffer = new long[Constants.BUFFER_SIZE];
    private static int sIndex = 0;
    private static int sLastIndex = -1;//记录 最后一个 sBuffer 下标的位置
    private static boolean assertIn = false;
    private volatile static long sCurrentDiffTime = SystemClock.uptimeMillis();
    private volatile static long sDiffTime = sCurrentDiffTime;
    private static long sMainThreadId = Looper.getMainLooper().getThread().getId();
    private static HandlerThread sTimerUpdateThread = MatrixHandlerThread.getNewHandlerThread("matrix_time_update_thread");
    //子线程 handler
    private static Handler sHandler = new Handler(sTimerUpdateThread.getLooper());
    private static final int METHOD_ID_MAX = 0xFFFFF;
    public static final int METHOD_ID_DISPATCH = METHOD_ID_MAX - 1;
    //存放着所有 获取焦点 的 activity的名称
    private static Set<String> sFocusActivitySet = new HashSet<>();
    private static final HashSet<IAppMethodBeatListener> listeners = new HashSet<>();
    private static final Object updateTimeLock = new Object();
    private static boolean isPauseUpdateTime = false;
    private static Runnable checkStartExpiredRunnable = null;
    //可以监控到 massage的执行
    private static LooperMonitor.LooperDispatchListener looperMonitorListener = new LooperMonitor.LooperDispatchListener() {
        @Override
        public boolean isValid() {
            return status >= STATUS_READY;
        }

        @Override
        public void dispatchStart() {
            super.dispatchStart();
            AppMethodBeat.dispatchBegin();
        }

        @Override
        public void dispatchEnd() {
            super.dispatchEnd();
            AppMethodBeat.dispatchEnd();
        }
    };

    static {
        sHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                realRelease();
            }
        }, Constants.DEFAULT_RELEASE_BUFFER_DELAY);
    }

    /**
     * update time runnable
     * <p>
     * https://github.com/Tencent/matrix/wiki/Matrix-Android-TraceCanary 中有介绍
     * 考虑到每个方法执行前后都获取系统时间（System.nanoTime）会对性能影响比较大，而实际上，单个函数执行耗时小于 5ms 的情况，
     * 对卡顿来说不是主要原因，可以忽略不计，如果是多次调用的情况，则在它的父级方法中可以反映出来，所以为了减少对性能的影响，
     * 通过另一条更新时间的线程每 5ms 去更新一个时间变量，而每个方法执行前后只读取该变量来减少性能损耗。
     */
    private static Runnable sUpdateDiffTimeRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                //无限循环  当isPauseUpdateTime=false（dispatchBegin方法完成）,然后更新 sCurrentDiffTime
                while (true) {
                    while (!isPauseUpdateTime && status > STATUS_STOPPED) {
                        sCurrentDiffTime = SystemClock.uptimeMillis() - sDiffTime;
                        SystemClock.sleep(Constants.TIME_UPDATE_CYCLE_MS);
                    }
                    synchronized (updateTimeLock) {
                        updateTimeLock.wait();
                    }
                }
            } catch (InterruptedException e) {
                MatrixLog.e(TAG, "" + e.toString());
            }
        }
    };

    public static AppMethodBeat getInstance() {
        return sInstance;
    }

    //会在 TracePlugin.start 中调用
    @Override
    public void onStart() {
        synchronized (statusLock) {
            //如果没有启动 或者已经过期 则进行启动
            if (status < STATUS_STARTED && status >= STATUS_EXPIRED_START) {
                sHandler.removeCallbacks(checkStartExpiredRunnable);
                if (sBuffer == null) {
                    throw new RuntimeException(TAG + " sBuffer == null");
                }
                MatrixLog.i(TAG, "[onStart] preStatus:%s", status, Utils.getStack());
                //标示已将 启动
                status = STATUS_STARTED;
            } else {
                MatrixLog.w(TAG, "[onStart] current status:%s", status);
            }
        }
    }

    //会在 TracePlugin.stop 中调用
    @Override
    public void onStop() {
        synchronized (statusLock) {
            //进行关闭
            if (status == STATUS_STARTED) {
                MatrixLog.i(TAG, "[onStop] %s", Utils.getStack());
                status = STATUS_STOPPED;
            } else {
                MatrixLog.w(TAG, "[onStop] current status:%s", status);
            }
        }
    }

    @Override
    public boolean isAlive() {
        return status >= STATUS_STARTED;
    }


    public static boolean isRealTrace() {
        return status >= STATUS_READY;
    }

    //释放操作
    private static void realRelease() {
        synchronized (statusLock) {
            if (status == STATUS_DEFAULT) {
                MatrixLog.i(TAG, "[realRelease] timestamp:%s", System.currentTimeMillis());
                sHandler.removeCallbacksAndMessages(null);
                //移除 looperMonitorListener 监听
                LooperMonitor.unregister(looperMonitorListener);
                //sTimerUpdateThread 退出
                sTimerUpdateThread.quit();
                sBuffer = null;
                //状态改为 STATUS_OUT_RELEASE
                status = STATUS_OUT_RELEASE;
            }
        }
    }

    //这个方法只有 在第一次执行 i方法时才会被调用
    private static void realExecute() {
        MatrixLog.i(TAG, "[realExecute] timestamp:%s", System.currentTimeMillis());

        //当前时间减去上一个 记录的时间
        sCurrentDiffTime = SystemClock.uptimeMillis() - sDiffTime;

        //清空 sHandler 所有消息
        sHandler.removeCallbacksAndMessages(null);
        //延迟5 ms 后执行 sUpdateDiffTimeRunnable
        sHandler.postDelayed(sUpdateDiffTimeRunnable, Constants.TIME_UPDATE_CYCLE_MS);
        //延迟15 ms 后执行 checkStartExpiredRunnable
        sHandler.postDelayed(checkStartExpiredRunnable = new Runnable() {
            @Override
            public void run() {
                synchronized (statusLock) {
                    MatrixLog.i(TAG, "[startExpired] timestamp:%s status:%s", System.currentTimeMillis(), status);
                    if (status == STATUS_DEFAULT || status == STATUS_READY) {
                        status = STATUS_EXPIRED_START;
                    }
                }
            }
        }, Constants.DEFAULT_RELEASE_BUFFER_DELAY);

        //hook 主线程的 HandlerCallback
        ActivityThreadHacker.hackSysHandlerCallback();
        //注册 looperMonitorListener 使可以接收到looper分发massage事件
        LooperMonitor.register(looperMonitorListener);
    }

    private static void dispatchBegin() {
        sCurrentDiffTime = SystemClock.uptimeMillis() - sDiffTime;
        isPauseUpdateTime = false;

        synchronized (updateTimeLock) {
            updateTimeLock.notify();
        }
    }

    private static void dispatchEnd() {
        isPauseUpdateTime = true;
    }

    /**
     * hook method when it's called in.
     *
     * @param methodId
     */
    public static void i(int methodId) {

        //对 AppMethodBeat 状态进行检查
        if (status <= STATUS_STOPPED) {
            return;
        }
        //对 methodId进行校验
        if (methodId >= METHOD_ID_MAX) {
            return;
        }

        //第一次执行该方法时 调用 realExecute 方法，并将状态切换为 STATUS_READY
        if (status == STATUS_DEFAULT) {
            synchronized (statusLock) {
                if (status == STATUS_DEFAULT) {
                    //当 当前类 没有被启动时 执行该方法
                    realExecute();
                    //切换状态 为 STATUS_READY
                    status = STATUS_READY;
                }
            }
        }

        long threadId = Thread.currentThread().getId();
        //执行回调
        if (sMethodEnterListener != null) {
            sMethodEnterListener.enter(methodId, threadId);
        }

        //如果是主线程
        if (threadId == sMainThreadId) {

            //i方法被重复执行的 提醒
            if (assertIn) {
                android.util.Log.e(TAG, "ERROR!!! AppMethodBeat.i Recursive calls!!!");
                return;
            }
            assertIn = true;
            if (sIndex < Constants.BUFFER_SIZE) {
                mergeData(methodId, sIndex, true);
            } else {
                sIndex = 0;
                mergeData(methodId, sIndex, true);
            }
            ++sIndex;
            assertIn = false;
        }
    }

    /**
     * hook method when it's called out.
     *
     * @param methodId
     */
    public static void o(int methodId) {
        //对 AppMethodBeat 状态进行检查
        if (status <= STATUS_STOPPED) {
            return;
        }
        //对 methodId进行校验
        if (methodId >= METHOD_ID_MAX) {
            return;
        }

        //如果是主线程
        if (Thread.currentThread().getId() == sMainThreadId) {
            if (sIndex < Constants.BUFFER_SIZE) {
                mergeData(methodId, sIndex, false);
            } else {
                sIndex = 0;
                mergeData(methodId, sIndex, false);
            }
            ++sIndex;
        }
    }

    /**
     * when the special method calls,it's will be called.
     *
     * 当activity的 onWindowFocusChange（activity可被操作） 被调用时，at 方法就会被调用
     *
     * @param activity now at which activity
     * @param isFocus  this window if has focus
     */
    public static void at(Activity activity, boolean isFocus) {
        String activityName = activity.getClass().getName();
        if (isFocus) {
            //获取焦点的activity 添加到 sFocusActivitySet
            if (sFocusActivitySet.add(activityName)) {
                synchronized (listeners) {
                    //广播 activityName 获取到焦点
                    for (IAppMethodBeatListener listener : listeners) {
                        listener.onActivityFocused(activityName);
                    }
                }
                // activity 不都有了吗 为啥还要通过 getVisibleScene() 获取？
                MatrixLog.i(TAG, "[at] visibleScene[%s] has %s focus!", getVisibleScene(), "attach");
            }
        } else {
            //失去焦点的activity 从sFocusActivitySet移除
            if (sFocusActivitySet.remove(activityName)) {
                MatrixLog.i(TAG, "[at] visibleScene[%s] has %s focus!", getVisibleScene(), "detach");
            }
        }
    }

    public static String getVisibleScene() {
        return AppActiveMatrixDelegate.INSTANCE.getVisibleScene();
    }

    /**
     * merge trace info as a long data
     * <p>
     * 合并数据
     *
     * @param methodId ： methodId
     * @param index ： sBuffer 下标
     * @param isIn ： 是否是 i 方法
     */
    private static void mergeData(int methodId, int index, boolean isIn) {
        if (methodId == AppMethodBeat.METHOD_ID_DISPATCH) {//如果是 UIThreadMonitor 的 dispatchBegin 或者 dispatchEnd 方法
            sCurrentDiffTime = SystemClock.uptimeMillis() - sDiffTime;
        }


        // 合并后的数据存到 trueId中
        long trueId = 0L;
        if (isIn) {//如果是 i 方法 则第63位上是1，否则为0 （就是一个标志位）
            trueId |= 1L << 63;
        }
        //43-62位 存储 methodId
        trueId |= (long) methodId << 43;
        //0-42位存储 sCurrentDiffTime
        trueId |= sCurrentDiffTime & 0x7FFFFFFFFFFL;
        //存放到 sBuffer中
        sBuffer[index] = trueId;
        checkPileup(index);
        sLastIndex = index;
    }

    public void addListener(IAppMethodBeatListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(IAppMethodBeatListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private static IndexRecord sIndexRecordHead = null;

    public IndexRecord maskIndex(String source) {
        //创建 IndexRecord 的 header
        if (sIndexRecordHead == null) {
            sIndexRecordHead = new IndexRecord(sIndex - 1);
            sIndexRecordHead.source = source;
            return sIndexRecordHead;
        } else {
            //创建一个 IndexRecord 并插入链表
            IndexRecord indexRecord = new IndexRecord(sIndex - 1);
            indexRecord.source = source;
            IndexRecord record = sIndexRecordHead;
            IndexRecord last = null;
            while (record != null) {
                if (indexRecord.index <= record.index) {
                    if (null == last) {
                        IndexRecord tmp = sIndexRecordHead;
                        sIndexRecordHead = indexRecord;
                        indexRecord.next = tmp;
                    } else {
                        IndexRecord tmp = last.next;
                        if (null != last.next) {
                            last.next = indexRecord;
                        }
                        indexRecord.next = tmp;
                    }
                    return indexRecord;
                }
                last = record;
                record = record.next;
            }

            last.next = indexRecord;

            return indexRecord;
        }
    }

    //这是在干啥 ，不太清楚
    private static void checkPileup(int index) {
        IndexRecord indexRecord = sIndexRecordHead;
        while (indexRecord != null) {
            if (indexRecord.index == index || (indexRecord.index == -1 && sLastIndex == Constants.BUFFER_SIZE - 1)) {
                indexRecord.isValid = false;
                MatrixLog.w(TAG, "[checkPileup] %s", indexRecord.toString());
                sIndexRecordHead = indexRecord = indexRecord.next;
            } else {
                break;
            }
        }
    }

    //这玩意儿 是个链表 就是用来记录  index 和 source的对应关系的
    public static final class IndexRecord {
        public IndexRecord(int index) {
            this.index = index;
        }

        public IndexRecord() {
            this.isValid = false;
        }

        public int index;
        private IndexRecord next;
        public boolean isValid = true;
        public String source;

        public void release() {
            isValid = false;
            IndexRecord record = sIndexRecordHead;
            IndexRecord last = null;
            while (null != record) {
                if (record == this) {
                    if (null != last) {
                        last.next = record.next;
                    } else {
                        sIndexRecordHead = record.next;
                    }
                    record.next = null;
                    break;
                }
                last = record;
                record = record.next;
            }
        }

        @Override
        public String toString() {
            return "index:" + index + ",\tisValid:" + isValid + " source:" + source;
        }
    }

    public long[] copyData(IndexRecord startRecord) {
        return copyData(startRecord, new IndexRecord(sIndex - 1));
    }

    private long[] copyData(IndexRecord startRecord, IndexRecord endRecord) {
        long current = System.currentTimeMillis();
        long[] data = new long[0];
        try {
            if (startRecord.isValid && endRecord.isValid) {
                int length;
                int start = Math.max(0, startRecord.index);
                int end = Math.max(0, endRecord.index);

                if (end > start) {
                    length = end - start + 1;
                    data = new long[length];
                    System.arraycopy(sBuffer, start, data, 0, length);
                } else if (end < start) {
                    length = 1 + end + (sBuffer.length - start);
                    data = new long[length];
                    System.arraycopy(sBuffer, start, data, 0, sBuffer.length - start);
                    System.arraycopy(sBuffer, 0, data, sBuffer.length - start, end + 1);
                }
                return data;
            }
            return data;
        } catch (OutOfMemoryError e) {
            MatrixLog.e(TAG, e.toString());
            return data;
        } finally {
            MatrixLog.i(TAG, "[copyData] [%s:%s] length:%s cost:%sms", Math.max(0, startRecord.index), endRecord.index, data.length, System.currentTimeMillis() - current);
        }
    }

    public static long getDiffTime() {
        return sDiffTime;
    }

    public void printIndexRecord() {
        StringBuilder ss = new StringBuilder(" \n");
        IndexRecord record = sIndexRecordHead;
        while (null != record) {
            ss.append(record).append("\n");
            record = record.next;
        }
        MatrixLog.i(TAG, "[printIndexRecord] %s", ss.toString());
    }

}
