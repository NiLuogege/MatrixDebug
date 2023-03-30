package com.niluogege.mytestapp;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import com.niluogege.mytestapp.matrix.battery.BatteryCanaryInitHelper;
import com.niluogege.mytestapp.matrix.config.DynamicConfigImplDemo;
import com.niluogege.mytestapp.matrix.listener.TestPluginListener;
import com.niluogege.mytestapp.matrix.resource.ManualDumpActivity;
import com.tencent.matrix.Matrix;
import com.tencent.matrix.batterycanary.BatteryMonitorPlugin;
import com.tencent.matrix.iocanary.IOCanaryPlugin;
import com.tencent.matrix.iocanary.config.IOConfig;
import com.tencent.matrix.resource.ResourcePlugin;
import com.tencent.matrix.resource.config.ResourceConfig;
import com.tencent.matrix.trace.TracePlugin;
import com.tencent.matrix.trace.config.TraceConfig;
import com.tencent.matrix.trace.tracer.SignalAnrTracer;
import com.tencent.matrix.util.MatrixLog;

import java.io.File;

public class MyApp extends Application {
    private static final String TAG = "MyApp";
    private static Context sContext;
    public static Activity linkActivity;

    @Override
    public void onCreate() {
        super.onCreate();

        initMatrix();
    }

    private void initMatrix() {
        // Switch.
        DynamicConfigImplDemo dynamicConfig = new DynamicConfigImplDemo();

        sContext = this;
        MatrixLog.i(TAG, "Start Matrix configurations.");

        // Builder. Not necessary while some plugins can be configured separately.
        Matrix.Builder builder = new Matrix.Builder(this);

        // Reporter. Matrix will callback this listener when found issue then emitting it.
//        builder.pluginListener(new TestPluginListener(this));
        builder.pluginListener(new TestPluginListener(this));

        // Configure trace canary.
        TracePlugin tracePlugin = configureTracePlugin(dynamicConfig);
        builder.plugin(tracePlugin);

        // Configure resource canary.
        ResourcePlugin resourcePlugin = configureResourcePlugin(dynamicConfig);
        builder.plugin(resourcePlugin);

        // Configure io canary.
        IOCanaryPlugin ioCanaryPlugin = configureIOCanaryPlugin(dynamicConfig);
        builder.plugin(ioCanaryPlugin);

        // Configure SQLite lint plugin.
//        SQLiteLintPlugin sqLiteLintPlugin = configureSQLiteLintPlugin();
//        builder.plugin(sqLiteLintPlugin);

        // Configure battery canary.
        BatteryMonitorPlugin batteryMonitorPlugin = configureBatteryCanary();
        builder.plugin(batteryMonitorPlugin);

        Matrix matrix = Matrix.init(builder.build());
//        matrix.startAllPlugins();

        // Trace Plugin need call start() at the beginning.
        //todo 为了调试先不启动 tracePlugin
//        tracePlugin.start();
        resourcePlugin.start();

        MatrixLog.i(TAG, "Matrix configurations done.");
    }

    private TracePlugin configureTracePlugin(DynamicConfigImplDemo dynamicConfig) {

        //fps监控是否可用
        boolean fpsEnable = dynamicConfig.isFPSEnable();
        //耗时函数监控是否可用
        boolean traceEnable = dynamicConfig.isTraceEnable();
        //ANR监控是否可用
        boolean signalAnrTraceEnable = dynamicConfig.isSignalAnrTraceEnable();

        File traceFileDir = new File(getApplicationContext().getExternalCacheDir(), "matrix_trace");
        if (!traceFileDir.exists()) {
            if (traceFileDir.mkdirs()) {
                MatrixLog.e(TAG, "failed to create traceFileDir");
            }
        }

        MatrixLog.e(TAG, "traceFileDir=" + traceFileDir.getAbsolutePath());


        File anrTraceFile = new File(traceFileDir, "anr_trace");    // path : /data/user/0/sample.tencent.matrix/files/matrix_trace/anr_trace
        File printTraceFile = new File(traceFileDir, "print_trace");    // path : /data/user/0/sample.tencent.matrix/files/matrix_trace/print_trace

        TraceConfig traceConfig = new TraceConfig.Builder()
                .dynamicConfig(dynamicConfig)
                .enableFPS(fpsEnable)
                .enableEvilMethodTrace(traceEnable)
                .enableAnrTrace(traceEnable)
                .enableStartup(traceEnable)
                .enableIdleHandlerTrace(traceEnable)                    // Introduced in Matrix 2.0
                .enableMainThreadPriorityTrace(true)                    // Introduced in Matrix 2.0
                .enableSignalAnrTrace(signalAnrTraceEnable)             // Introduced in Matrix 2.0
                .anrTracePath(anrTraceFile.getAbsolutePath())
                .printTracePath(printTraceFile.getAbsolutePath())
                .splashActivities("sample.tencent.matrix.SplashActivity;")
                .isDebug(true)
                .isDevEnv(false)
                .build();

        //Another way to use SignalAnrTracer separately
        //useSignalAnrTraceAlone(anrTraceFile.getAbsolutePath(), printTraceFile.getAbsolutePath());

        return new TracePlugin(traceConfig);
    }

