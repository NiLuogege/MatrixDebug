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
import com.tencent.matrix.batterycanary.utils.TimeBreaker;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


@RunWith(AndroidJUnit4.class)
public class MonitorAppStatTest {
    static final String TAG = "Matrix.test.MonitorAppStatTest";

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
                .enable(WakeLockMonitorFeature.class)
                .enable(DeviceStatMonitorFeature.class)
                .enable(AlarmMonitorFeature.class)
                .enableBuiltinForegroundNotify(false)
                .enableForegroundMode(false)
                .wakelockTimeout(1000)
                .greyJiffiesTime(100)
                .foregroundLoopCheckTime(1000)
                .build();
        return new BatteryMonitorCore(config);
    }

    @Test
    public void testGetCurrentSnapshot() throws InterruptedException {
        AppStatMonitorFeature feature = new AppStatMonitorFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        Assert.assertEquals(1, feature.mStampList.size());
        Assert.assertEquals(1, Integer.valueOf(feature.mStampList.get(0).key).intValue());

        Thread.sleep(100);
        AppStatMonitorFeature.AppStatSnapshot snapshot = feature.currentAppStatSnapshot();
        Assert.assertNotNull(snapshot);
        Assert.assertEquals(1, feature.mStampList.size());
        Assert.assertEquals(1, Integer.valueOf(feature.mStampList.get(0).key).intValue());
        Assert.assertTrue(snapshot.uptime.get() >= 100L);
        Assert.assertTrue(snapshot.fgRatio.get() > 0L);
        Assert.assertEquals(0L, (long) snapshot.bgRatio.get());
        Assert.assertEquals(0L, (long) snapshot.fgSrvRatio.get());
    }

    @Test
    public void testGetCurrentSnapshotWithBg() throws InterruptedException {
        AppStatMonitorFeature feature = new AppStatMonitorFeature();
        feature.configure(mockMonitor());
        feature.onTurnOn();

        Thread.sleep(100);
        AppStatMonitorFeature.AppStatSnapshot snapshot = feature.currentAppStatSnapshot();
        Assert.assertNotNull(snapshot);

        Assert.assertEquals(1, feature.mStampList.size());
        Assert.assertEquals(1, Integer.valueOf(feature.mStampList.get(0).key).intValue());
        Assert.assertTrue(snapshot.uptime.get() >= 100L);
        Assert.assertTrue(snapshot.fgRatio.get() > 99);
        Assert.assertEquals(0L, (long) snapshot.bgRatio.get());
        Assert.assertEquals(0L, (long) snapshot.fgSrvRatio.get());

        feature.onForeground(false);
        Thread.sleep(100);
        snapshot = feature.currentAppStatSnapshot();
        Assert.assertNotNull(snapshot);
        Assert.assertEquals(2, feature.mStampList.size());
        Assert.assertEquals(2, Integer.valueOf(feature.mStampList.get(0).key).intValue());
        Assert.assertTrue(snapshot.uptime.get() >= 200L);
        Assert.assertEquals(50, snapshot.fgRatio.get(), 5);
        Assert.assertEquals(50, snapshot.bgRatio.get(), 5);
        Assert.assertEquals(0L, (long) snapshot.fgSrvRatio.get());

        TimeBreaker.Stamp stamp = new TimeBreaker.Stamp("3");
        feature.mStampList.add(0, stamp);
        Assert.assertEquals(3, feature.mStampList.size());
        Assert.assertEquals(3, Integer.valueOf(feature.mStampList.get(0).key).intValue());

        Thread.sleep(100);

        snapshot = feature.currentAppStatSnapshot();
        Assert.assertNotNull(snapshot);
        Assert.assertEquals(3, feature.mStampList.size());
        Assert.assertEquals(3, Integer.valueOf(feature.mStampList.get(0).key).intValue());
        Assert.assertTrue(snapshot.uptime.get() >= 300L);
        Assert.assertEquals(33, snapshot.fgRatio.get(), 5);
        Assert.assertEquals(33, snapshot.bgRatio.get(), 5);
        Assert.assertEquals(33, snapshot.fgSrvRatio.get(), 5);
    }

    @Test
    public void testEmptyListOps() {
        List<Object> emptyList = Collections.emptyList();
        try {
            emptyList.add(0, null);
            Assert.fail("should failed");
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testConcurrent() throws InterruptedException {
        final AppStatMonitorFeature feature = new AppStatMonitorFeature();
        feature.configure(mockMonitor());

        List<Thread> threadList = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int finalI = i;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    feature.onForeground( finalI % 2 == 0);
                }
            });
            thread.start();
            threadList.add(thread);
        }

        for (int i = 0; i < 100; i++) {
            final int finalI = i;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                   if ( finalI % 3 == 0) {
                       feature.onTurnOff();
                   } else {
                       feature.onTurnOn();
                   }
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
