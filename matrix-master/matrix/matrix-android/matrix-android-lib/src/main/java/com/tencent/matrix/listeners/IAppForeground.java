package com.tencent.matrix.listeners;

public interface IAppForeground {

    /**
     * 当有activity被打开时会被调用
     * @param isForeground APP处于前台
     */
    void onForeground(boolean isForeground);
}
