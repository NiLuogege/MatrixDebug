/*
 * Copyright (C) 2008 Google Inc.
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
import com.squareup.haha.annotations.Nullable;
import com.squareup.haha.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

public class ClassInstance extends Instance {

    private final long mValuesOffset;

    public ClassInstance(long id, @NonNull StackTrace stack, long valuesOffset) {
        super(id, stack);
        mValuesOffset = valuesOffset;
    }

    @VisibleForTesting
    @NonNull
    List<FieldValue> getFields(String name) {
        ArrayList<FieldValue> result = new ArrayList<FieldValue>();
        for (FieldValue value : getValues()) {
            if (value.getField().getName().equals(name)) {
                result.add(value);
            }
        }
        return result;
    }

    @NonNull
    public List<FieldValue> getValues() {
        ArrayList<FieldValue> result = new ArrayList<FieldValue>();

        ClassObj clazz = getClassObj();
        getBuffer().setPosition(mValuesOffset);
        while (clazz != null) {
            for (Field field : clazz.getFields()) {
                result.add(new FieldValue(field, readValue(field.getType())));
            }
            clazz = clazz.getSuperClassObj();
        }
        return result;
    }

    @Override
    public final void accept(@NonNull Visitor visitor) {
        visitor.visitClassInstance(this);
        for (FieldValue field : getValues()) {
            if (field.getValue() instanceof Instance) {
                if (!mReferencesAdded) {
                    ((Instance) field.getValue()).addReference(field.getField(), this);
                }
                visitor.visitLater(this, (Instance) field.getValue());
            }
        }
        mReferencesAdded = true;
    }

    @Override
    public boolean getIsSoftReference() {
        return getClassObj().getIsSoftReference();
    }

    public final String toString() {
        return String.format("%s@%d (0x%x)", getClassObj().getClassName(), getUniqueId(), getUniqueId());
    }

    public static class FieldValue {
        private Field mField;
        private Object mValue;

        public FieldValue(@NonNull Field field, @Nullable Object value) {
            this.mField = field;
            this.mValue = value;
        }

        public Field getField() {
            return mField;
        }

        public Object getValue() {
            return mValue;
        }
    }
}