    private void useSignalAnrTraceAlone(String anrFilePath, String printTraceFile) {
        SignalAnrTracer signalAnrTracer = new SignalAnrTracer(this, anrFilePath, printTraceFile);
        signalAnrTracer.setSignalAnrDetectedListener(new SignalAnrTracer.SignalAnrDetectedListener() {
            @Override
            public void onAnrDetected(String stackTrace, String mMessageString, long mMessageWhen, boolean fromProcessErrorState, String cgroup) {
                // got an ANR
            }

            @Override
            public void onNativeBacktraceDetected(String backtrace, String mMessageString, long mMessageWhen, boolean fromProcessErrorState) {

            }
        });
        signalAnrTracer.onStartTrace();
    }

    private ResourcePlugin configureResourcePlugin(DynamicConfigImplDemo dynamicConfig) {
        Intent intent = new Intent();
        ResourceConfig.DumpMode mode = ResourceConfig.DumpMode.AUTO_DUMP;
        MatrixLog.i(TAG, "Dump Activity Leak Mode=%s", mode);
        intent.setClassName(this.getPackageName(), "com.tencent.mm.ui.matrix.ManualDumpActivity");
        ResourceConfig resourceConfig = new ResourceConfig.Builder()
                .dynamicConfig(dynamicConfig)
                .setAutoDumpHprofMode(mode)
                .setManualDumpTargetActivity(ManualDumpActivity.class.getName())
                .setManufacture(Build.MANUFACTURER)
                .setDetectDebuger(true)//设置在debug环境可用
                .build();
        ResourcePlugin.activityLeakFixer(this);

        return new ResourcePlugin(resourceConfig);
    }

    private IOCanaryPlugin configureIOCanaryPlugin(DynamicConfigImplDemo dynamicConfig) {
        return new IOCanaryPlugin(new IOConfig.Builder()
                .dynamicConfig(dynamicConfig)
                .build());
    }

//    private SQLiteLintPlugin configureSQLiteLintPlugin() {
//        SQLiteLintConfig sqlLiteConfig;
//
//        /*
//         * HOOK模式下，SQLiteLint会自己去获取所有已执行的sql语句及其耗时(by hooking sqlite3_profile)
//         * @see 而另一个模式：SQLiteLint.SqlExecutionCallbackMode.CUSTOM_NOTIFY , 则需要调用 {@link SQLiteLint#notifySqlExecution(String, String, int)}来通知
//         * SQLiteLint 需要分析的、已执行的sql语句及其耗时
//         * @see TestSQLiteLintActivity#doTest()
//         */
//        // sqlLiteConfig = new SQLiteLintConfig(SQLiteLint.SqlExecutionCallbackMode.HOOK);
//
//        sqlLiteConfig = new SQLiteLintConfig(SQLiteLint.SqlExecutionCallbackMode.CUSTOM_NOTIFY);
//        return new SQLiteLintPlugin(sqlLiteConfig);
//    }

    private BatteryMonitorPlugin configureBatteryCanary() {
        // Configuration of battery plugin is really complicated.
        // See it in BatteryCanaryInitHelper.
        return BatteryCanaryInitHelper.createMonitor();
    }

    public static Context getContext() {
        return sContext;
    }
}
