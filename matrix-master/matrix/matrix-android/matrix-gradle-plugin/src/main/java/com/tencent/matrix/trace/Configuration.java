package com.tencent.matrix.trace;

import com.tencent.matrix.javalib.util.FileUtil;
import com.tencent.matrix.javalib.util.Util;
import com.tencent.matrix.trace.retrace.MappingCollector;

import java.util.HashSet;

public class Configuration {

    public String packageName;//包名
    public String mappingDir;//mapping文件存储目录
    public String baseMethodMapPath;//build.gradle 中配置的 baseMethodMapFile
    public String methodMapFilePath;//methodMapping.txt 文件路径
    public String ignoreMethodMapFilePath;//ignoreMethodMapping.txt 文件路径
    public String blackListFilePath;//blackListFile文件路径
    public String traceClassOut;//插桩后的 class存储目录
    //保存具体的 混淆后的黑名单 类名 或者 包名
    public HashSet<String> blackSet = new HashSet<>();

    Configuration(String packageName, String mappingDir, String baseMethodMapPath, String methodMapFilePath,
                  String ignoreMethodMapFilePath, String blackListFilePath, String traceClassOut) {
        this.packageName = packageName;
        this.mappingDir = Util.nullAsNil(mappingDir);
        this.baseMethodMapPath = Util.nullAsNil(baseMethodMapPath);
        this.methodMapFilePath = Util.nullAsNil(methodMapFilePath);
        this.ignoreMethodMapFilePath = Util.nullAsNil(ignoreMethodMapFilePath);
        this.blackListFilePath = Util.nullAsNil(blackListFilePath);
        this.traceClassOut = Util.nullAsNil(traceClassOut);
    }

    public int parseBlackFile(MappingCollector processor) {
        String blackStr = TraceBuildConstants.DEFAULT_BLACK_TRACE + FileUtil.readFileAsString(blackListFilePath);

        // /转为 . 然后通过回车分隔
        String[] blackArray = blackStr.trim().replace("/", ".").split("\n");

        if (blackArray != null) {
            for (String black : blackArray) {
                if (black.length() == 0) {
                    continue;
                }
                if (black.startsWith("#")) {//去除注释
                    continue;
                }
                if (black.startsWith("[")) {//去除 package，  class 等标识
                    continue;
                }

                if (black.startsWith("-keepclass ")) {
                    black = black.replace("-keepclass ", "");
                    blackSet.add(processor.proguardClassName(black, black));// 获取并保存 混淆后的 class名 到 blackSet
                } else if (black.startsWith("-keeppackage ")) {
                    black = black.replace("-keeppackage ", "");
                    blackSet.add(processor.proguardPackageName(black, black));
                }
            }
        }
        return blackSet.size();
    }

    @Override
    public String toString() {
        return "\n# Configuration" + "\n"
                + "|* packageName:\t" + packageName + "\n"
                + "|* mappingDir:\t" + mappingDir + "\n"
                + "|* baseMethodMapPath:\t" + baseMethodMapPath + "\n"
                + "|* methodMapFilePath:\t" + methodMapFilePath + "\n"
                + "|* ignoreMethodMapFilePath:\t" + ignoreMethodMapFilePath + "\n"
                + "|* blackListFilePath:\t" + blackListFilePath + "\n"
                + "|* traceClassOut:\t" + traceClassOut + "\n";
    }

    public static class Builder {

        public String packageName;
        public String mappingPath;
        public String baseMethodMap;
        public String methodMapFile;
        public String ignoreMethodMapFile;
        public String blackListFile;
        public String traceClassOut;

        public Builder setPackageName(String packageName) {
            this.packageName = packageName;
            return this;
        }

        public Builder setMappingPath(String mappingPath) {
            this.mappingPath = mappingPath;
            return this;
        }

        public Builder setBaseMethodMap(String baseMethodMap) {
            this.baseMethodMap = baseMethodMap;
            return this;
        }

        public Builder setTraceClassOut(String traceClassOut) {
            this.traceClassOut = traceClassOut;
            return this;
        }

        public Builder setMethodMapFilePath(String methodMapDir) {
            methodMapFile = methodMapDir;
            return this;
        }

        public Builder setIgnoreMethodMapFilePath(String methodMapDir) {
            ignoreMethodMapFile = methodMapDir;
            return this;
        }

        public Builder setBlackListFile(String blackListFile) {
            this.blackListFile = blackListFile;
            return this;
        }

        public Configuration build() {
            return new Configuration(packageName, mappingPath, baseMethodMap, methodMapFile, ignoreMethodMapFile, blackListFile, traceClassOut);
        }

    }
}
