/*
 * Copyright (C) 2014 Google Inc.
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

package com.squareup.haha.perflib;

import com.squareup.haha.guava.collect.Maps;

import java.util.Map;

public enum Type {
    OBJECT(2, 0), // Pointer sizes are dependent on the hprof file, so set it to 0 for now.
    BOOLEAN(4, 1),
    CHAR(5, 2),
    FLOAT(6, 4),
    DOUBLE(7, 8),
    BYTE(8, 1),
    SHORT(9, 2),
    INT(10, 4),
    LONG(11, 8);

    private static Map<Integer, Type> sTypeMap = Maps.newHashMap();

    private int mId;

    private int mSize;

    static {
        for (Type type : Type.values()) {
            sTypeMap.put(type.mId, type);
        }
    }

    Type(int type, int size) {
        mId = type;
        mSize = size;
    }

    public static Type getType(int id) {
        return sTypeMap.get(id);
    }

    public int getSize() {
        return mSize;
    }

    public int getTypeId() {
        return mId;
    }

    public static String getClassNameOfPrimitiveArray(Type type) {
        switch (type) {
            case BOOLEAN: return "boolean[]";
            case CHAR: return "char[]";
            case FLOAT: return "float[]";
            case DOUBLE: return "double[]";
            case BYTE: return "byte[]";
            case SHORT: return "short[]";
            case INT: return "int[]";
            case LONG: return "long[]";
            default: throw new IllegalArgumentException("OBJECT type is not a primitive type");
        }
    }
}

