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

package com.tencent.matrix.javalib.util;

import java.io.File;
import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Created by jinqiuchen on 17/7/4.
 */

public final class Util {

    private Util() {
    }

    public static boolean isNullOrNil(String str) {
        return str == null || str.isEmpty();
    }

    public static String nullAsNil(String str) {
        return str == null ? "" : str;
    }

    public static boolean preventZipSlip(java.io.File output, String zipEntryName) {

        try {
            if (zipEntryName.contains("..") && new File(output, zipEntryName).getCanonicalPath().startsWith(output.getCanonicalPath() + File.separator)) {
                return true;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return true;
        }
        return false;
    }

    public static boolean isNumber(String str) {
        Pattern pattern = Pattern.compile("\\d+");
        return pattern.matcher(str).matches();
    }

    public static String byteArrayToHex(byte[] data) {
        char[] hexDigits = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'};
        char[] str = new char[data.length * 2];
        int k = 0;
        for (int i = 0; i < data.length; i++) {
            byte byte0 = data[i];
            str[k++] = hexDigits[byte0 >>> 4 & 0xf];
            str[k++] = hexDigits[byte0 & 0xf];
        }
        return new String(str);
    }

    public static String formatByteUnit(long bytes) {

        if (bytes >= 1024 * 1024) {
            return String.format("%.2fMB", bytes / (1.0 * 1024 * 1024));
        } else if (bytes >= 1024) {
            return String.format("%.2fKB", bytes / (1.0 * 1024));
        } else {
            return String.format("%dBytes", bytes);
        }
    }

/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

    public static String globToRegexp(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() * 2);
        int begin = 0;
        sb.append('^');
        for (int i = 0, n = glob.length(); i < n; i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                begin = appendQuoted(sb, glob, begin, i) + 1;
                if (i < n - 1 && glob.charAt(i + 1) == '*') {
                    i++;
                    begin++;
                }
                sb.append(".*?");
            } else if (c == '?') {
                begin = appendQuoted(sb, glob, begin, i) + 1;
                sb.append(".?");
            }
        }
        appendQuoted(sb, glob, begin, glob.length());
        sb.append('$');
        return sb.toString();
    }

    private static int appendQuoted(StringBuilder sb, String s, int from, int to) {
        if (to > from) {
            boolean isSimple = true;
            for (int i = from; i < to; i++) {
                char c = s.charAt(i);
                if (!Character.isLetterOrDigit(c) && c != '/' && c != ' ') {
                    isSimple = false;
                    break;
                }
            }
            if (isSimple) {
                for (int i = from; i < to; i++) {
                    sb.append(s.charAt(i));
                }
                return to;
            }
            sb.append(Pattern.quote(s.substring(from, to)));
        }
        return to;
    }

    public static String capitalize(String word) {
        if (word.isEmpty()) {
            return word;
        }

        char c = word.charAt(0);

        char upperChar = Character.toUpperCase(c);

        return upperChar + word.substring(1);

    }

//    public static String capitalizedString(String word) {
//        if (word.isEmpty()) {
//            return word;
//        }
//
//        char c = word.charAt(0);
//
//        // see if the letter is using more than one char.
//        if (c >= Character.MIN_HIGH_SURROGATE && c <= Character.MAX_HIGH_SURROGATE) {
//            int codePoint = word.codePointAt(0);
//            int charCount = Character.charCount(codePoint);
//
//            String upperString = word.substring(0, charCount).toUpperCase(Locale.US);
//            String leftString = word.substring(charCount);
//            return upperString + leftString;
//        } else {
//            char upperChar = Character.toUpperCase(c);
//
//            // it's not clear where non surrogate-pair values can trigger this but this is safer.
//            if (upperChar == -0x1) { // Character.ERROR (internal!)
//                upperChar = word.substring(0, 1).toUpperCase(Locale.US).charAt(0);
//            }
//
//            return upperChar + word.substring(1);
//        }
//    }
//
//    public String appendCapitalized(String prefix, String name, String suffix) {
//        StringBuilder sb = new StringBuilder(prefix.length() + name.length() + suffix.length());
//        sb.append(prefix);
//        sb.append(capitalizedString(name));
//        sb.append(capitalizedString(suffix));
//        return sb.toString();
//    }
}
