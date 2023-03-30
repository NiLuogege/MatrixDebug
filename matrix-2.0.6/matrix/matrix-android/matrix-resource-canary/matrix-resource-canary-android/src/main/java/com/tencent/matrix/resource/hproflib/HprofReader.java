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

package com.tencent.matrix.resource.hproflib;

import com.tencent.matrix.resource.hproflib.model.Field;
import com.tencent.matrix.resource.hproflib.model.ID;
import com.tencent.matrix.resource.hproflib.model.Type;
import com.tencent.matrix.resource.hproflib.utils.IOUtil;
import com.tencent.matrix.util.MatrixLog;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Created by tangyinsheng on 2017/6/25.
 * <p>
 * http://note.youdao.com/noteshare?id=3c71440b4ee1ec7e778de5700226fbf4&sub=AAA3125C0F8046369016C49732BD7AEC 这个笔记里记录了 .hprof文件中的信息格式
 */

public class HprofReader {
    private static final String TAG = "HprofReader";

    //.hprof 原始文件的流，也就是待裁剪的那个
    private final InputStream mStreamIn;
    private int mIdSize = 0;

    public HprofReader(InputStream in) {
        mStreamIn = in;
    }

    //访问者开始访问
    public void accept(HprofVisitor hv) throws IOException {
        //先访问 header
        acceptHeader(hv);
        //再访问 Record
        acceptRecord(hv);
        //结束访问
        hv.visitEnd();
    }

    /**
     * 访问header 包含
     * <p>
     * - 格式名和版本号 18byte, 如：JAVA PROFILE 1.0.3
     * - 标识符大小 4byte ，我们成为id 这个就是这个id所占用的长度，后面在解析Record的时候会用到，如 4
     * - 时间戳（高位时间戳 4byte + 低位时间戳 4byte） 8byte ,如 1680164571571 （其实就是这个文件生成的时间 如 2023-03-30 16:22:51）
     */
    private void acceptHeader(HprofVisitor hv) throws IOException {
        final String text = IOUtil.readNullTerminatedString(mStreamIn);
        final int idSize = IOUtil.readBEInt(mStreamIn);
        if (idSize <= 0 || idSize >= (Integer.MAX_VALUE >> 1)) {
            throw new IOException("bad idSize: " + idSize);
        }
        final long timestamp = IOUtil.readBELong(mStreamIn);
        mIdSize = idSize;
        MatrixLog.i(TAG, "acceptHeader, text=%s, idSize=%d timestamp=%d", text, idSize, timestamp);
        hv.visitHeader(text, idSize, timestamp);
    }

    /**
     * 访问 Record 包含
     * - tag 1byte,
     * - 时间戳 4byte,
     * - 数据长度 4byte,
     * - 数据内容 数据长度个byte, 不同的tag包含的数据不一样
     */
    private void acceptRecord(HprofVisitor hv) throws IOException {
        try {
            while (true) {
                //读取 tag
                final int tag = mStreamIn.read();
                //读取时间戳
                final int timestamp = IOUtil.readBEInt(mStreamIn);
                //读取数据长度
                final long length = IOUtil.readBEInt(mStreamIn) & 0x00000000FFFFFFFFL;
                //通过不同的tag 读取不同的数据内容
                switch (tag) {
                    //String ，这个就类似于 resource.arsc文件中的 字符串池，里面存储了所有用到过的字符串，如变量名类名等待，其他用到字符串的地方会通过id在这个池子中查找
                    case HprofConstants.RECORD_TAG_STRING:
                        acceptStringRecord(timestamp, length, hv);
                        break;
                    case HprofConstants.RECORD_TAG_LOAD_CLASS://加载的类
                        acceptLoadClassRecord(timestamp, length, hv);
                        break;
                    case HprofConstants.RECORD_TAG_STACK_FRAME://栈帧
                        acceptStackFrameRecord(timestamp, length, hv);
                        break;
                    case HprofConstants.RECORD_TAG_STACK_TRACE://栈，包含多个栈帧
                        acceptStackTraceRecord(timestamp, length, hv);
                        break;
                    case HprofConstants.RECORD_TAG_HEAP_DUMP:
                    case HprofConstants.RECORD_TAG_HEAP_DUMP_SEGMENT:
                        acceptHeapDumpRecord(tag, timestamp, length, hv);
                        break;
                    case HprofConstants.RECORD_TAG_ALLOC_SITES:
                    case HprofConstants.RECORD_TAG_HEAP_SUMMARY:
                    case HprofConstants.RECORD_TAG_START_THREAD:
                    case HprofConstants.RECORD_TAG_END_THREAD:
                    case HprofConstants.RECORD_TAG_HEAP_DUMP_END:
                    case HprofConstants.RECORD_TAG_CPU_SAMPLES:
                    case HprofConstants.RECORD_TAG_CONTROL_SETTINGS:
                    case HprofConstants.RECORD_TAG_UNLOAD_CLASS:
                    case HprofConstants.RECORD_TAG_UNKNOWN:
                    default:
                        acceptUnconcernedRecord(tag, timestamp, length, hv);
                        break;
                }
            }
        } catch (EOFException ignored) {
            // Ignored.
        }
    }

