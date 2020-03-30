package com.tencent.matrix.trace.util;

import com.tencent.matrix.util.DeviceUtil;

public class Utils {

    public static String getStack() {
        StackTraceElement[] trace = new Throwable().getStackTrace();
        return getStack(trace);
    }

    public static String getStack(StackTraceElement[] trace) {
        return getStack(trace, "", -1);
    }

    public static String getStack(StackTraceElement[] trace, String preFixStr, int limit) {
        if ((trace == null) || (trace.length < 3)) {
            return "";
        }
        if (limit < 0) {
            limit = Integer.MAX_VALUE;
        }
        StringBuilder t = new StringBuilder(" \n");
        for (int i = 3; i < trace.length - 3 && i < limit; i++) {
            t.append(preFixStr);
            t.append("at ");
            t.append(trace[i].getClassName());
            t.append(":");
            t.append(trace[i].getMethodName());
            t.append("(" + trace[i].getLineNumber() + ")");
            t.append("\n");

        }
        return t.toString();
    }

    /**
     * 计算cup使用程度 = 线程耗时 / 总耗时
     * @param threadMs 线程耗时
     * @param ms 总耗时
     * @return
     */
    public static String calculateCpuUsage(long threadMs, long ms) {
        if (threadMs <= 0) {
            return ms > 1000 ? "0%" : "100%";
        }

        if (threadMs >= ms) {
            return "100%";
        }

        return String.format("%.2f", 1.f * threadMs / ms * 100) + "%";
    }

    public static boolean isEmpty(String str) {
        return null == str || str.equals("");
    }

    /**
     * 获取进程优先级
     *
     * nice（静态优先级）：值越大说明抢占资源的能力越差，优先级越高
     * priority（动态优先级）：值越小优先级越高
     *
     * @param pid 进程id
     * @return
     */
    public static int[] getProcessPriority(int pid) {
        String name = String.format("/proc/%s/stat", pid);
        int priority = Integer.MIN_VALUE;
        int nice = Integer.MAX_VALUE;
        try {
            //获取文件内容
            String content = DeviceUtil.getStringFromFile(name).trim();
            String[] args = content.split(" ");
            if (args.length >= 19) {
                priority = Integer.parseInt(args[17].trim());
                nice = Integer.parseInt(args[18].trim());
            }
        } catch (Exception e) {
            return new int[]{priority, nice};
        }
        return new int[]{priority, nice};
    }

    public static String formatTime(final long timestamp) {
        return new java.text.SimpleDateFormat("[yy-MM-dd HH:mm:ss]").format(new java.util.Date(timestamp));
    }
}
