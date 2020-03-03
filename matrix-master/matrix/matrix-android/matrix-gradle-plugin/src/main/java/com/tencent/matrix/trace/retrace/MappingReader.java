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

package com.tencent.matrix.trace.retrace;

import com.tencent.matrix.javalib.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.LineNumberReader;

/**
 * Created by caichongyang on 2017/6/3.
 */
public class MappingReader {
    private final static String TAG = "MappingReader";
    private final static String SPLIT = ":";
    private final static String SPACE = " ";
    private final static String ARROW = "->";
    private final static String LEFT_PUNC = "(";
    private final static String RIGHT_PUNC = ")";
    private final static String DOT = ".";
    private final File proguardMappingFile;//mapping.txt 文件

    public MappingReader(File proguardMappingFile) {
        this.proguardMappingFile = proguardMappingFile;
    }

    /**
     * Reads the mapping file
     */
    public void read(MappingProcessor mappingProcessor) throws IOException {
        LineNumberReader reader = new LineNumberReader(new BufferedReader(new FileReader(proguardMappingFile)));
        try {
            String className = null;
            // Read the class and class member mappings.
            while (true) {
                String line = reader.readLine();
                if (line == null) {
                    break;
                }
                line = line.trim();
                if (!line.startsWith("#")) {//不是注释
                    if (line.endsWith(SPLIT)) {//类
                        //className ： 原始类名
                        className = parseClassMapping(line, mappingProcessor);
                    } else if (className != null) { // 类成员
                        parseClassMemberMapping(className, line, mappingProcessor);
                    }
                } else {
                    Log.i(TAG, "comment:# %s", line);
                }
            }
        } catch (IOException err) {
            throw new IOException("Can't read mapping file", err);
        } finally {
            try {
                reader.close();
            } catch (IOException ex) {
                // do nothing
            }
        }
    }

    /**
     * @param line read content
     * @param mappingProcessor
     * @return
     */
    private String parseClassMapping(String line, MappingProcessor mappingProcessor) {

        int leftIndex = line.indexOf(ARROW);
        if (leftIndex < 0) {
            return null;
        }
        int offset = 2;
        int rightIndex = line.indexOf(SPLIT, leftIndex + offset);
        if (rightIndex < 0) {
            return null;
        }

        //原始类名
        String className = line.substring(0, leftIndex).trim();
        //混淆后类名
        String newClassName = line.substring(leftIndex + offset, rightIndex).trim();

        // 解析 原始类名 和 混淆后类名 并保存到 MappingCollector 中 的三个集合中
        boolean ret = mappingProcessor.processClassMapping(className, newClassName);

        return ret ? className : null;
    }

    /**
     * Parses the a class member mapping
     *
     * @param className
     * @param line
     * @param mappingProcessor parse line such as
     *                         ___ ___ -> ___
     *                         ___:___:___ ___(___) -> ___
     *                         ___:___:___ ___(___):___ -> ___
     *                         ___:___:___ ___(___):___:___ -> ___
     */
    private void parseClassMemberMapping(String className, String line, MappingProcessor mappingProcessor) {
        int leftIndex1 = line.indexOf(SPLIT);//第一个 : 的角标
        int leftIndex2 = leftIndex1 < 0 ? -1 : line.indexOf(SPLIT, leftIndex1 + 1);//第二个 : 的角标
        int spaceIndex = line.indexOf(SPACE, leftIndex2 + 2);//第一个空格的角标
        int argIndex1 = line.indexOf(LEFT_PUNC, spaceIndex + 1);//第一个左括号的角标
        int argIndex2 = argIndex1 < 0 ? -1 : line.indexOf(RIGHT_PUNC, argIndex1 + 1);//第一个 有括号的角标
        int leftIndex3 = argIndex2 < 0 ? -1 : line.indexOf(SPLIT, argIndex2 + 1);//第三个 : 的角标
        int leftIndex4 = leftIndex3 < 0 ? -1 : line.indexOf(SPLIT, leftIndex3 + 1);//第四个 : 的角标
        int rightIndex = line.indexOf(ARROW, (leftIndex4 >= 0 ? leftIndex4 : leftIndex3 >= 0//第一个 -> 的角标
                ? leftIndex3 : argIndex2 >= 0 ? argIndex2 : spaceIndex) + 1);
        if (spaceIndex < 0 || rightIndex < 0) {
            return;
        }

        //获取返回类型
        String type = line.substring(leftIndex2 + 1, spaceIndex).trim();
        //获取混淆前方法名
        String name = line.substring(spaceIndex + 1, argIndex1 >= 0 ? argIndex1 : rightIndex).trim();
        //获取混淆后方法名
        String newName = line.substring(rightIndex + 2).trim();

        String newClassName = className;
        int dotIndex = name.lastIndexOf(DOT);
        if (dotIndex >= 0) {
            className = name.substring(0, dotIndex);
            name = name.substring(dotIndex + 1);
        }

        if (type.length() > 0 && name.length() > 0 && newName.length() > 0 && argIndex2 >= 0) {
            //解析出参数
            String arguments = line.substring(argIndex1 + 1, argIndex2).trim();
            //保存到 MappingCollector 中
            mappingProcessor.processMethodMapping(className, type, name, arguments, newClassName, newName);
        }
    }
}