    /**
     * 数据内容是String
     * 格式如下
     * ID：ID for this string
     * [u1]*：UTF8 characters for string (NOT NULL terminated)
     */
    private void acceptStringRecord(int timestamp, long length, HprofVisitor hv) throws IOException {
        //获取这个 string 的Id
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        //读取String 内容
        final String text = IOUtil.readString(mStreamIn, length - mIdSize);
        hv.visitStringRecord(id, text, timestamp, length);

//        MatrixLog.i(TAG, "acceptRecord acceptStringRecord id=%s ,text=%s", id.toString(), text);
    }

    /**
     * 数据内容是 类
     * 格式如下
     * u4 :class serial number (always > 0)
     * ID :class object ID
     * u4:stack trace serial number
     * ID：class name string ID
     */
    private void acceptLoadClassRecord(int timestamp, long length, HprofVisitor hv) throws IOException {
        //获取 serialNumber
        final int serialNumber = IOUtil.readBEInt(mStreamIn);
        //获取class object id
        final ID classObjectId = IOUtil.readID(mStreamIn, mIdSize);
        final int stackTraceSerial = IOUtil.readBEInt(mStreamIn);
        final ID classNameStringId = IOUtil.readID(mStreamIn, mIdSize);
        hv.visitLoadClassRecord(serialNumber, classObjectId, stackTraceSerial, classNameStringId, timestamp, length);

//        MatrixLog.i(TAG, "acceptRecord acceptLoadClassRecord serialNumber=%d ,classObjectId=%s, stackTraceSerial=%d, classNameStringId=%s",
//                serialNumber, classObjectId.toString(), stackTraceSerial, classNameStringId.toString());
    }

    /**
     * 数据内容是 栈帧
     * 格式如下
     * ID：stack frame ID
     * ID：method name string ID
     * ID：method signature string ID
     * ID：source file name string ID
     * u4：class serial number
     * u4：ine number
     */
    private void acceptStackFrameRecord(int timestamp, long length, HprofVisitor hv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final ID methodNameId = IOUtil.readID(mStreamIn, mIdSize);
        final ID methodSignatureId = IOUtil.readID(mStreamIn, mIdSize);
        final ID sourceFileId = IOUtil.readID(mStreamIn, mIdSize);
        final int serial = IOUtil.readBEInt(mStreamIn);
        final int lineNumber = IOUtil.readBEInt(mStreamIn);
        hv.visitStackFrameRecord(id, methodNameId, methodSignatureId, sourceFileId, serial, lineNumber, timestamp, length);
//        MatrixLog.i(TAG, "acceptRecord acceptStackFrameRecord id=%s ,methodNameId=%s, methodSignatureId=%s, sourceFileId=%s, serial=%d, lineNumber=%d",
//                id.toString(), methodNameId.toString(), methodSignatureId.toString(), sourceFileId.toString(), serial, lineNumber);
    }

