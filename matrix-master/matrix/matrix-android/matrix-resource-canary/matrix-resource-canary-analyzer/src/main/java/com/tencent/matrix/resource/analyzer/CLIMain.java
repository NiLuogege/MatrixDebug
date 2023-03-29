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

package com.tencent.matrix.resource.analyzer;

import com.tencent.matrix.resource.analyzer.model.ActivityLeakResult;
import com.tencent.matrix.resource.analyzer.model.AndroidExcludedBmpRefs;
import com.tencent.matrix.resource.analyzer.model.AndroidExcludedRefs;
import com.tencent.matrix.resource.analyzer.model.DuplicatedBitmapResult;
import com.tencent.matrix.resource.analyzer.model.ExcludedBmps;
import com.tencent.matrix.resource.analyzer.model.ExcludedRefs;
import com.tencent.matrix.resource.analyzer.model.HeapSnapshot;
import com.tencent.matrix.resource.analyzer.model.HprofBitmapProvider;
import com.tencent.matrix.resource.analyzer.utils.BitmapDecoder;
import com.tencent.matrix.resource.common.utils.StreamUtil;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.MissingOptionException;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.json.JSONObject;

import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import static com.tencent.matrix.resource.analyzer.CLIMain.AnalyzerOptions.OPTION_COMPRESS_OUTPUT;
import static com.tencent.matrix.resource.analyzer.CLIMain.AnalyzerOptions.OPTION_HELP;
import static com.tencent.matrix.resource.analyzer.CLIMain.AnalyzerOptions.OPTION_INPUT;
import static com.tencent.matrix.resource.analyzer.CLIMain.AnalyzerOptions.OPTION_MIN_BMPLEAK_SIZE;
import static com.tencent.matrix.resource.analyzer.CLIMain.AnalyzerOptions.OPTION_OUTPUT;
import static com.tencent.matrix.resource.analyzer.model.DuplicatedBitmapResult.DuplicatedBitmapEntry;

/**
 * Created by tangyinsheng on 2017/7/3.
 */

public final class CLIMain {
    private static final int ERROR_SUCCESS                     = 0;
    private static final int ERROR_MISSING_INPUT_PATH          = -1;
    private static final int ERROR_NEED_ARGUMENTS              = -2;
    private static final int ERROR_MISSING_OUTPUT_PATH         = -3;
    private static final int ERROR_OTHERS                      = -255;

    private static File    mInputFile      = null;
    private static File    mOutputFile     = null;
    private static boolean mCompressOutput = false;
    private static int     mMinBmpLeakSize = 5000;

    private static final String EXTRA_INFO_NAME = "extra.info";

    @SuppressWarnings("unused")
    enum AnalyzerOptions {
        OPTION_INPUT {
            @Override
            Option build() {
                return Option.builder("i")
                        .longOpt("input")
                        .desc("Required. Path to read result generated by resource canary module.")
                        .numberOfArgs(1)
                        .argName("inputPath")
                        .optionalArg(false)
                        .required(true)
                        .build();
            }
        },
        OPTION_OUTPUT {
            @Override
            Option build() {
                return Option.builder("o")
                        .longOpt("output")
                        .desc("Required. Path to store analyze result directory.")
                        .numberOfArgs(1)
                        .argName("outputPath")
                        .optionalArg(false)
                        .required(true)
                        .build();
            }
        },
        OPTION_COMPRESS_OUTPUT {
            @Override
            Option build() {
                return Option.builder("co")
                        .longOpt("compress-output")
                        .desc("Optional. Compress analyze result as a zip.")
                        .required(false)
                        .build();
            }
        },
        OPTION_MIN_BMPLEAK_SIZE {
            @Override
            Option build() {
                return Option.builder("mb")
                        .longOpt("min-bmpleak-size")
                        .desc("Optional. Minimum size of accepted bmp leak."
                                + " Any leaks whose size is less than that would be ignored.")
                        .numberOfArgs(1)
                        .argName("minBmpLeakSize")
                        .optionalArg(false)
                        .required(false)
                        .build();
            }
        },
        OPTION_HELP {
            @Override
            Option build() {
                return Option.builder("h")
                        .longOpt("help")
                        .desc("Optional. Show this message.")
                        .required(false)
                        .build();
            }
        };

