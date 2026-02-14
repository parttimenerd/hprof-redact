/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.bechberger.hprof.HprofConstants.*;

public final class HprofFilter {
    private HprofFilter() {}

    /**
     * HPROF header:
     *   [u1]* "JAVA PROFILE 1.0.2\0" (null-terminated)
     *   u4    id size
     *   u8    timestamp (ms since epoch)
     * Records:
     *   u1    tag
     *   u4    time (microseconds since header timestamp)
     *   u4    length (bytes of body)
     *   [u1]* body
     */
    public static void filter(Path inputPath, OutputStream output, HprofTransformer transformer) throws IOException {
        Map<Long, HprofClassInfo> classInfos = new HashMap<>();
        Map<Long, NameKind> nameKinds = new HashMap<>();

        try (InputStream input = HprofIo.openInputStream(inputPath)) {
            HprofDataInput in = new HprofDataInput(input);
            HprofHeader header = readHeader(in);
            scanForMetadata(in, header.idSize(), classInfos, nameKinds);
        }

        try (InputStream input = HprofIo.openInputStream(inputPath)) {
            HprofDataInput in = new HprofDataInput(input);
            HprofDataOutput out = new HprofDataOutput(output);
            HprofHeader header = readHeader(in);

            out.writeBytes(header.bytes());
            out.writeU4(header.idSize());
            out.writeU8(header.timestamp());

            in.setIdSize(header.idSize());
            out.setIdSize(header.idSize());

            Map<Long, List<HprofType>> flattenedTypesCache = new HashMap<>();
            writeRecords(in, out, transformer, header.idSize(), classInfos, flattenedTypesCache, nameKinds);
            out.flush();
        }
    }

