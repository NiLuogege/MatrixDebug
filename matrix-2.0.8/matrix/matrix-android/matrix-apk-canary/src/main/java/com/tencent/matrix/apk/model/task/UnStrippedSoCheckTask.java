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

package com.tencent.matrix.apk.model.task;

import com.google.gson.JsonArray;
import com.tencent.matrix.apk.model.exception.TaskExecuteException;
import com.tencent.matrix.apk.model.exception.TaskInitException;
import com.tencent.matrix.apk.model.job.JobConfig;
import com.tencent.matrix.apk.model.job.JobConstants;
import com.tencent.matrix.apk.model.result.TaskJsonResult;
import com.tencent.matrix.apk.model.result.TaskResult;
import com.tencent.matrix.apk.model.result.TaskResultFactory;
import com.tencent.matrix.apk.model.task.util.ApkConstants;
import com.tencent.matrix.javalib.util.FileUtil;
import com.tencent.matrix.javalib.util.Log;
import com.tencent.matrix.javalib.util.Util;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tencent.matrix.apk.model.result.TaskResultFactory.TASK_RESULT_TYPE_JSON;
import static com.tencent.matrix.apk.model.task.TaskFactory.TASK_TYPE_UNSTRIPPED_SO;

/**
 * Created by jinqiuchen on 17/6/29.
 */

public class UnStrippedSoCheckTask extends ApkTask {

    private static final String TAG = "Matrix.UnStrippedSoCheckTask";

    private File libDir;
    private String toolnmPath;

    public UnStrippedSoCheckTask(JobConfig jobConfig, Map<String, String> params) {
        super(jobConfig, params);
        type = TASK_TYPE_UNSTRIPPED_SO;
    }

    @Override
    public void init() throws TaskInitException {
        super.init();
        final String inputPath = config.getUnzipPath();
        toolnmPath = params.get(JobConstants.PARAM_TOOL_NM);
        if (Util.isNullOrNil(toolnmPath)) {
            throw new TaskInitException(TAG + "---The path of tool 'nm' is not given!");
        } else {
            Pattern envPattern = Pattern.compile("(\\$[a-zA-Z_-]+)");
            Matcher matcher =  envPattern.matcher(toolnmPath);
            while (matcher.find()) {
                if (!Util.isNullOrNil(matcher.group())) {
                    String env = System.getenv(matcher.group().substring(1));
                    Log.d(TAG, "%s -> %s", matcher.group().substring(1), env);
                    if (!Util.isNullOrNil(env)) {
                        toolnmPath = toolnmPath.replace(matcher.group(), env);
                    }
                }
            }
            Log.i(TAG, "toolnm pah is %s", toolnmPath);
        }
        if (!FileUtil.isLegalFile(toolnmPath)) {
            throw new TaskInitException(TAG + "---Can not find the tool 'nm'!");
        }
        if (!Util.isNullOrNil(inputPath)) {
            Log.i(TAG, "inputPath:%s", inputPath);
            libDir = new File(inputPath, "lib");
        } else {
            throw new TaskInitException(TAG + "---APK-UNZIP-PATH can not be null!");
        }

    }

    private boolean isSoStripped(File libFile) throws IOException, InterruptedException {
        ProcessBuilder processBuilder = new ProcessBuilder(toolnmPath, libFile.getAbsolutePath());
        Process process = processBuilder.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String line = reader.readLine();
        boolean result = false;
        if (!Util.isNullOrNil(line)) {
            Log.d(TAG, "%s", line);
            String[] columns = line.split(":");
            if (columns.length == 3 && columns[2].trim().equalsIgnoreCase("no symbols")) {
                result = true;
            }
        }
        reader.close();
        process.waitFor();
        return result;
    }

    @Override
    public TaskResult call() throws TaskExecuteException {
        try {
            TaskResult taskResult = TaskResultFactory.factory(getType(), TASK_RESULT_TYPE_JSON, config);
            if (taskResult == null) {
                return null;
            }
            long startTime = System.currentTimeMillis();
            List<File> libFiles = new ArrayList<>();
            JsonArray jsonArray = new JsonArray();
            if (libDir.exists() && libDir.isDirectory()) {
                File[] dirs = libDir.listFiles();
                for (File dir : dirs) {
                    if (dir.isDirectory()) {
                        File[] libs = dir.listFiles();
                        for (File libFile : libs) {
                            if (libFile.isFile() && libFile.getName().endsWith(ApkConstants.DYNAMIC_LIB_FILE_SUFFIX)) {
                                libFiles.add(libFile);
                            }
                        }
                    }
                }
            }
            for (File libFile : libFiles) {
                if (!isSoStripped(libFile)) {
                    Log.i(TAG, "lib: %s is not stripped", libFile.getName());

                    jsonArray.add(libFile.getName());
                }
            }
            ((TaskJsonResult) taskResult).add("unstripped-lib", jsonArray);
            taskResult.setStartTime(startTime);
            taskResult.setEndTime(System.currentTimeMillis());
            return taskResult;
        } catch (Exception e) {
            throw new TaskExecuteException(e.getMessage(), e);
        }
    }
}