    /**
     * 数据内容是 栈 ，包含多个栈帧，也就是上面解析出来的栈帧id
     * 格式如下
     * u4：stack trace serial number
     * u4：thread serial number
     * u4：number of frames
     * [ID]*：series of stack frame ID's
     */
    private void acceptStackTraceRecord(int timestamp, long length, HprofVisitor hv) throws IOException {
        final int serialNumber = IOUtil.readBEInt(mStreamIn);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        final int numFrames = IOUtil.readBEInt(mStreamIn);
        final ID[] frameIds = new ID[numFrames];
        for (int i = 0; i < numFrames; ++i) {
            frameIds[i] = IOUtil.readID(mStreamIn, mIdSize);
        }
        hv.visitStackTraceRecord(serialNumber, threadSerialNumber, frameIds, timestamp, length);
//        MatrixLog.i(TAG, "acceptRecord acceptStackTraceRecord serialNumber=%d ,threadSerialNumber=%d, numFrames=%d ，frameIds=" + frameIds,
//                serialNumber, threadSerialNumber, numFrames);
    }

    /**
     * 包含了 length 个子Tag，每个子Tag是1byte
     * 子tag有如下类型，下面的代码也都是在解析这个tag,具体的格式查看 http://note.youdao.com/noteshare?id=3c71440b4ee1ec7e778de5700226fbf4&sub=AAA3125C0F8046369016C49732BD7AEC
     * ROOT_UNKNOWN = 0xff
     * ROOT_JNI_GLOBAL = 0x01
     * ROOT_JNI_LOCAL = 0x02
     * ROOT_JAVA_FRAME = 0x03
     * ROOT_NATIVE_STACK = 0x04
     * ROOT_STICKY_CLASS = 0x05
     * ROOT_THREAD_BLOCK = 0x06
     * ROOT_MONITOR_USED = 0x07
     * ROOT_THREAD_OBJECT = 0x08
     * CLASS_DUMP = 0x20
     * INSTANCE_DUMP = 0x21
     * OBJECT_ARRAY_DUMP = 0x22
     * PRIMITIVE_ARRAY_DUMP = 0x23：占据到80%以上
     * Android Hprof格式
     */
    private void acceptHeapDumpRecord(int tag, int timestamp, long length, HprofVisitor hv) throws IOException {
        //获取 HprofHeapDumpVisitor
        final HprofHeapDumpVisitor hdv = hv.visitHeapDumpRecord(tag, timestamp, length);
        if (hdv == null) {
            IOUtil.skip(mStreamIn, length);
            return;
        }
        while (length > 0) {
            //获取子tag
            final int heapDumpTag = mStreamIn.read();
            --length;
            switch (heapDumpTag) {
                case HprofConstants.HEAPDUMP_ROOT_UNKNOWN:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_JNI_GLOBAL:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    IOUtil.skip(mStreamIn, mIdSize);   //  ignored
                    length -= (mIdSize << 1);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_JNI_LOCAL:
                    length -= acceptJniLocal(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_JAVA_FRAME:
                    length -= acceptJavaFrame(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_NATIVE_STACK:
                    length -= acceptNativeStack(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_STICKY_CLASS:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_THREAD_BLOCK:
                    length -= acceptThreadBlock(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_MONITOR_USED:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_THREAD_OBJECT:
                    length -= acceptThreadObject(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_CLASS_DUMP:
                    length -= acceptClassDump(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_INSTANCE_DUMP:
                    length -= acceptInstanceDump(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_OBJECT_ARRAY_DUMP:
                    length -= acceptObjectArrayDump(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_PRIMITIVE_ARRAY_DUMP:
                    length -= acceptPrimitiveArrayDump(heapDumpTag, hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_PRIMITIVE_ARRAY_NODATA_DUMP:
                    length -= acceptPrimitiveArrayDump(heapDumpTag, hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_HEAP_DUMP_INFO:
                    length -= acceptHeapDumpInfo(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_INTERNED_STRING:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_FINALIZING:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_DEBUGGER:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_REFERENCE_CLEANUP:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_VM_INTERNAL:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                case HprofConstants.HEAPDUMP_ROOT_JNI_MONITOR:
                    length -= acceptJniMonitor(hdv);
                    break;
                case HprofConstants.HEAPDUMP_ROOT_UNREACHABLE:
                    hdv.visitHeapDumpBasicObj(heapDumpTag, IOUtil.readID(mStreamIn, mIdSize));
                    length -= mIdSize;
                    break;
                default:
                    throw new IllegalArgumentException(
                            "acceptHeapDumpRecord loop with unknown tag " + heapDumpTag
                                    + " with " + mStreamIn.available()
                                    + " bytes possibly remaining");
            }
        }
        hdv.visitEnd();
    }

    //访问一切不关心的 数据，直接读取数据长度就行了，不用解析出来
    private void acceptUnconcernedRecord(int tag, int timestamp, long length, HprofVisitor hv) throws IOException {
        final byte[] data = new byte[(int) length];
        IOUtil.readFully(mStreamIn, data, 0, length);
        hv.visitUnconcernedRecord(tag, timestamp, length, data);
    }

    private int acceptHeapDumpInfo(HprofHeapDumpVisitor hdv) throws IOException {
        final int heapId = IOUtil.readBEInt(mStreamIn);
        final ID heapNameId = IOUtil.readID(mStreamIn, mIdSize);
        hdv.visitHeapDumpInfo(heapId, heapNameId);
        return 4 + mIdSize;
    }

    /**
     *ID: object ID
     *u4: thread serial number
     *u4: frame number in stack trace (-1 for empty)
     */
    private int acceptJniLocal(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        final int stackFrameNumber = IOUtil.readBEInt(mStreamIn);
        hdv.visitHeapDumpJniLocal(id, threadSerialNumber, stackFrameNumber);
        return mIdSize + 4 + 4;
    }

    /**
     * ID：object ID
     * u4：thread serial number
     * u4：frame number in stack trace (-1 for empty)
     */
    private int acceptJavaFrame(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        final int stackFrameNumber = IOUtil.readBEInt(mStreamIn);
        hdv.visitHeapDumpJavaFrame(id, threadSerialNumber, stackFrameNumber);
        return mIdSize + 4 + 4;
    }

    /**
     * ID:object ID
     * u4:thread serial number
     */
    private int acceptNativeStack(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        hdv.visitHeapDumpNativeStack(id, threadSerialNumber);
        return mIdSize + 4;
    }

    /**
     * ID:object ID
     * u4:thread serial number
     */
    private int acceptThreadBlock(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        hdv.visitHeapDumpThreadBlock(id, threadSerialNumber);
        return mIdSize + 4;
    }

    /**
     * ID：thread object ID
     * u4：thread serial number
     * u4：stack trace serial number
     */
    private int acceptThreadObject(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        final int stackFrameNumber = IOUtil.readBEInt(mStreamIn);
        hdv.visitHeapDumpThreadObject(id, threadSerialNumber, stackFrameNumber);
        return mIdSize + 4 + 4;
    }

    /**
     * 这个格式太长了就不贴了 ,不过这里很重要
     *
     * 这里标识的是一个类对象 在内存中标识，包含了静态变量，成员变量，继承关系 等等信息
     */
    private int acceptClassDump(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int stackSerialNumber = IOUtil.readBEInt(mStreamIn);
        //父类 id
        final ID superClassId = IOUtil.readID(mStreamIn, mIdSize);
        //class loader id
        final ID classLoaderId = IOUtil.readID(mStreamIn, mIdSize);
        IOUtil.skip(mStreamIn, (mIdSize << 2));
        final int instanceSize = IOUtil.readBEInt(mStreamIn);

        int bytesRead = (7 * mIdSize) + 4 + 4;


        //  Skip over the constant pool  常量池直接跳过
        int numEntries = IOUtil.readBEShort(mStreamIn);
        bytesRead += 2;
        for (int i = 0; i < numEntries; ++i) {
            IOUtil.skip(mStreamIn, 2);
            bytesRead += 2 + skipValue();
        }

        //  Static fields Static field的个数
        numEntries = IOUtil.readBEShort(mStreamIn);
        Field[] staticFields = new Field[numEntries];
        bytesRead += 2;
        for (int i = 0; i < numEntries; ++i) {
            final ID nameId = IOUtil.readID(mStreamIn, mIdSize);
            final int typeId = mStreamIn.read();
            final Type type = Type.getType(typeId);
            if (type == null) {
                throw new IllegalStateException("accept class failed, lost type def of typeId: " + typeId);
            }
            final Object staticValue = IOUtil.readValue(mStreamIn, type, mIdSize);
            staticFields[i] = new Field(typeId, nameId, staticValue);
            bytesRead += mIdSize + 1 + type.getSize(mIdSize);
        }

        //  Instance fields  Instance fields的 个数
        numEntries = IOUtil.readBEShort(mStreamIn);
        final Field[] instanceFields = new Field[numEntries];
        bytesRead += 2;
        for (int i = 0; i < numEntries; i++) {
            //引用名称 ID
            final ID nameId = IOUtil.readID(mStreamIn, mIdSize);
            //引用类型 ID
            final int typeId = mStreamIn.read();
            instanceFields[i] = new Field(typeId, nameId, null);
            bytesRead += mIdSize + 1;
        }

        //上面会收集 这个类的所有信息，然后在调用这个方法
        hdv.visitHeapDumpClass(id, stackSerialNumber, superClassId, classLoaderId, instanceSize, staticFields, instanceFields);

        return bytesRead;
    }

    /**
     * 主要是描述一个对象
     *
     ID:object ID
     u4:stack trace serial number
     ID:class object ID
     u4:number of bytes that follow
     [value]*:instance field values (this class, followed by super class, etc)
     */
    private int acceptInstanceDump(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int stackId = IOUtil.readBEInt(mStreamIn);
        final ID typeId = IOUtil.readID(mStreamIn, mIdSize);
        final int remaining = IOUtil.readBEInt(mStreamIn);
        //instanceData 就是这个类在内存中的具体内容
        final byte[] instanceData = new byte[remaining];
        IOUtil.readFully(mStreamIn, instanceData, 0, remaining);
        hdv.visitHeapDumpInstance(id, stackId, typeId, instanceData);
        return mIdSize + 4 + mIdSize + 4 + remaining;
    }

    /**
     * ID:array object ID
     * u4:stack trace serial number
     * u4:number of elements
     * ID:array class object ID
     * [ID]*:elements
     */
    private int acceptObjectArrayDump(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int stackId = IOUtil.readBEInt(mStreamIn);
        final int numElements = IOUtil.readBEInt(mStreamIn);
        final ID typeId = IOUtil.readID(mStreamIn, mIdSize);
        final int remaining = numElements * mIdSize;
        final byte[] elements = new byte[remaining];
        IOUtil.readFully(mStreamIn, elements, 0, remaining);
        hdv.visitHeapDumpObjectArray(id, stackId, numElements, typeId, elements);
        return mIdSize + 4 + 4 + mIdSize + remaining;
    }

    /**
     * 对于分析内存泄漏引用链，这里的内容一般没有什么用，所以一般裁剪也是裁剪的这里的内容
     * ID:array object ID
     * u4:stack trace serial number
     * u4:number of elements
     * u1:element type (See Basic Type)
     * [u1]*:elements (packed array)
     */
    private int acceptPrimitiveArrayDump(int tag, HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int stackId = IOUtil.readBEInt(mStreamIn);
        final int numElements = IOUtil.readBEInt(mStreamIn);
        final int typeId = mStreamIn.read();
        final Type type = Type.getType(typeId);
        if (type == null) {
            throw new IllegalStateException("accept primitive array failed, lost type def of typeId: " + typeId);
        }
        final int remaining = numElements * type.getSize(mIdSize);
        final byte[] elements = new byte[remaining];
        IOUtil.readFully(mStreamIn, elements, 0, remaining);
        hdv.visitHeapDumpPrimitiveArray(tag, id, stackId, numElements, typeId, elements);
        return mIdSize + 4 + 4 + 1 + remaining;
    }

    private int acceptJniMonitor(HprofHeapDumpVisitor hdv) throws IOException {
        final ID id = IOUtil.readID(mStreamIn, mIdSize);
        final int threadSerialNumber = IOUtil.readBEInt(mStreamIn);
        final int stackDepth = IOUtil.readBEInt(mStreamIn);
        hdv.visitHeapDumpJniMonitor(id, threadSerialNumber, stackDepth);
        return mIdSize + 4 + 4;
    }

    private int skipValue() throws IOException {
        final int typeId = mStreamIn.read();
        final Type type = Type.getType(typeId);
        if (type == null) {
            throw new IllegalStateException("failure to skip type, cannot find type def of typeid: " + typeId);
        }
        final int size = type.getSize(mIdSize);
        IOUtil.skip(mStreamIn, size);
        return size + 1;
    }
}
