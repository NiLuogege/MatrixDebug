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

import com.tencent.matrix.resource.common.utils.DigestUtil;
import com.tencent.matrix.resource.hproflib.model.Field;
import com.tencent.matrix.resource.hproflib.model.ID;
import com.tencent.matrix.resource.hproflib.model.Type;
import com.tencent.matrix.resource.hproflib.utils.IOUtil;
import com.tencent.matrix.util.MatrixLog;
import com.tencent.matrix.util.MatrixUtil;
import com.tencent.tinker.ziputils.ziputil.TinkerZipEntry;
import com.tencent.tinker.ziputils.ziputil.TinkerZipFile;
import com.tencent.tinker.ziputils.ziputil.TinkerZipOutputStream;
import com.tencent.tinker.ziputils.ziputil.TinkerZipUtil;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * Created by tangyinsheng on 2017/6/29.
 */

public class HprofBufferShrinker {
    public static final String TAG = "Matrix.HprofBufferShrinker";

    private static final String PROPERTY_NAME = "extra.info";

    private final Set<ID>         mBmpBufferIds                   = new HashSet<>();
    //保存原始数组数据
    private final Map<ID, byte[]> mBufferIdToElementDataMap       = new HashMap<>();
    private final Map<ID, ID>     mBmpBufferIdToDeduplicatedIdMap = new HashMap<>();
    private final Set<ID>         mStringValueIds                 = new HashSet<>();

    private ID mBitmapClassNameStringId    = null;
    //Bitmap class 对象 id
    private ID mBmpClassId                 = null;
    //代表 mBuffer 字段
    private ID mMBufferFieldNameStringId   = null;
    //代表 mRecycled 字段
    private ID mMRecycledFieldNameStringId = null;

    private ID mStringClassNameStringId = null;
    //String class 对象 的id
    private ID mStringClassId           = null;
    private ID mValueFieldNameStringId  = null;

    private int     mIdSize                    = 0;
    private ID      mNullBufferId              = null;
    //Bitmap 类中成员变量集合
    private Field[] mBmpClassInstanceFields    = null;
    //String 类中成员变量集合
    private Field[] mStringClassInstanceFields = null;

    public static boolean addExtraInfo(File shrinkResultFile, Properties properties) {
        if (shrinkResultFile == null || !shrinkResultFile.exists()) {
            return false;
        }
        if (properties.isEmpty()) {
            return true;
        }
        long start = System.currentTimeMillis();
        OutputStream propertiesOutputStream = null;
        File propertiesFile = new File(shrinkResultFile.getParentFile(), PROPERTY_NAME);
        File tempFile = new File(shrinkResultFile.getAbsolutePath() + "_temp");

        try {
            propertiesOutputStream = new BufferedOutputStream(new FileOutputStream(propertiesFile, false));
            properties.store(propertiesOutputStream, null);
        } catch (Throwable throwable) {
            MatrixLog.e(TAG, "save property error:" + throwable);
            return false;
        } finally {
            MatrixUtil.closeQuietly(propertiesOutputStream);
        }

        TinkerZipOutputStream out = null;
        TinkerZipFile zipFile = null;
        try {

            out = new TinkerZipOutputStream(new BufferedOutputStream(new FileOutputStream(tempFile)));

            zipFile = new TinkerZipFile(shrinkResultFile);
            final Enumeration<? extends TinkerZipEntry> entries = zipFile.entries();

            while (entries.hasMoreElements()) {
                TinkerZipEntry zipEntry = entries.nextElement();
                if (zipEntry == null) {
                    throw new RuntimeException("zipEntry is null when get from oldApk");
                }
                String name = zipEntry.getName();
                if (name.contains("../")) {
                    continue;
                }
                TinkerZipUtil.extractTinkerEntry(zipFile, zipEntry, out);
            }
            Long crc = getCRC32(propertiesFile);
            if (crc == null) {
                MatrixLog.e(TAG, "new crc is null");
                return false;
            }
            TinkerZipEntry propertyEntry = new TinkerZipEntry(propertiesFile.getName());
            // add property file
            TinkerZipUtil.extractLargeModifyFile(propertyEntry, propertiesFile, crc, out);
        } catch (IOException e) {
            MatrixLog.e(TAG, "zip property error:" + e);
            return false;
        } finally {
            MatrixUtil.closeQuietly(zipFile);
            MatrixUtil.closeQuietly(out);
            propertiesFile.delete();
        }

        shrinkResultFile.delete();
        if (!tempFile.renameTo(shrinkResultFile)) {
            MatrixLog.e(TAG, "rename error");
            return false;
        }

        MatrixLog.i(TAG, "addExtraInfo end, path: %s, cost time: %d", shrinkResultFile.getAbsolutePath(), (System.currentTimeMillis() - start));
        return true;
    }

