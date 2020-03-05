package com.tencent.matrix.listeners;

/**
 * app 是否处于前台的 监听
 */
public interface IAppForeground {

    /**
     * 当有activity被打开时会被调用
     * @param isForeground APP处于前台
     */
    void onForeground(boolean isForeground);
}
