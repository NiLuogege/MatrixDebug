package com.niluogege.mytestapp.matrix.trace;

import static com.niluogege.mytestapp.MyApp.getContext;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.tencent.matrix.util.MatrixLog;


public class StartUpBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "Matrix.StartUpBroadcastReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        MatrixLog.i(TAG, "[onReceive]");
        getContext().startActivity(new Intent(getContext(), TestOtherProcessActivity.class));
    }
}