    private static Long getCRC32(File file) {
        CRC32 crc32 = new CRC32();
        // MessageDigest.get
        FileInputStream fileInputStream = null;
        try {
            fileInputStream = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int length;
            while ((length = fileInputStream.read(buffer)) != -1) {
                crc32.update(buffer, 0, length);
            }
            return crc32.getValue();
        } catch (IOException e) {
            return null;
        } finally {
            MatrixUtil.closeQuietly(fileInputStream);
        }
    }

    /**
     * 进行裁剪
     * @param hprofIn 源文件
     * @param hprofOut 裁剪后的文件
     * @throws IOException
     */
    public void shrink(File hprofIn, File hprofOut) throws IOException {
        FileInputStream is = null;
        OutputStream os = null;
        try {
            is = new FileInputStream(hprofIn);
            os = new BufferedOutputStream(new FileOutputStream(hprofOut));

            //通过访问者模式进行裁剪 类似于 AMS
            //下面通过三个不同的 访问者 进行不同的操作
            final HprofReader reader = new HprofReader(new BufferedInputStream(is));
            //这个visiter主要是收集信息，收集 Bitmap和String对象 并记录他们的成员变量 为下面做分析做准备
            reader.accept(new HprofInfoCollectVisitor());
            // Reset. 对流进行重置
            is.getChannel().position(0);
            //这里主要是收集 需要对比的Bitmap的 mBuffer byte数组 id ，和 String 的 value char数组 id
            reader.accept(new HprofKeptBufferCollectVisitor());
            // Reset. 对流进行重置
            is.getChannel().position(0);
            //这里是进行裁剪，裁剪的是 PRIMITIVE ARRAY DUMP 这个区域，里面只保留String类型数据 和 为重复的图片数据。像一些int数组，Boolean数组都会被裁掉
            //这里是边读别写
            reader.accept(new HprofBufferShrinkVisitor(new HprofWriter(os)));
        } finally {
            if (os != null) {
                try {
                    os.close();
                } catch (Throwable thr) {
                    // Ignored.
                }
            }
            if (is != null) {
                try {
                    is.close();
                } catch (Throwable thr) {
                    // Ignored.
                }
            }
        }
    }

    /**
     * 这个visiter主要是收集信息，收集 Bitmap和String对象 并记录他们的成员变量 为下面做分析做准备
     */
    private class HprofInfoCollectVisitor extends HprofVisitor {

        HprofInfoCollectVisitor() {
            super(null);
        }

        //访问到了 header
        @Override
        public void visitHeader(String text, int idSize, long timestamp) {
            mIdSize = idSize;
            mNullBufferId = ID.createNullID(idSize);
        }

        //访问到了 Record
        @Override
        public void visitStringRecord(ID id, String text, int timestamp, long length) {
            //记录 android.graphics.Bitmap 这个字符串的 id
            if (mBitmapClassNameStringId == null && "android.graphics.Bitmap".equals(text)) {
                mBitmapClassNameStringId = id;
            //    记录 mBuffer 这个字符串的 id
            } else if (mMBufferFieldNameStringId == null && "mBuffer".equals(text)) {
                mMBufferFieldNameStringId = id;
             //记录 mRecycled 这个字符串的 id
            } else if (mMRecycledFieldNameStringId == null && "mRecycled".equals(text)) {
                mMRecycledFieldNameStringId = id;
                //记录 java.lang.String 这个字符串的 id
            } else if (mStringClassNameStringId == null && "java.lang.String".equals(text)) {
                mStringClassNameStringId = id;
                //记录value 这个字符串的 id
            } else if (mValueFieldNameStringId == null && "value".equals(text)) {
                mValueFieldNameStringId = id;
            }
        }

        @Override
        public void visitLoadClassRecord(int serialNumber, ID classObjectId, int stackTraceSerial, ID classNameStringId, int timestamp, long length) {
            //通过上面记录的 mBitmapClassNameStringId 定位到 Bitmap class对象的 id ,并记录下来 。这里就能感觉到字节码文件对象内存中只有一份
            if (mBmpClassId == null && mBitmapClassNameStringId != null && mBitmapClassNameStringId.equals(classNameStringId)) {
                mBmpClassId = classObjectId;
            }
            //和Bitmap同理 记录 String class 对象 的id
            else if (mStringClassId == null && mStringClassNameStringId != null && mStringClassNameStringId.equals(classNameStringId)) {
                mStringClassId = classObjectId;
            }
        }

