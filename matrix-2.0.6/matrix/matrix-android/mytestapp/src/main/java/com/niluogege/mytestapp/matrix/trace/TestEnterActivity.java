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

package com.niluogege.mytestapp.matrix.trace;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.niluogege.mytestapp.R;
import com.niluogege.mytestapp.matrix.issue.IssueFilter;


/**
 * Created by caichongyang on 2017/11/14.
 */

public class TestEnterActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.test_fps_layout);
        findViewById(R.id.content).setVisibility(View.GONE);

        IssueFilter.setCurrentFilter(IssueFilter.ISSUE_TRACE);
        ListView listView = findViewById(R.id.list_view);
        String[] data = new String[200];
        for (int i = 0; i < 200; i++) {
            data[i] = "MatrixTrace:" + i;
        }
        listView.setAdapter(new ArrayAdapter(this, android.R.layout.simple_list_item_1, data));
        //这里延时了三秒 ，
        // Matrix检测到耗时方法的话 如果是debug环境会在控制台输出日志 具体的代码位置是 EvilMethodTracer.analyse 方法中
        // 如果用户配置了 pluginListener 监听则也会回到到监听的 onDetectIssue 方法中，
        //对于 本项目而言就是 TestPluginListener ，他的处理是跳转到 IssuesListActivity中，它里面会显示本次app运行期间所有的上报信息
        SystemClock.sleep(3000);
    }
}