    private static void writeRecords(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                     int idSize, Map<Long, HprofClassInfo> classInfos,
                                     Map<Long, List<HprofType>> flattenedTypesCache,
                                     Map<Long, NameKind> nameKinds) throws IOException {
        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            long time = in.readU4();
            long length = in.readU4();

            switch (tag) {
                case HPROF_UTF8 -> handleUtf8Record(in, out, transformer, time, length, idSize, nameKinds);
                case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT -> {
                    writeRecordHeader(out, tag, time, length);
                    handleHeapDumpSegment(in, out, transformer, length, idSize, classInfos, flattenedTypesCache, nameKinds);
                }
                case HPROF_LOAD_CLASS -> handleLoadClass(in, out, time, length, idSize, nameKinds);
                case HPROF_START_THREAD -> handleStartThread(in, out, time, length, idSize, nameKinds);
                case HPROF_FRAME -> handleFrame(in, out, time, length, idSize, nameKinds);
                default -> {
                    writeRecordHeader(out, tag, time, length);
                    copyBytes(in, out, length);
                }
            }
        }
    }

    private static HprofHeader readHeader(HprofDataInput in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        while (true) {
            int b = in.readU1();
            buffer.write(b);
            if (b == 0) {
                break;
            }
        }
        int idSize = (int) in.readU4();
        long timestamp = in.readU8();
        return new HprofHeader(buffer.toByteArray(), idSize, timestamp);
    }

    private static void scanForMetadata(HprofDataInput in, int idSize,
                                        Map<Long, HprofClassInfo> classInfos,
                                        Map<Long, NameKind> nameKinds) throws IOException {
        in.setIdSize(idSize);
        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            in.readU4();
            long length = in.readU4();

            switch (tag) {
                case HPROF_LOAD_CLASS -> scanLoadClass(in, idSize, nameKinds);
                case HPROF_START_THREAD -> scanStartThread(in, idSize, nameKinds);
                case HPROF_FRAME -> scanFrame(in, idSize, nameKinds);
                case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT -> scanHeapDumpSegment(in, length, idSize, classInfos, nameKinds);
                default -> skipFully(in, length);
            }
        }
    }

    private static void scanLoadClass(HprofDataInput in, int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        skipFully(in, 4);
        skipFully(in, idSize);
        skipFully(in, 4);
        long nameId = in.readId();
        nameKinds.putIfAbsent(nameId, NameKind.CLASS_NAME);
    }

    private static void scanStartThread(HprofDataInput in, int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        skipFully(in, 4);
        skipFully(in, idSize);
        skipFully(in, 4);
        long threadNameId = in.readId();
        long threadGroupNameId = in.readId();
        long threadGroupParentNameId = in.readId();
        nameKinds.putIfAbsent(threadNameId, NameKind.THREAD_NAME);
        nameKinds.putIfAbsent(threadGroupNameId, NameKind.THREAD_GROUP_NAME);
        nameKinds.putIfAbsent(threadGroupParentNameId, NameKind.THREAD_GROUP_PARENT_NAME);
    }

    private static void scanFrame(HprofDataInput in, int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        skipFully(in, idSize); // frameId
        long methodNameId = in.readId();
        long methodSigId = in.readId();
        long sourceFileNameId = in.readId();
        skipFully(in, 4 + 4); // class serial, line number
        nameKinds.putIfAbsent(methodNameId, NameKind.METHOD_NAME);
        nameKinds.putIfAbsent(methodSigId, NameKind.METHOD_SIGNATURE);
        nameKinds.putIfAbsent(sourceFileNameId, NameKind.SOURCE_FILE_NAME);
    }

    private static void scanHeapDumpSegment(HprofDataInput in, long length, int idSize,
                                            Map<Long, HprofClassInfo> classInfos,
                                            Map<Long, NameKind> nameKinds) throws IOException {
        LimitedInputStream limited = new LimitedInputStream(in.rawStream(), length);
        HprofDataInput segmentIn = new HprofDataInput(limited);
        segmentIn.setIdSize(idSize);

        while (limited.remaining() > 0) {
            int subTag = segmentIn.readU1();
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN -> skipFully(segmentIn, idSize);
                case HPROF_GC_ROOT_JNI_GLOBAL -> skipFully(segmentIn, idSize + idSize);
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME -> skipFully(segmentIn, idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> skipFully(segmentIn, idSize + 4);
                case HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> skipFully(segmentIn, idSize);
                case HPROF_GC_ROOT_THREAD_OBJ -> skipFully(segmentIn, idSize + 4 + 4);
                case HPROF_GC_CLASS_DUMP -> scanClassDump(segmentIn, idSize, classInfos, nameKinds);
                case HPROF_GC_INSTANCE_DUMP -> skipInstanceDump(segmentIn, idSize);
                case HPROF_GC_OBJ_ARRAY_DUMP -> skipObjectArrayDump(segmentIn, idSize);
                case HPROF_GC_PRIM_ARRAY_DUMP -> skipPrimitiveArrayDump(segmentIn, idSize);
                default -> throw new IOException("Unsupported heap dump subrecord tag: 0x" + Integer.toHexString(subTag));
            }
        }

        if (limited.remaining() != 0) {
            throw new IOException("Heap dump segment length mismatch: " + limited.remaining());
        }
    }

    private static void scanClassDump(HprofDataInput in, int idSize,
                                      Map<Long, HprofClassInfo> classInfos,
                                      Map<Long, NameKind> nameKinds) throws IOException {
        long classId = in.readId();
        skipFully(in, 4);
        long superClassId = in.readId();
        skipFully(in, idSize * 5);
        skipFully(in, 4); // instance size

        int constantPoolSize = in.readU2();
        for (int i = 0; i < constantPoolSize; i++) {
            skipFully(in, 2);
            HprofType type = HprofType.fromCode(in.readU1());
            skipValueByType(in, type, idSize);
        }

        int staticFieldCount = in.readU2();
        for (int i = 0; i < staticFieldCount; i++) {
            long nameId = in.readId();
            HprofType type = HprofType.fromCode(in.readU1());
            skipValueByType(in, type, idSize);
            nameKinds.putIfAbsent(nameId, NameKind.FIELD_NAME);
        }

        int instanceFieldCount = in.readU2();
        List<HprofClassInfo.FieldDef> instanceFieldDefs = new ArrayList<>(instanceFieldCount);
        for (int i = 0; i < instanceFieldCount; i++) {
            long nameId = in.readId();
            HprofType type = HprofType.fromCode(in.readU1());
            instanceFieldDefs.add(new HprofClassInfo.FieldDef(nameId, type));
            nameKinds.putIfAbsent(nameId, NameKind.FIELD_NAME);
        }

        classInfos.put(classId, new HprofClassInfo(classId, superClassId, instanceFieldDefs));
    }

    private static void skipInstanceDump(HprofDataInput in, int idSize) throws IOException {
        skipFully(in, idSize + 4 + idSize);
        long dataLength = in.readU4();
        skipFully(in, dataLength);
    }

    private static void skipObjectArrayDump(HprofDataInput in, int idSize) throws IOException {
        skipFully(in, idSize + 4);
        long numElements = in.readU4();
        skipFully(in, idSize);
        skipFully(in, numElements * idSize);
    }

    private static void skipPrimitiveArrayDump(HprofDataInput in, int idSize) throws IOException {
        skipFully(in, idSize + 4);
        long numElements = in.readU4();
        HprofType elementType = HprofType.fromCode(in.readU1());
        long elementSize = sizeForType(elementType, idSize);
        skipFully(in, numElements * elementSize);
    }

    private static void writeRecordHeader(HprofDataOutput out, int tag, long time, long length) throws IOException {
        out.writeU1(tag);
        out.writeU4(time);
        out.writeU4(length);
    }

    /**
     * HPROF_UTF8:
     *   id   nameId
     *   [u1]* UTF-8 bytes (no trailing zero)
     */
    private static void handleUtf8Record(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                         long time, long length, int idSize,
                                         Map<Long, NameKind> nameKinds) throws IOException {
        long id = in.readId();
        long bytesLength = length - idSize;
        if (bytesLength < 0 || bytesLength > Integer.MAX_VALUE) {
            throw new IOException("Invalid UTF8 length: " + length);
        }
        byte[] data = new byte[(int) bytesLength];
        in.readFully(data);

        final String original;
        try {
            // HotSpot emits SymbolTable strings in modified UTF-8 (MUTF-8).
            original = ModifiedUtf8.decode(data);
        } catch (IllegalArgumentException ex) {
            // If we can't decode, safest is to preserve bytes exactly.
            out.writeU1(HPROF_UTF8);
            out.writeU4(time);
            out.writeU4(length);
            out.writeId(id);
            out.writeBytes(data);
            return;
        }

        String transformed = transformName(original, transformer, nameKinds.get(id));
        if (transformed == null || transformed.equals(original)) {
            // Preserve original bytes (and therefore size) when not changing the string.
            out.writeU1(HPROF_UTF8);
            out.writeU4(time);
            out.writeU4(length);
            out.writeId(id);
            out.writeBytes(data);
            return;
        }

        byte[] outBytes = ModifiedUtf8.encode(transformed);
        long newLength = idSize + outBytes.length;
        if (newLength > 0xFFFF_FFFFL) {
            throw new IOException("Transformed UTF8 length too large: " + newLength);
        }

        out.writeU1(HPROF_UTF8);
        out.writeU4(time);
        out.writeU4(newLength);
        out.writeId(id);
        out.writeBytes(outBytes);
    }

    /**
     * HPROF_HEAP_DUMP(_SEGMENT):
     *   [u1]* heap sub-records, each prefixed with sub-tag.
     */
    private static void handleHeapDumpSegment(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                              long length, int idSize,
                                              Map<Long, HprofClassInfo> classInfos,
                                              Map<Long, List<HprofType>> flattenedTypesCache,
                                              Map<Long, NameKind> nameKinds) throws IOException {
        LimitedInputStream limited = new LimitedInputStream(in.rawStream(), length);
        HprofDataInput segmentIn = new HprofDataInput(limited);
        segmentIn.setIdSize(idSize);

        while (limited.remaining() > 0) {
            int subTag = segmentIn.readU1();
            out.writeU1(subTag);
            switch (subTag) {
                // Root records (fixed-size payloads)
                case HPROF_GC_ROOT_UNKNOWN -> copyBytes(segmentIn, out, idSize);
                case HPROF_GC_ROOT_JNI_GLOBAL -> copyBytes(segmentIn, out, idSize + idSize);
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME -> copyBytes(segmentIn, out, idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> copyBytes(segmentIn, out, idSize + 4);
                case HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> copyBytes(segmentIn, out, idSize);
                case HPROF_GC_ROOT_THREAD_OBJ -> copyBytes(segmentIn, out, idSize + 4 + 4);
                // Typed heap records
                case HPROF_GC_CLASS_DUMP -> handleClassDump(segmentIn, out, transformer, idSize, classInfos, flattenedTypesCache, nameKinds);
                case HPROF_GC_INSTANCE_DUMP -> handleInstanceDump(segmentIn, out, transformer, idSize, classInfos, flattenedTypesCache);
                case HPROF_GC_OBJ_ARRAY_DUMP -> handleObjectArrayDump(segmentIn, out, idSize);
                case HPROF_GC_PRIM_ARRAY_DUMP -> handlePrimitiveArrayDump(segmentIn, out, transformer, idSize);
                default -> throw new IOException("Unsupported heap dump subrecord tag: 0x" + Integer.toHexString(subTag));
            }
        }

        if (limited.remaining() != 0) {
            throw new IOException("Heap dump segment length mismatch: " + limited.remaining());
        }
    }

    /**
     * HPROF_GC_CLASS_DUMP:
     *   id  classId
     *   u4  stackTraceSerial
     *   id  superClassId
     *   id  classLoaderId
     *   id  signersId
     *   id  protectionDomainId
     *   id  reserved1
     *   id  reserved2
     *   u4  instanceSize
     *   u2  constantPoolSize
     *   [constantPoolSize]* (u2 index, u1 type, value)
     *   u2  staticFieldCount
     *   [staticFieldCount]* (id nameId, u1 type, value)
     *   u2  instanceFieldCount
     *   [instanceFieldCount]* (id nameId, u1 type)
     */
    private static void handleClassDump(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                        int idSize, Map<Long, HprofClassInfo> classInfos,
                                        Map<Long, List<HprofType>> flattenedTypesCache,
                                        Map<Long, NameKind> nameKinds) throws IOException {
        long classId = in.readId();
        long stackTraceSerial = in.readU4();
        long superClassId = in.readId();
        long classLoaderId = in.readId();
        long signersId = in.readId();
        long protectionDomainId = in.readId();
        long reserved1 = in.readId();
        long reserved2 = in.readId();
        long instanceSize = in.readU4();

        out.writeId(classId);
        out.writeU4(stackTraceSerial);
        out.writeId(superClassId);
        out.writeId(classLoaderId);
        out.writeId(signersId);
        out.writeId(protectionDomainId);
        out.writeId(reserved1);
        out.writeId(reserved2);
        out.writeU4(instanceSize);

        int constantPoolSize = in.readU2();
        out.writeU2(constantPoolSize);
        for (int i = 0; i < constantPoolSize; i++) {
            int index = in.readU2();
            HprofType type = HprofType.fromCode(in.readU1());
            out.writeU2(index);
            out.writeU1(type.code());
            writeValueByType(in, out, transformer, type, idSize);
        }

        int staticFieldCount = in.readU2();
        out.writeU2(staticFieldCount);
        for (int i = 0; i < staticFieldCount; i++) {
            long nameId = in.readId();
            HprofType type = HprofType.fromCode(in.readU1());
            out.writeId(nameId);
            out.writeU1(type.code());
            writeValueByType(in, out, transformer, type, idSize);
            nameKinds.putIfAbsent(nameId, NameKind.FIELD_NAME);
        }

        int instanceFieldCount = in.readU2();
        out.writeU2(instanceFieldCount);
        List<HprofClassInfo.FieldDef> instanceFieldDefs = new ArrayList<>(instanceFieldCount);
        for (int i = 0; i < instanceFieldCount; i++) {
            long nameId = in.readId();
            HprofType type = HprofType.fromCode(in.readU1());
            out.writeId(nameId);
            out.writeU1(type.code());
            instanceFieldDefs.add(new HprofClassInfo.FieldDef(nameId, type));
            nameKinds.putIfAbsent(nameId, NameKind.FIELD_NAME);
        }

        classInfos.put(classId, new HprofClassInfo(classId, superClassId, instanceFieldDefs));
        flattenedTypesCache.remove(classId);
    }

    /**
     * HPROF_GC_INSTANCE_DUMP:
     *   id  objectId
     *   u4  stackTraceSerial
     *   id  classId
     *   u4  dataLength
     *   [u1]* instance data
     */
    private static void handleInstanceDump(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                           int idSize, Map<Long, HprofClassInfo> classInfos,
                                           Map<Long, List<HprofType>> flattenedTypesCache) throws IOException {
        long objectId = in.readId();
        long stackTraceSerial = in.readU4();
        long classId = in.readId();
        long dataLength = in.readU4();

        out.writeId(objectId);
        out.writeU4(stackTraceSerial);
        out.writeId(classId);
        out.writeU4(dataLength);

        List<HprofType> flattened = flattenedTypes(classId, classInfos, flattenedTypesCache);
        if (flattened == null) {
            copyBytes(in, out, dataLength);
            return;
        }

        long expected = 0;
        for (HprofType type : flattened) {
            expected += sizeForType(type, idSize);
        }
        if (expected != dataLength) {
            throw new IOException("Instance dump length mismatch: expected " + expected + " but was " + dataLength);
        }

        for (HprofType type : flattened) {
            writeValueByType(in, out, transformer, type, idSize);
        }
    }

    /**
     * HPROF_GC_OBJ_ARRAY_DUMP:
     *   id  arrayId
     *   u4  stackTraceSerial
     *   u4  numElements
     *   id  arrayClassId
     *   [id]* elementIds
     */
    private static void handleObjectArrayDump(HprofDataInput in, HprofDataOutput out, int idSize) throws IOException {
        long arrayId = in.readId();
        long stackTraceSerial = in.readU4();
        long numElements = in.readU4();
        long arrayClassId = in.readId();

        out.writeId(arrayId);
        out.writeU4(stackTraceSerial);
        out.writeU4(numElements);
        out.writeId(arrayClassId);

        for (long i = 0; i < numElements; i++) {
            out.writeId(in.readId());
        }
    }

    /**
     * HPROF_LOAD_CLASS:
     *   u4  classSerial
     *   id  classId
     *   u4  stackTraceSerial
     *   id  classNameId
     */
    private static void handleLoadClass(HprofDataInput in, HprofDataOutput out, long time, long length,
                                        int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        if (length != 4 + idSize + 4 + idSize) {
            throw new IOException("Unexpected LOAD_CLASS length: " + length);
        }
        long classSerial = in.readU4();
        long classId = in.readId();
        long stackTraceSerial = in.readU4();
        long nameId = in.readId();
        nameKinds.putIfAbsent(nameId, NameKind.CLASS_NAME);

        out.writeU1(HPROF_LOAD_CLASS);
        out.writeU4(time);
        out.writeU4(length);
        out.writeU4(classSerial);
        out.writeId(classId);
        out.writeU4(stackTraceSerial);
        out.writeId(nameId);
    }

    /**
     * HPROF_START_THREAD:
     *   u4  threadSerial
     *   id  threadObjectId
     *   u4  stackTraceSerial
     *   id  threadNameId
     *   id  threadGroupNameId
     *   id  threadGroupParentNameId
     */
    private static void handleStartThread(HprofDataInput in, HprofDataOutput out, long time, long length,
                                          int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        if (length != 4 + idSize + 4 + idSize + idSize + idSize) {
            throw new IOException("Unexpected START_THREAD length: " + length);
        }
        long threadSerial = in.readU4();
        long threadObjectId = in.readId();
        long stackTraceSerial = in.readU4();
        long threadNameId = in.readId();
        long threadGroupNameId = in.readId();
        long threadGroupParentNameId = in.readId();

        nameKinds.putIfAbsent(threadNameId, NameKind.THREAD_NAME);
        nameKinds.putIfAbsent(threadGroupNameId, NameKind.THREAD_GROUP_NAME);
        nameKinds.putIfAbsent(threadGroupParentNameId, NameKind.THREAD_GROUP_PARENT_NAME);

        out.writeU1(HPROF_START_THREAD);
        out.writeU4(time);
        out.writeU4(length);
        out.writeU4(threadSerial);
        out.writeId(threadObjectId);
        out.writeU4(stackTraceSerial);
        out.writeId(threadNameId);
        out.writeId(threadGroupNameId);
        out.writeId(threadGroupParentNameId);
    }

    /**
     * HPROF_FRAME:
     *   id  frameId
     *   id  methodNameId
     *   id  methodSigId
     *   id  sourceFileNameId
     *   u4  classSerial
     *   u4  lineNumber
     */
    private static void handleFrame(HprofDataInput in, HprofDataOutput out, long time, long length,
                                    int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        if (length != idSize + idSize + idSize + idSize + 4 + 4) {
            throw new IOException("Unexpected FRAME length: " + length);
        }
        long frameId = in.readId();
        long methodNameId = in.readId();
        long methodSigId = in.readId();
        long sourceFileNameId = in.readId();
        long classSerial = in.readU4();
        long lineNumber = in.readU4();

        nameKinds.putIfAbsent(methodNameId, NameKind.METHOD_NAME);
        nameKinds.putIfAbsent(methodSigId, NameKind.METHOD_SIGNATURE);
        nameKinds.putIfAbsent(sourceFileNameId, NameKind.SOURCE_FILE_NAME);

        out.writeU1(HPROF_FRAME);
        out.writeU4(time);
        out.writeU4(length);
        out.writeId(frameId);
        out.writeId(methodNameId);
        out.writeId(methodSigId);
        out.writeId(sourceFileNameId);
        out.writeU4(classSerial);
        out.writeU4(lineNumber);
    }

    /**
     * HPROF_GC_PRIM_ARRAY_DUMP:
     *   id  arrayId
     *   u4  stackTraceSerial
     *   u4  numElements
     *   u1  elementType
     *   [value]* elements
     */
    private static void handlePrimitiveArrayDump(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                                 int idSize) throws IOException {
        long arrayId = in.readId();
        long stackTraceSerial = in.readU4();
        long numElements = in.readU4();
        HprofType elementType = HprofType.fromCode(in.readU1());

        out.writeId(arrayId);
        out.writeU4(stackTraceSerial);
        out.writeU4(numElements);
        out.writeU1(elementType.code());

        for (long i = 0; i < numElements; i++) {
            writeValueByType(in, out, transformer, elementType, idSize);
        }
    }

    private static void writeValueByType(HprofDataInput in, HprofDataOutput out, HprofTransformer transformer,
                                         HprofType type, int idSize) throws IOException {
        switch (type) {
            case OBJECT, ARRAY_OBJECT -> out.writeId(in.readId());
            case BOOLEAN -> {
                int raw = in.readU1();
                boolean value = raw != 0;
                boolean transformed = transformer.transformBoolean(value);
                out.writeU1(transformed == value ? raw : (transformed ? 1 : 0));
            }
            case BYTE -> out.writeU1(transformer.transformByte((byte) in.readU1()));
            case CHAR -> out.writeU2(transformer.transformChar((char) in.readU2()));
            case SHORT -> out.writeU2(transformer.transformShort((short) in.readU2()));
            case INT -> out.writeU4(transformer.transformInt((int) in.readU4()));
            case LONG -> out.writeU8(transformer.transformLong(in.readU8()));
            case FLOAT -> {
                int bits = (int) in.readU4();
                float value = Float.intBitsToFloat(bits);
                int outBits = Float.floatToRawIntBits(transformer.transformFloat(value));
                out.writeU4(outBits);
            }
            case DOUBLE -> {
                long bits = in.readU8();
                double value = Double.longBitsToDouble(bits);
                long outBits = Double.doubleToRawLongBits(transformer.transformDouble(value));
                out.writeU8(outBits);
            }
        }
    }

    private static long sizeForType(HprofType type, int idSize) throws IOException {
        return switch (type) {
            case OBJECT, ARRAY_OBJECT -> idSize;
            case BOOLEAN, BYTE -> 1;
            case CHAR, SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
        };
    }

    private static List<HprofType> flattenedTypes(long classId, Map<Long, HprofClassInfo> classInfos,
                                                  Map<Long, List<HprofType>> flattenedTypesCache) {
        if (classId == 0) {
            return List.of();
        }
        List<HprofType> cached = flattenedTypesCache.get(classId);
        if (cached != null) {
            return cached;
        }
        HprofClassInfo info = classInfos.get(classId);
        if (info == null) {
            return null;
        }
        List<HprofType> result = new ArrayList<>();
        if (info.superClassId() != 0) {
            List<HprofType> parent = flattenedTypes(info.superClassId(), classInfos, flattenedTypesCache);
            if (parent == null) {
                return null;
            }
            result.addAll(parent);
        }
        result.addAll(info.instanceFieldTypes());
        flattenedTypesCache.put(classId, result);
        return result;
    }

    private static void copyBytes(HprofDataInput in, HprofDataOutput out, long length) throws IOException {
        if (length == 0) {
            return;
        }
        if (length < 0) {
            throw new IOException("Negative length: " + length);
        }
        byte[] buffer = new byte[8192];
        long remaining = length;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            in.readFully(buffer, 0, chunk);
            out.writeBytes(buffer, 0, chunk);
            remaining -= chunk;
        }
    }

    private static void skipFully(HprofDataInput in, long length) throws IOException {
        if (length <= 0) {
            return;
        }
        in.skipFully(length);
    }

    private static void skipValueByType(HprofDataInput in, HprofType type, int idSize) throws IOException {
        long size = sizeForType(type, idSize);
        skipFully(in, size);
    }

    private static String transformName(String original, HprofTransformer transformer, NameKind kind) {
        if (kind == null) {
            return transformer.transformUtf8String(original);
        }
        return switch (kind) {
            case CLASS_NAME -> transformer.transformClassName(original);
            case FIELD_NAME -> transformer.transformFieldName(original);
            case METHOD_NAME -> transformer.transformMethodName(original);
            case METHOD_SIGNATURE -> transformer.transformMethodSignature(original);
            case SOURCE_FILE_NAME -> transformer.transformSourceFileName(original);
            case THREAD_NAME -> transformer.transformThreadName(original);
            case THREAD_GROUP_NAME -> transformer.transformThreadGroupName(original);
            case THREAD_GROUP_PARENT_NAME -> transformer.transformThreadGroupParentName(original);
        };
    }

    private enum NameKind {
        CLASS_NAME,
        FIELD_NAME,
        METHOD_NAME,
        METHOD_SIGNATURE,
        SOURCE_FILE_NAME,
        THREAD_NAME,
        THREAD_GROUP_NAME,
        THREAD_GROUP_PARENT_NAME
    }

    private record HprofHeader(byte[] bytes, int idSize, long timestamp) {}

    private static final class LimitedInputStream extends InputStream {
        private final InputStream in;
        private long remaining;

        LimitedInputStream(InputStream source, long limit) {
            this.in = source;
            this.remaining = limit;
        }

        long remaining() {
            return remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int value = in.read();
            if (value < 0) {
                throw new EOFException("Unexpected end of stream");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(length, remaining);
            int read = in.read(buffer, offset, toRead);
            if (read < 0) {
                throw new EOFException("Unexpected end of stream");
            }
            remaining -= read;
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long toSkip = Math.min(n, remaining);
            long skipped = in.skip(toSkip);
            remaining -= skipped;
            return skipped;
        }
    }
}