package com.tencent.matrix.memory.canary.trim

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.OnLifecycleEvent
import com.tencent.matrix.Matrix
import com.tencent.matrix.lifecycle.IStateObserver
import com.tencent.matrix.lifecycle.owners.ProcessDeepBackgroundOwner
import com.tencent.matrix.lifecycle.owners.ProcessStagedBackgroundOwner
import com.tencent.matrix.lifecycle.supervisor.AppDeepBackgroundOwner
import com.tencent.matrix.lifecycle.supervisor.AppStagedBackgroundOwner
import com.tencent.matrix.util.MatrixHandlerThread
import com.tencent.matrix.util.MatrixLog
import com.tencent.matrix.util.safeApply
import java.util.concurrent.TimeUnit

interface TrimCallback {
    fun backgroundTrim()
    fun systemTrim(level: Int)
}

data class TrimMemoryConfig(
    val enable: Boolean = false,
    val delayMillis: Long = TimeUnit.MINUTES.toMillis(1)
)

/**
 * Trim memory when turned staged background or deep background for 1min or received system trim callback
 */
object TrimMemoryNotifier {

    private const val TAG = "Matrix.TrimMemoryNotifier"

    private val procTrimCallbacks = ArrayList<TrimCallback>()
    private val appTrimCallbacks = ArrayList<TrimCallback>()

    private fun ArrayList<TrimCallback>.backgroundTrim() {
        synchronized(this) {
            forEach {
                safeApply(TAG) {
                    it.backgroundTrim()
                }
            }
        }
    }

    private fun ArrayList<TrimCallback>.systemTrim(level: Int) {
        synchronized(this) {
            forEach {
                safeApply(TAG) {
                    it.systemTrim(level)
                }
            }
        }
    }

    fun init(config: TrimMemoryConfig) {
        if (!config.enable) {
            return
        }

        if (!Matrix.isInstalled()) {
            MatrixLog.e(TAG, "Matrix NOT installed yet")
            return
        }


        Matrix.with().application.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onLowMemory() {
                MatrixLog.e(TAG, "onLowMemory post")
                MatrixHandlerThread.getDefaultHandler().post {
                    MatrixLog.e(TAG, "onLowMemory")
                    procTrimCallbacks.systemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                    appTrimCallbacks.systemTrim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
                }
            }

            override fun onTrimMemory(level: Int) {
                if (level <= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
                    MatrixLog.e(TAG, "onTrimMemory post: $level")
                    MatrixHandlerThread.getDefaultHandler().post {
                        MatrixLog.e(TAG, "onTrimMemory: $level")
                        procTrimCallbacks.systemTrim(level)
                        appTrimCallbacks.systemTrim(level)
                    }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) {}
        })


        val procTrimTask = Runnable {
            MatrixLog.i(TAG, "trim: process staged bg timeout ${config.delayMillis}")
            procTrimCallbacks.backgroundTrim()
        }

        object : IStateObserver {
            val runningHandler = MatrixHandlerThread.getDefaultHandler()

            override fun on() {
                runningHandler.removeCallbacksAndMessages(null)
                runningHandler.postDelayed(procTrimTask, config.delayMillis)
            }

            override fun off() {
                runningHandler.removeCallbacks(procTrimTask)
            }
        }.let {
            ProcessStagedBackgroundOwner.observeForever(it)
        }

        ProcessDeepBackgroundOwner.observeForever(object : IStateObserver {
            override fun on() {
                MatrixLog.i(TAG, "trim: process deep bg")
                procTrimCallbacks.backgroundTrim()
            }

            override fun off() {}
        })


        val appTrimTask = Runnable {
            MatrixLog.i(TAG, "trim: app staged bg timeout ${config.delayMillis}")
            appTrimCallbacks.backgroundTrim()
        }

        object : IStateObserver {
            val runningHandler = MatrixHandlerThread.getDefaultHandler()

            override fun on() {
                runningHandler.removeCallbacksAndMessages(null)
                runningHandler.postDelayed(appTrimTask, config.delayMillis)
            }

            override fun off() {
                runningHandler.removeCallbacks(appTrimTask)
            }
        }.let {
            AppStagedBackgroundOwner.observeForever(it)
        }

        AppDeepBackgroundOwner.observeForever(object : IStateObserver {
            override fun on() {
                MatrixLog.i(TAG, "trim: app deep bg")
                appTrimCallbacks.backgroundTrim()
            }

            override fun off() {}
        })
    }

    fun addProcessBackgroundTrimCallback(callback: TrimCallback) {
        synchronized(procTrimCallbacks) {
            procTrimCallbacks.add(callback)
        }
    }

    fun addProcessBackgroundTrimCallback(lifecycleOwner: LifecycleOwner, callback: TrimCallback) {
        synchronized(procTrimCallbacks) {
            procTrimCallbacks.add(callback)
        }
        lifecycleOwner.lifecycle.addObserver(object : LifecycleObserver {
            @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            fun release() {
                synchronized(procTrimCallbacks) {
                    procTrimCallbacks.remove(callback)
                }
            }
        })
    }

    fun addAppBackgroundTrimCallback(callback: TrimCallback) {
        synchronized(appTrimCallbacks) {
            appTrimCallbacks.add(callback)
        }
    }

    fun addAppBackgroundTrimCallback(lifecycleOwner: LifecycleOwner, callback: TrimCallback) {
        synchronized(appTrimCallbacks) {
            appTrimCallbacks.add(callback)
        }
        lifecycleOwner.lifecycle.addObserver(object : LifecycleObserver {
            @OnLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            fun release() {
                synchronized(appTrimCallbacks) {
                    appTrimCallbacks.remove(callback)
                }
            }
        })
    }

    fun removeTrimCallback(callback: TrimCallback) {
        synchronized(procTrimCallbacks) {
            procTrimCallbacks.remove(callback)
        }
        synchronized(appTrimCallbacks) {
            appTrimCallbacks.remove(callback)
        }
    }
}