        @Override
        public HprofHeapDumpVisitor visitHeapDumpRecord(int tag, int timestamp, long length) {
            return new HprofHeapDumpVisitor(null) {
                @Override
                public void visitHeapDumpClass(ID id, int stackSerialNumber, ID superClassId, ID classLoaderId, int instanceSize, Field[] staticFields, Field[] instanceFields) {
                    //这里是找到 Bitmap对象 ，然后将它的成员变量记录起来, 可以看到这里mBmpClassInstanceFields只赋值一次也就是 我们只需要一份
                    if (mBmpClassInstanceFields == null && mBmpClassId != null && mBmpClassId.equals(id)) {
                        mBmpClassInstanceFields = instanceFields;
                    }
                    //这里是找到 String对象 ，然后将它的成员变量记录起来, 可以看到这里 mStringClassInstanceFields 只赋值一次也就是 我们只需要一份
                    else if (mStringClassInstanceFields == null && mStringClassId != null && mStringClassId.equals(id)) {
                        mStringClassInstanceFields = instanceFields;
                    }
                }
            };
        }
    }

    /**
     * 这里主要是收集 需要对比的Bitmap的 mBuffer byte数组 id ，和 String 的 value char数组 id
     * 并将重复Bitmap保存到 mBmpBufferIdToDeduplicatedIdMap 中
     */
    private class HprofKeptBufferCollectVisitor extends HprofVisitor {

        HprofKeptBufferCollectVisitor() {
            super(null);
        }

        @Override
        public HprofHeapDumpVisitor visitHeapDumpRecord(int tag, int timestamp, long length) {
            return new HprofHeapDumpVisitor(null) {

                @Override
                public void visitHeapDumpInstance(ID id, int stackId, ID typeId, byte[] instanceData) {
                    try {
                        //当访问到的是一个 Bitmap对象
                        if (mBmpClassId != null && mBmpClassId.equals(typeId)) {
                            ID bufferId = null;
                            Boolean isRecycled = null;
                            //将这个类的内容从 byte[] 转为 流
                            final ByteArrayInputStream bais = new ByteArrayInputStream(instanceData);
                            for (Field field : mBmpClassInstanceFields) {
                                final ID fieldNameStringId = field.nameId;
                                final Type fieldType = Type.getType(field.typeId);
                                if (fieldType == null) {
                                    throw new IllegalStateException("visit bmp instance failed, lost type def of typeId: " + field.typeId);
                                }
                                if (mMBufferFieldNameStringId.equals(fieldNameStringId)) {
                                    //获取mBuffer数组的 id
                                    bufferId = (ID) IOUtil.readValue(bais, fieldType, mIdSize);
                                } else if (mMRecycledFieldNameStringId.equals(fieldNameStringId)) {
                                    //获取 isRecycled 的值
                                    isRecycled = (Boolean) IOUtil.readValue(bais, fieldType, mIdSize);
                                } else if (bufferId == null || isRecycled == null) {
                                    IOUtil.skipValue(bais, fieldType, mIdSize);
                                } else {
                                    break;
                                }
                            }
                            bais.close();
                            final boolean reguardAsNotRecycledBmp = (isRecycled == null || !isRecycled);
                            if (bufferId != null && reguardAsNotRecycledBmp && !bufferId.equals(mNullBufferId)) {
                                //将需要对比的 buffer 数组手机起来，也就是需要对比这些Bitmap
                                mBmpBufferIds.add(bufferId);
                            }
                        }
                        //当访问到的是一个 String 对象
                        else if (mStringClassId != null && mStringClassId.equals(typeId)) {
                            ID strValueId = null;
                            final ByteArrayInputStream bais = new ByteArrayInputStream(instanceData);
                            for (Field field : mStringClassInstanceFields) {
                                final ID fieldNameStringId = field.nameId;
                                final Type fieldType = Type.getType(field.typeId);
                                if (fieldType == null) {
                                    throw new IllegalStateException("visit string instance failed, lost type def of typeId: " + field.typeId);
                                }
                                if (mValueFieldNameStringId.equals(fieldNameStringId)) {
                                    //记录String对象的value成员变量
                                    strValueId = (ID) IOUtil.readValue(bais, fieldType, mIdSize);
                                } else if (strValueId == null) {
                                    IOUtil.skipValue(bais, fieldType, mIdSize);
                                } else {
                                    break;
                                }
                            }
                            bais.close();
                            if (strValueId != null && !strValueId.equals(mNullBufferId)) {
                                //将String保存起来
                                mStringValueIds.add(strValueId);
                            }
                        }
                    } catch (Throwable thr) {
                        throw new RuntimeException(thr);
                    }
                }

                @Override
                public void visitHeapDumpPrimitiveArray(int tag, ID id, int stackId, int numElements, int typeId, byte[] elements) {
                    //保存原始数组数据 ,这里面应该会有Bitmap的 mBuffer byte数组 和 String的value char数组
                    mBufferIdToElementDataMap.put(id, elements);
                }
            };
        }

