/*
 * Tencent is pleased to support the open source community by making wechat-matrix available.
 * Copyright (C) 2018 THL A29 Limited, a Tencent company. All rights reserved.
 * Licensed under the BSD 3-Clause License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tencent.matrix.batterycanary.monitor.feature;

import android.app.Application;
import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.tencent.matrix.Matrix;
import com.tencent.matrix.batterycanary.monitor.BatteryMonitorConfig;
import com.tencent.matrix.batterycanary.monitor.BatteryMonitorCore;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static com.tencent.matrix.batterycanary.monitor.BatteryMonitorConfig.AMS_HOOK_FLAG_BT;


@RunWith(AndroidJUnit4.class)
public class MonitorFeatureBlueToothTest {
    static final String TAG = "Matrix.test.MonitorFeatureBlueToothTest";

    Context mContext;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        if (!Matrix.isInstalled()) {
            Matrix.init(new Matrix.Builder(((Application) mContext.getApplicationContext())).build());
        }
    }

    @After
    public void shutDown() {
    }

    private BatteryMonitorCore mockMonitor() {
        BatteryMonitorConfig config = new BatteryMonitorConfig.Builder()
                .enable(JiffiesMonitorFeature.class)
                .enableBuiltinForegroundNotify(false)
                .enableForegroundMode(false)
                .wakelockTimeout(1000)
                .greyJiffiesTime(100)
                .foregroundLoopCheckTime(1000)
                .build();
        return new BatteryMonitorCore(config);
    }

    @Test
    public void testRegister() throws InterruptedException {
        BlueToothMonitorFeature feature = new BlueToothMonitorFeature();
        feature.configure(mockMonitor());

        BlueToothMonitorFeature.BlueToothSnapshot snapshot = feature.currentSnapshot();
        Assert.assertEquals(0, (int) snapshot.regsCount.get());
        Assert.assertEquals(0, (int) snapshot.discCount.get());
        Assert.assertEquals(0, (int) snapshot.scanCount.get());

        for (int i = 0; i < 50; i++) {
            feature.mTracing.onRegisterScanner();
            snapshot = feature.currentSnapshot();
            Assert.assertEquals(i + 1, (int) snapshot.regsCount.get());
            Assert.assertEquals(0, (int) snapshot.discCount.get());
            Assert.assertEquals(0, (int) snapshot.scanCount.get());
        }

        feature.onTurnOff();
        snapshot = feature.currentSnapshot();
        Assert.assertEquals(0, (int) snapshot.regsCount.get());
        Assert.assertEquals(0, (int) snapshot.discCount.get());
        Assert.assertEquals(0, (int) snapshot.scanCount.get());
    }

    @Test
    public void testDiscovery() throws InterruptedException {
        BlueToothMonitorFeature feature = new BlueToothMonitorFeature();
        feature.configure(mockMonitor());

        BlueToothMonitorFeature.BlueToothSnapshot snapshot = feature.currentSnapshot();
        Assert.assertEquals(0, (int) snapshot.regsCount.get());
        Assert.assertEquals(0, (int) snapshot.discCount.get());
        Assert.assertEquals(0, (int) snapshot.scanCount.get());

        for (int i = 0; i < 50; i++) {
            feature.mTracing.onStartDiscovery();
            snapshot = feature.currentSnapshot();
            Assert.assertEquals(0, (int) snapshot.regsCount.get());
            Assert.assertEquals(i + 1, (int) snapshot.discCount.get());
            Assert.assertEquals(0, (int) snapshot.scanCount.get());
        }

        feature.onTurnOff();
        snapshot = feature.currentSnapshot();
        Assert.assertEquals(0, (int) snapshot.regsCount.get());
        Assert.assertEquals(0, (int) snapshot.discCount.get());
        Assert.assertEquals(0, (int) snapshot.scanCount.get());
    }

    @Test
    public void testScan() throws InterruptedException {
        BlueToothMonitorFeature feature = new BlueToothMonitorFeature();
        feature.configure(mockMonitor());

        BlueToothMonitorFeature.BlueToothSnapshot snapshot = feature.currentSnapshot();
        Assert.assertEquals(0, (int) snapshot.regsCount.get());
        Assert.assertEquals(0, (int) snapshot.discCount.get());
        Assert.assertEquals(0, (int) snapshot.scanCount.get());

        for (int i = 0; i < 50; i++) {
            feature.mTracing.onStartScan();
            snapshot = feature.currentSnapshot();
            Assert.assertEquals(0, (int) snapshot.regsCount.get());
            Assert.assertEquals(0, (int) snapshot.discCount.get());
            Assert.assertEquals(i + 1, (int) snapshot.scanCount.get());
        }

        feature.onTurnOff();
        snapshot = feature.currentSnapshot();
        Assert.assertEquals(0, (int) snapshot.regsCount.get());
        Assert.assertEquals(0, (int) snapshot.discCount.get());
        Assert.assertEquals(0, (int) snapshot.scanCount.get());
    }

    @Test
    public void testEnableFlag() {
        Assert.assertEquals(0, (new BatteryMonitorConfig.Builder().build().amsHookEnableFlag & AMS_HOOK_FLAG_BT));
        Assert.assertEquals(AMS_HOOK_FLAG_BT, (new BatteryMonitorConfig.Builder().setAmsHookEnableFlag(0b1111).build().amsHookEnableFlag & AMS_HOOK_FLAG_BT));
    }
}
