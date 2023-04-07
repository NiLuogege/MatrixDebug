/*
 * Copyright (C) 2014 The Android Open Source Project
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

import com.squareup.haha.annotations.NonNull;
import com.squareup.haha.guava.base.Objects;

/**
 * A field with an associated type and name.
 */
public final class Field {

    @NonNull
    private final Type mType;

    @NonNull
    private final String mName;

    public Field(@NonNull Type type, @NonNull String name) {
        mType = type;
        mName = name;
    }

    @NonNull
    public Type getType() {
        return mType;
    }

    @NonNull
    public String getName() {
        return mName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }

        if (!(o instanceof Field)) {
            return false;
        }

        Field field = (Field) o;

        return mType == field.mType && mName.equals(field.mName);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mType, mName);
    }
}