        @Override
        public void visitEnd() {
            //遍历原始数组
            final Set<Map.Entry<ID, byte[]>> idDataSet = mBufferIdToElementDataMap.entrySet();
            final Map<String, ID> duplicateBufferFilterMap = new HashMap<>();
            for (Map.Entry<ID, byte[]> idDataPair : idDataSet) {
                final ID bufferId = idDataPair.getKey();
                final byte[] elementData = idDataPair.getValue();
                //说明要保存这个数据 跳过
                if (!mBmpBufferIds.contains(bufferId)) {
                    // Discard non-bitmap buffer.
                    continue;
                }
                //对数据进行MD5 ，如果没保存过就添加到 duplicateBufferFilterMap 中
                //如果保持过就说明有重复的图片了
                final String buffMd5 = DigestUtil.getMD5String(elementData);
                final ID mergedBufferId = duplicateBufferFilterMap.get(buffMd5);
                if (mergedBufferId == null) {
                    duplicateBufferFilterMap.put(buffMd5, bufferId);
                } else {
                    mBmpBufferIdToDeduplicatedIdMap.put(mergedBufferId, mergedBufferId);
                    //这里就是有重复的图片了 ，将重复的数据替换成前一个
                    mBmpBufferIdToDeduplicatedIdMap.put(bufferId, mergedBufferId);
                }
            }
            // Save memory cost.
            mBufferIdToElementDataMap.clear();
        }
    }

    private class HprofBufferShrinkVisitor extends HprofVisitor {

        HprofBufferShrinkVisitor(HprofWriter hprofWriter) {
            super(hprofWriter);
        }

        @Override
        public HprofHeapDumpVisitor visitHeapDumpRecord(int tag, int timestamp, long length) {
            return new HprofHeapDumpVisitor(super.visitHeapDumpRecord(tag, timestamp, length)) {
                //当读到对象是会走这里
                @Override
                public void visitHeapDumpInstance(ID id, int stackId, ID typeId, byte[] instanceData) {
                    try {
                        //如果是bitmap对象
                        if (typeId.equals(mBmpClassId)) {
                            ID bufferId = null;
                            int bufferIdPos = 0;
                            final ByteArrayInputStream bais = new ByteArrayInputStream(instanceData);
                            for (Field field : mBmpClassInstanceFields) {
                                final ID fieldNameStringId = field.nameId;
                                final Type fieldType = Type.getType(field.typeId);
                                if (fieldType == null) {
                                    throw new IllegalStateException("visit instance failed, lost type def of typeId: " + field.typeId);
                                }
                                if (mMBufferFieldNameStringId.equals(fieldNameStringId)) {
                                    bufferId = (ID) IOUtil.readValue(bais, fieldType, mIdSize);
                                    break;
                                } else {
                                    bufferIdPos += IOUtil.skipValue(bais, fieldType, mIdSize);
                                }
                            }
                            //这里就在合并数据
                            if (bufferId != null) {
                                final ID deduplicatedId = mBmpBufferIdToDeduplicatedIdMap.get(bufferId);
                                if (deduplicatedId != null && !bufferId.equals(deduplicatedId) && !bufferId.equals(mNullBufferId)) {
                                    modifyIdInBuffer(instanceData, bufferIdPos, deduplicatedId);
                                }
                            }
                        }
                    } catch (Throwable thr) {
                        throw new RuntimeException(thr);
                    }
                    super.visitHeapDumpInstance(id, stackId, typeId, instanceData);
                }

                private void modifyIdInBuffer(byte[] buf, int off, ID newId) {
                    final ByteBuffer bBuf = ByteBuffer.wrap(buf);
                    bBuf.position(off);
                    bBuf.put(newId.getBytes());
                }

                /**
                 * 这里就是在裁剪，只保留String类型数据 和 为重复的图片数据。
                 * 像一些int数组，Boolean数组都会被裁掉
                 */
                @Override
                public void visitHeapDumpPrimitiveArray(int tag, ID id, int stackId, int numElements, int typeId, byte[] elements) {
                    final ID deduplicatedID = mBmpBufferIdToDeduplicatedIdMap.get(id);
                    // Discard non-bitmap or duplicated bitmap buffer but keep reference key.
                    // 为null的情况：不是buffer数据；或者是独一份的buffer数据
                    // ID不相等的情况：buffer A与buffer B md5一致，但保留起来的是A，这里id却为B，因此B应该要被替换为A，B的数据要被删除
                    if (deduplicatedID == null || !id.equals(deduplicatedID)) {
                        // 该id不是String value的id，也就是说字符串的文字应该得到保留
                        if (!mStringValueIds.contains(id)) {
                            return;
                        }
                    }
                    super.visitHeapDumpPrimitiveArray(tag, id, stackId, numElements, typeId, elements);
                }
            };
        }
    }
}
