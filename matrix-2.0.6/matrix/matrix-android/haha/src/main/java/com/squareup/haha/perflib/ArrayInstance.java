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
import com.squareup.haha.perflib.io.HprofBuffer;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;

public class ArrayInstance extends Instance {

    private final Type mType;

    private final int mLength;

    private final long mValuesOffset;

    public ArrayInstance(long id, @NonNull StackTrace stack, @NonNull Type type, int length,
            long valuesOffset) {
        super(id, stack);
        mType = type;
        mLength = length;
        mValuesOffset = valuesOffset;
    }

    @NonNull
    public Object[] getValues() {
        Object[] values = new Object[mLength];

        getBuffer().setPosition(mValuesOffset);
        for (int i = 0; i < mLength; i++) {
            values[i] = readValue(mType);
        }
        return values;
    }

    @NonNull
    private byte[] asRawByteArray(int start, int elementCount) {
        getBuffer().setPosition(mValuesOffset);
        assert mType != Type.OBJECT;
        assert start + elementCount <= mLength;
        byte[] bytes = new byte[elementCount * mType.getSize()];
        getBuffer().readSubSequence(bytes, start * mType.getSize(), elementCount * mType.getSize());
        return bytes;
    }

    @NonNull
    public char[] asCharArray(int offset, int length) {
        assert mType == Type.CHAR;
        // TODO: Make this copy less by supporting offset in asRawByteArray.
        CharBuffer charBuffer = ByteBuffer.wrap(asRawByteArray(offset, length)).order(HprofBuffer.HPROF_BYTE_ORDER).asCharBuffer();
        char[] result = new char[length];
        charBuffer.get(result);
        return result;
    }

    @Override
    public final int getSize() {
        // TODO: Take the rest of the fields into account: length, type, etc (~16 bytes).
        return mLength * mHeap.mSnapshot.getTypeSize(mType);
    }

    @Override
    public final void accept(@NonNull Visitor visitor) {
        visitor.visitArrayInstance(this);
        if (mType == Type.OBJECT) {
            for (Object value : getValues()) {
                if (value instanceof Instance) {
                    if (!mReferencesAdded) {
                        ((Instance)value).addReference(null, this);
                    }
                    visitor.visitLater(this, (Instance)value);
                }
            }
            mReferencesAdded = true;
        }
    }

    @Override
    public ClassObj getClassObj() {
        if (mType == Type.OBJECT) {
            return super.getClassObj();
        } else {
            // Primitive arrays don't set their classId, we need to do the lookup manually.
            return mHeap.mSnapshot.findClass(Type.getClassNameOfPrimitiveArray(mType));
        }
    }

    public Type getArrayType() {
        return mType;
    }

    public final String toString() {
        String className = getClassObj().getClassName();
        if (className.endsWith("[]")) {
            className = className.substring(0, className.length() - 2);
        }
        return String.format("%s[%d]@%d (0x%x)", className, mLength, getUniqueId(), getUniqueId());
    }
}
