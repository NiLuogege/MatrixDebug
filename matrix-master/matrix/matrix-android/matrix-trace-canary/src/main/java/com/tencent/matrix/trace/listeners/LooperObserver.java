package com.tencent.matrix.trace.listeners;

import android.support.annotation.CallSuper;

public abstract class LooperObserver  {

    private boolean isDispatchBegin = false;

    /**
     *
     * @param beginMs dispatch 的起始时间
     * @param cpuBeginMs dispatch 的起始时 当前线程时间
     * @param token 等于 dispatch 的起始时间
     */
    @CallSuper
    public void dispatchBegin(long beginMs, long cpuBeginMs, long token) {
        isDispatchBegin = true;
    }

    /**
     *
     * @param focusedActivityName 当前activity的名字
     * @param start looper dispatch 的起始时间
     * @param end looper dispatch 的结束时间
     * @param frameCostMs 该帧耗时
     * @param inputCostNs input 花费 时间
     * @param animationCostNs animation 花费 时间
     * @param traversalCostNs traversal 花费 时间
     */
    public void doFrame(String focusedActivityName, long start, long end, long frameCostMs, long inputCostNs, long animationCostNs, long traversalCostNs) {

    }

    /**
     *
     * @param beginMs dispatch 的起始时间
     * @param cpuBeginMs dispatch 的起始时 当前线程时间
     * @param endMs dispatch 的结束时间
     * @param cpuEndMs dispatch 的结束时 当前线程时间
     * @param token 等于 dispatch 的起始时间
     * @param isBelongFrame 是否属于一帧？ 不确定
     */
    @CallSuper
    public void dispatchEnd(long beginMs, long cpuBeginMs, long endMs, long cpuEndMs, long token, boolean isBelongFrame) {
        isDispatchBegin = false;
    }

    public boolean isDispatchBegin() {
        return isDispatchBegin;
    }
}
