package com.tencent.matrix.trace.core;

import android.os.Build;
import android.os.Looper;
import android.os.MessageQueue;
import android.os.SystemClock;
import android.support.annotation.CallSuper;
import android.util.Log;
import android.util.Printer;

import com.tencent.matrix.util.MatrixLog;
import com.tencent.matrix.util.ReflectUtils;

import java.util.HashSet;
import java.util.Objects;

/**
 * LooperMonitor 是一个 空闲时执行的handler
 */
public class LooperMonitor implements MessageQueue.IdleHandler {

    private final HashSet<LooperDispatchListener> listeners = new HashSet<>();
    private static final String TAG = "Matrix.LooperMonitor";
    private LooperPrinter printer;
    private Looper looper;
    private static final long CHECK_TIME = 60 * 1000L;
    private long lastCheckPrinterTime = 0;

    public abstract static class LooperDispatchListener {

        boolean isHasDispatchStart = false;

        public boolean isValid() {
            return false;
        }

        //message 开始执行
        public void dispatchStart() {

        }

        //message 开始执行
        //该注解，是告诉子类，必须 要调用 super.XXX方法
        @CallSuper
        public void onDispatchStart(String x) {
            this.isHasDispatchStart = true;
            dispatchStart();
        }
        //message 执行结束
        @CallSuper
        public void onDispatchEnd(String x) {
            this.isHasDispatchStart = false;
            dispatchEnd();
        }

        //message 执行结束
        public void dispatchEnd() {
        }
    }

    private static final LooperMonitor mainMonitor = new LooperMonitor();

    static void register(LooperDispatchListener listener) {
        mainMonitor.addListener(listener);
    }

    static void unregister(LooperDispatchListener listener) {
        mainMonitor.removeListener(listener);
    }

    public HashSet<LooperDispatchListener> getListeners() {
        return listeners;
    }

    public void addListener(LooperDispatchListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(LooperDispatchListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    public LooperMonitor(Looper looper) {
        Objects.requireNonNull(looper);
        this.looper = looper;
        //添加 自定义的 Printer
        resetPrinter();
        //添加 IdleHandler
        addIdleHandler(looper);
    }

    private LooperMonitor() {
        this(Looper.getMainLooper());
    }

    @Override
    public boolean queueIdle() {
        // 这里是怕 printer 被其他 组件覆盖，所以要一直检查
        // 1分钟检查一次
        if (SystemClock.uptimeMillis() - lastCheckPrinterTime >= CHECK_TIME) {
            resetPrinter();
            lastCheckPrinterTime = SystemClock.uptimeMillis();
        }
        //返回true 表示要重复执行
        return true;
    }

    /**
     * 释放资源，回归原始状态
     */
    public synchronized void onRelease() {
        if (printer != null) {
            synchronized (listeners) {
                listeners.clear();
            }
            MatrixLog.v(TAG, "[onRelease] %s, origin printer:%s", looper.getThread().getName(), printer.origin);
            looper.setMessageLogging(printer.origin);
            removeIdleHandler(looper);
            looper = null;
            printer = null;
        }
    }

    private static boolean isReflectLoggingError = false;

    /**
     * 添加 自定义的 Printer
     */
    private synchronized void resetPrinter() {
        Printer originPrinter = null;
        try {
            if (!isReflectLoggingError) {
                //获取之前的 Printer ,防止项目其他功能在 设置了 这个 Printer， 如果直接设置
                //那其他 工具就不能正常运行了 大厂的程序员 还是细啊！！
                originPrinter = ReflectUtils.get(looper.getClass(), "mLogging", looper);

                //如果已经 hook过 就直接返回
                if (originPrinter == printer && null != printer) {
                    return;
                }
            }
        } catch (Exception e) {
            isReflectLoggingError = true;
            Log.e(TAG, "[resetPrinter] %s", e);
        }

        if (null != printer) {
            MatrixLog.w(TAG, "maybe thread:%s printer[%s] was replace other[%s]!",
                    looper.getThread().getName(), printer, originPrinter);
        }
        //设置自己的Printer
        looper.setMessageLogging(printer = new LooperPrinter(originPrinter));
        if (null != originPrinter) {
            MatrixLog.i(TAG, "reset printer, originPrinter[%s] in %s", originPrinter, looper.getThread().getName());
        }
    }

    private synchronized void removeIdleHandler(Looper looper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            looper.getQueue().removeIdleHandler(this);
        } else {
            try {
                MessageQueue queue = ReflectUtils.get(looper.getClass(), "mQueue", looper);
                queue.removeIdleHandler(this);
            } catch (Exception e) {
                Log.e(TAG, "[removeIdleHandler] %s", e);
            }

        }
    }

    /**
     *  添加 IdleHandler
     *  6.0以上是用api， 6.0以下 是用反射添加
     *
     * @param looper
     */
    private synchronized void addIdleHandler(Looper looper) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            looper.getQueue().addIdleHandler(this);
        } else {
            try {
                MessageQueue queue = ReflectUtils.get(looper.getClass(), "mQueue", looper);
                queue.addIdleHandler(this);
            } catch (Exception e) {
                Log.e(TAG, "[removeIdleHandler] %s", e);
            }
        }
    }


    //自定义 一个 Printer
    class LooperPrinter implements Printer {
        public Printer origin;
        boolean isHasChecked = false;
        boolean isValid = false;

        LooperPrinter(Printer printer) {
            this.origin = printer;
        }

        @Override
        public void println(String x) {
            if (null != origin) {
                //执行原始printer的 println方法
                origin.println(x);
                if (origin == this) {
                    throw new RuntimeException(TAG + " origin == this");
                }
            }

            //检查 输出内容是否有效（有可能 有些系统做了修改，真实蛋疼）
            if (!isHasChecked) {
                isValid = x.charAt(0) == '>' || x.charAt(0) == '<';
                isHasChecked = true;
                if (!isValid) {
                    MatrixLog.e(TAG, "[println] Printer is inValid! x:%s", x);
                }
            }

            if (isValid) {
                dispatch(x.charAt(0) == '>', x);
            }

        }
    }

    /**
     * @param isBegin 是否是执行 message 开始
     * @param log
     */
    private void dispatch(boolean isBegin, String log) {

        for (LooperDispatchListener listener : listeners) {
            if (listener.isValid()) {
                if (isBegin) {
                    if (!listener.isHasDispatchStart) {
                        //分发开始
                        listener.onDispatchStart(log);
                    }
                } else {
                    if (listener.isHasDispatchStart) {
                        //分发结束
                        listener.onDispatchEnd(log);
                    }
                }
            } else if (!isBegin && listener.isHasDispatchStart) {
                //分发结束
                listener.dispatchEnd();
            }
        }

    }


}