        Option mOption = null;

        abstract Option build();

        public static Options buildAll() {
            final Options options = new Options();
            for (AnalyzerOptions builder : EnumSet.allOf(AnalyzerOptions.class)) {
                final Option option = builder.build();
                builder.mOption = option;
                options.addOption(option);
            }
            return options;
        }
    }

    private static Options sOptions = AnalyzerOptions.buildAll();

    private static void printUsage(PrintStream out) {
        final PrintWriter pw = new PrintWriter(out);
        pw.println("Matrix ResourceCanary CommandLine Tools.\n");
        final HelpFormatter fmtter = new HelpFormatter();
        fmtter.setOptionComparator(null);
        fmtter.printUsage(pw, 120, "java -jar analyzer.jar", sOptions);
        pw.println("\noptions:");
        fmtter.printOptions(pw, 120, sOptions, 0, 4);
        pw.flush();
    }

    private static void parseArguments(CommandLine cmdline) throws ParseException {
        final String inputPath = cmdline.getOptionValue(OPTION_INPUT.mOption.getLongOpt());
        mInputFile = new File(inputPath);

        if (cmdline.hasOption(OPTION_COMPRESS_OUTPUT.mOption.getLongOpt())) {
            mCompressOutput = true;
        }

        String outputPath = cmdline.getOptionValue(OPTION_OUTPUT.mOption.getLongOpt());
        if (mCompressOutput && !outputPath.endsWith(".zip") && !outputPath.endsWith(".jar")) {
            outputPath += ".zip";
        }
        mOutputFile = new File(outputPath);

        final String minBmpLeakSizeVal = cmdline.getOptionValue(OPTION_MIN_BMPLEAK_SIZE.mOption.getLongOpt());
        if (minBmpLeakSizeVal != null) {
            mMinBmpLeakSize = Integer.parseInt(minBmpLeakSizeVal);
        }
    }

    private static void doAnalyze() throws IOException {
        ZipFile zf = null;
        BufferedReader br = null;
        File tempHprofFile = null;
        try {
            zf = new ZipFile(mInputFile);
            final ZipEntry canaryResultInfoEntry = new ZipEntry("result.info");
            final Map<String, String> resultInfoMap = new HashMap<>();
            br = new BufferedReader(new InputStreamReader(zf.getInputStream(canaryResultInfoEntry)));
            String confLine = null;
            while ((confLine = br.readLine()) != null) {
                if (confLine.startsWith("#")) {
                    // Skip comment.
                    continue;
                }
                final String[] kvPair = confLine.split("\\s*=\\s*");
                if (kvPair.length != 2) {
                    // Skip bad config line.
                    continue;
                }
                final String key = kvPair[0].trim();
                final String value = kvPair[1].trim();
                resultInfoMap.put(key, value);
            }

            final String sdkVersionStr = resultInfoMap.get("sdkVersion");
            if (sdkVersionStr == null) {
                throw new IllegalStateException("sdkVersion is absent in result.info.");
            }
            final int sdkVersion = Integer.parseInt(sdkVersionStr);

            final String manufacturer = resultInfoMap.get("manufacturer");
            if (manufacturer == null) {
                throw new IllegalStateException("manufacturer is absent in result.info.");
            }

            final String hprofEntryName = resultInfoMap.get("hprofEntry");
            if (hprofEntryName == null) {
                throw new IllegalStateException("hprofEntry is absent in result.info.");
            }
            final ZipEntry hprofEntry = new ZipEntry(hprofEntryName);

            final String leakedActivityKey = resultInfoMap.get("leakedActivityKey");
            if (leakedActivityKey == null) {
                throw new IllegalStateException("leakedActivityKey is absent in result.info.");
            }

            // We would extract hprof entry into a temporary file.
            tempHprofFile = new File(new File("").getAbsoluteFile(), "temp_" + System.currentTimeMillis() + ".hprof");
            StreamUtil.extractZipEntry(zf, hprofEntry, tempHprofFile);

            // Parse extra info if exists.
            final JSONObject extraInfo = new JSONObject();
            final ZipEntry extraInfoEntry = zf.getEntry(EXTRA_INFO_NAME);
            if (extraInfoEntry != null) {
                BufferedReader br2 = null;
                try {
                    br2 = new BufferedReader(new InputStreamReader(zf.getInputStream(extraInfoEntry)));
                    String line = null;
                    while ((line = br2.readLine()) != null) {
                        if (line.startsWith("#")) {
                            // Skip comment.
                            continue;
                        }
                        final String[] pair = line.split("\\s*=\\s*");
                        if (pair.length < 2) {
                            throw new IllegalStateException("bad extra info line: " + line);
                        }
                        final String key = pair[0].trim();
                        final String value = pair[1].trim();
                        extraInfo.put(key, value);
                    }
                } finally {
                    StreamUtil.closeQuietly(br2);
                }
            }

            // Then do analyzing works and output into directory or zip according to the option. Besides,
            // store extra info into the result json by the way.
            // 从 result.info 文件中拿到 hprof 文件、sdkVersion 等信息，接着开始分析
            analyzeAndStoreResult(tempHprofFile, sdkVersion, manufacturer, leakedActivityKey, extraInfo);
        } finally {
            if (tempHprofFile != null) {
                tempHprofFile.delete();
            }
            StreamUtil.closeQuietly(br);
            StreamUtil.closeQuietly(zf);
        }
    }

