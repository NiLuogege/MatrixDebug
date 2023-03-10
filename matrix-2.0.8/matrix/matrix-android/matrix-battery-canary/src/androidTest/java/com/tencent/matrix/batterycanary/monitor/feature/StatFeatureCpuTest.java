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

import com.tencent.matrix.Matrix;
import com.tencent.matrix.batterycanary.monitor.BatteryMonitorConfig;
import com.tencent.matrix.batterycanary.monitor.BatteryMonitorCore;
import com.tencent.matrix.batterycanary.monitor.feature.CpuStatFeature.CpuStateSnapshot;
import com.tencent.matrix.batterycanary.monitor.feature.MonitorFeature.Snapshot.Delta;
import com.tencent.matrix.batterycanary.monitor.feature.MonitorFeature.Snapshot.Entry.DigitEntry;
import com.tencent.matrix.batterycanary.utils.BatteryMetricsTest;
import com.tencent.matrix.batterycanary.utils.PowerProfile;
import com.tencent.matrix.batterycanary.utils.ProcStatUtil;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;


@RunWith(AndroidJUnit4.class)
public class StatFeatureCpuTest {
    static final String TAG = "Matrix.test.StatFeatureCpuTest";

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
                .enable(CpuStatFeature.class)
                .enableBuiltinForegroundNotify(false)
                .enableForegroundMode(false)
                .wakelockTimeout(1000)
                .greyJiffiesTime(100)
                .foregroundLoopCheckTime(1000)
                .build();
        return new BatteryMonitorCore(config);
    }

    @Test
    public void testGetCpuStatesSnapshot() throws InterruptedException {
        CpuStatFeature feature = new CpuStatFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        Assert.assertTrue(feature.isSupported());
        CpuStateSnapshot cpuStateSnapshot = feature.currentCpuStateSnapshot();
        Assert.assertFalse(cpuStateSnapshot.isDelta);
        Assert.assertTrue(cpuStateSnapshot.isValid());
        Assert.assertTrue(cpuStateSnapshot.totalCpuJiffies() > 0);
        Assert.assertTrue(cpuStateSnapshot.cpuCoreStates.size() > 0);
        Assert.assertTrue(cpuStateSnapshot.procCpuCoreStates.size() > 0);
    }

    @Test
    public void testGetCpuStatesSnapshotDelta() throws InterruptedException {
        CpuStatFeature feature = new CpuStatFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        Assert.assertTrue(feature.isSupported());

        CpuStateSnapshot bgn = feature.currentCpuStateSnapshot();
        Thread.sleep(1000L);
        CpuStateSnapshot end = feature.currentCpuStateSnapshot();
        Delta<CpuStateSnapshot> delta = end.diff(bgn);

        Assert.assertTrue(delta.bgn.isValid());
        Assert.assertTrue(delta.end.isValid());
        Assert.assertTrue(delta.dlt.isValid());
        Assert.assertTrue(delta.dlt.isDelta);
        Assert.assertTrue(delta.end.totalCpuJiffies() >= delta.bgn.totalCpuJiffies());
        Assert.assertEquals(delta.dlt.totalCpuJiffies(), end.totalCpuJiffies() - bgn.totalCpuJiffies());
        Assert.assertTrue(delta.dlt.cpuCoreStates.size() > 0);
        Assert.assertTrue(delta.dlt.procCpuCoreStates.size() > 0);
    }

    @Test
    public void testConfigureSipping() throws InterruptedException, IOException {
        CpuStatFeature feature = new CpuStatFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        Assert.assertTrue(feature.isSupported());
        CpuStateSnapshot cpuStateSnapshot = feature.currentCpuStateSnapshot();
        Assert.assertFalse(cpuStateSnapshot.isDelta);
        Assert.assertTrue(cpuStateSnapshot.isValid());
        Assert.assertTrue(cpuStateSnapshot.totalCpuJiffies() > 0);

        PowerProfile powerProfile = PowerProfile.init(mContext);
        Assert.assertNotNull(powerProfile);
        Assert.assertTrue(powerProfile.isSupported());

        double cpuSip = cpuStateSnapshot.configureCpuSip(powerProfile);
        Assert.assertTrue(cpuSip > 0);

        double procSip = cpuStateSnapshot.configureProcSip(powerProfile, ProcStatUtil.currentPid().getJiffies());
        Assert.assertTrue(procSip > 0);
    }

    @Test
    public void testConfigureSippingDelta() throws InterruptedException, IOException {
        CpuStatFeature feature = new CpuStatFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        Assert.assertTrue(feature.isSupported());
        ProcStatUtil.ProcStat procStatBgn = ProcStatUtil.currentPid();
        CpuStateSnapshot bgn = feature.currentCpuStateSnapshot();
        BatteryMetricsTest.CpuConsumption.hanoi(20);
        CpuStateSnapshot end = feature.currentCpuStateSnapshot();

        for (int i = 0; i < end.procCpuCoreStates.size(); i++) {
            DigitEntry<Long> entry = end.procCpuCoreStates.get(i).getList().get(0);
            end.procCpuCoreStates.get(i).getList().set(0, DigitEntry.of(entry.get() + 100L * (i + 1)));
        }
        Delta<CpuStateSnapshot> delta = end.diff(bgn);

        Assert.assertTrue(delta.bgn.isValid());
        Assert.assertTrue(delta.end.isValid());
        Assert.assertTrue(delta.dlt.isValid());
        Assert.assertTrue(delta.dlt.isDelta);

        PowerProfile powerProfile = PowerProfile.init(mContext);
        Assert.assertNotNull(powerProfile);
        Assert.assertTrue(powerProfile.isSupported());

        double cpuSip = delta.dlt.configureCpuSip(powerProfile);
        Assert.assertTrue(cpuSip > 0);

        double procSip = delta.dlt.configureProcSip(powerProfile, ProcStatUtil.currentPid().getJiffies() - procStatBgn.getJiffies());
        Assert.assertTrue(procSip > 0);
    }

    @Test
    public void testConcurrent() throws InterruptedException {
        final CpuStatFeature feature = new CpuStatFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        List<Thread> threadList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int finalI = i;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    feature.currentCpuStateSnapshot();
                }
            });
            thread.start();
            threadList.add(thread);
        }
        for (Thread item : threadList) {
            item.join();
        }
    }
}