    private static void analyzeAndStoreResult(File hprofFile, int sdkVersion, String manufacturer,
                                              String leakedActivityKey, JSONObject extraInfo) throws IOException {
        final HeapSnapshot heapSnapshot = new HeapSnapshot(hprofFile);
        final ExcludedRefs excludedRefs = AndroidExcludedRefs.createAppDefaults(sdkVersion, manufacturer).build();
        // 分析 Activity 内存泄漏
        final ActivityLeakResult activityLeakResult
                = new ActivityLeakAnalyzer(leakedActivityKey, excludedRefs).analyze(heapSnapshot);

        // 分析重复 Bitmap
        DuplicatedBitmapResult duplicatedBmpResult = DuplicatedBitmapResult.noDuplicatedBitmap(0);
        if (sdkVersion < 26) {
            final ExcludedBmps excludedBmps = AndroidExcludedBmpRefs.createDefaults().build();
            duplicatedBmpResult = new DuplicatedBitmapAnalyzer(mMinBmpLeakSize, excludedBmps).analyze(heapSnapshot);
        } else {
            System.err.println("\n ! SDK version of target device is larger or equal to 26, "
                    + "which is not supported by DuplicatedBitmapAnalyzer.");
        }
        final String resultJsonName = "result.json";
        final String bufferContentsRootDirName = "buffer_contents";
        final String extralInfoKey = "extraInfo";
        if (mCompressOutput) {
            ZipOutputStream zos = null;
            try {
                zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(mOutputFile)));
                final ZipEntry analyzeResultEntry = new ZipEntry(resultJsonName);
                zos.putNextEntry(analyzeResultEntry);
                try {
                    final PrintWriter pw = new PrintWriter(zos);
                    final JSONObject resultJson = new JSONObject();
                    final JSONObject activityLeakResultJson = new JSONObject();
                    activityLeakResult.encodeToJSON(activityLeakResultJson);
                    final JSONObject duplicatedBmpResultJson = new JSONObject();
                    duplicatedBmpResult.encodeToJSON(duplicatedBmpResultJson);

                    resultJson.put("activityLeakResult", activityLeakResultJson)
                              .put("duplicatedBitmapResult", duplicatedBmpResultJson);

                    if (extraInfo != null && extraInfo.length() > 0) {
                        resultJson.put(extralInfoKey, extraInfo);
                    }

                    pw.println(resultJson.toString());
                    pw.flush();
                } finally {
                    try {
                        zos.closeEntry();
                    } catch (Throwable ignored) {
                        // Ignored.
                    }
                }

                // Store bitmap buffer.
                final List<DuplicatedBitmapEntry> duplicatedBmpEntries = duplicatedBmpResult.getDuplicatedBitmapEntries();
                final int duplicatedBmpEntryCount = duplicatedBmpEntries.size();
                for (int i = 0; i < duplicatedBmpEntryCount; ++i) {
                    final DuplicatedBitmapEntry entry = duplicatedBmpEntries.get(i);
                    final BufferedImage img = BitmapDecoder.getBitmap(
                            new HprofBitmapProvider(entry.getBuffer(), entry.getWidth(), entry.getHeight()));
                    // Since bmp format is not compatible with alpha channel, we export buffer as png instead.
                    final String pngName = bufferContentsRootDirName + "/" + entry.getBufferHash() + ".png";
                    try {
                        zos.putNextEntry(new ZipEntry(pngName));
                        ImageIO.write(img, "png", zos);
                        zos.flush();
                    } finally {
                        try {
                            zos.closeEntry();
                        } catch (Throwable ignored) {
                            // Ignored.
                        }
                    }
                }
            } finally {
                StreamUtil.closeQuietly(zos);
            }
        } else {
            final File outputDir = mOutputFile;
            if (!outputDir.exists()) {
                outputDir.mkdirs();
            }
            PrintWriter resultJsonPW = null;
            try {
                // 生成 result.json 文件并写入结果
                final File resultJsonFile = new File(outputDir, resultJsonName);
                resultJsonPW = new PrintWriter(new BufferedWriter(new FileWriter(resultJsonFile)));
                final JSONObject resultJson = new JSONObject();
                final JSONObject activityLeakResultJson = new JSONObject();
                activityLeakResult.encodeToJSON(activityLeakResultJson);
                final JSONObject duplicatedBmpResultJson = new JSONObject();
                duplicatedBmpResult.encodeToJSON(duplicatedBmpResultJson);

                resultJson.put("activityLeakResult", activityLeakResultJson)
                          .put("duplicatedBitmapResult", duplicatedBmpResultJson);

                if (extraInfo != null && extraInfo.length() > 0) {
                    resultJson.put(extralInfoKey, extraInfo);
                }

                resultJsonPW.println(resultJson.toString());
                resultJsonPW.flush();
            } finally {
                StreamUtil.closeQuietly(resultJsonPW);
            }

            // Store bitmap buffer.
            final File bufferContentsRootDir = new File(outputDir, bufferContentsRootDirName);
            if (!bufferContentsRootDir.exists()) {
                bufferContentsRootDir.mkdirs();
            }
            final List<DuplicatedBitmapEntry> duplicatedBmpEntries = duplicatedBmpResult.getDuplicatedBitmapEntries();
            final int duplicatedBmpEntryCount = duplicatedBmpEntries.size();
            // 输出重复的 Bitmap 图像
            for (int i = 0; i < duplicatedBmpEntryCount; ++i) {
                final DuplicatedBitmapEntry entry = duplicatedBmpEntries.get(i);
                final BufferedImage img = BitmapDecoder.getBitmap(
                        new HprofBitmapProvider(entry.getBuffer(), entry.getWidth(), entry.getHeight()));
                // Since bmp format is not compatible with alpha channel, we export buffer as png instead.
                final String pngName = entry.getBufferHash() + ".png";
                OutputStream os = null;
                try {
                    final File pngFile = new File(bufferContentsRootDir, pngName);
                    os = new BufferedOutputStream(new FileOutputStream(pngFile));
                    ImageIO.write(img, "png", os);
                } finally {
                    StreamUtil.closeQuietly(os);
                }
            }
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            printUsage(System.out);
            System.exit(ERROR_NEED_ARGUMENTS);
        }
        try {
            final CommandLine cmdline = new DefaultParser().parse(sOptions, args);
            if (cmdline.hasOption(OPTION_HELP.mOption.getLongOpt())) {
                printUsage(System.out);
                System.exit(ERROR_SUCCESS);
            }

            parseArguments(cmdline);

            doAnalyze();

            System.exit(ERROR_SUCCESS);
        } catch (MissingOptionException e) {
            int errCode = ERROR_OTHERS;
            System.err.println(e.getMessage());
            final String firstMissingOpt
                    = (String) e.getMissingOptions().iterator().next();
            if (AnalyzerOptions.OPTION_INPUT.mOption.getOpt().equals(firstMissingOpt)) {
                errCode = ERROR_MISSING_INPUT_PATH;
            } else if (AnalyzerOptions.OPTION_OUTPUT.mOption.getOpt().equals(firstMissingOpt)) {
                errCode = ERROR_MISSING_OUTPUT_PATH;
            }
            System.exit(errCode);
        } catch (Throwable thr) {
            thr.printStackTrace();
            System.exit(ERROR_OTHERS);
        }
    }
}
