/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import me.bechberger.hprof.transformer.HprofTransformer;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.bechberger.hprof.HprofConstants.*;

public final class HprofFilter {
    private final HprofTransformer transformer;
    private final VerboseHelper verboseHelper;

    public HprofFilter(HprofTransformer transformer, java.io.PrintStream verboseOut) {
        this.transformer = transformer;
        this.verboseHelper = verboseOut == null ? null : new VerboseHelper(verboseOut);
    }

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
    public void filter(Path inputPath, OutputStream output) throws IOException {
        Map<Long, HprofClassInfo> classInfos = new HashMap<>();
        Map<Long, NameKind> nameKinds = new HashMap<>();

        try (InputStream input = HprofIO.openInputStream(inputPath)) {
            HprofDataInput in = new HprofDataInput(input);
            HprofHeader header = readHeader(in);
            scanForMetadata(in, header.idSize(), classInfos, nameKinds);
        }

        try (InputStream input = HprofIO.openInputStream(inputPath)) {
            HprofDataInput in = new HprofDataInput(input);
            HprofDataOutput out = new HprofDataOutput(output);
            HprofHeader header = readHeader(in);

            out.writeBytes(header.bytes());
            out.writeU4(header.idSize());
            out.writeU8(header.timestamp());

            in.setIdSize(header.idSize());
            out.setIdSize(header.idSize());

                    Map<Long, List<HprofType>> flattenedTypesCache = new HashMap<>();
                    writeRecords(in, out, header.idSize(), classInfos, flattenedTypesCache, nameKinds);
            out.flush();
        }
    }

    private void writeRecords(HprofDataInput in, HprofDataOutput out,
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
                case HPROF_UTF8 -> handleUtf8Record(in, out, time, length, idSize, nameKinds);
                case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT -> {
                    writeRecordHeader(out, tag, time, length);
                    handleHeapDumpSegment(in, out, length, idSize, classInfos, flattenedTypesCache, nameKinds);
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

    private HprofHeader readHeader(HprofDataInput in) throws IOException {
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

    private void scanForMetadata(HprofDataInput in, int idSize,
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
                case HPROF_LOAD_CLASS -> scanLoadClass(in, nameKinds);
                case HPROF_START_THREAD -> scanStartThread(in, idSize, nameKinds);
                case HPROF_FRAME -> scanFrame(in, idSize, nameKinds);
                case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT -> scanHeapDumpSegment(in, length, idSize, classInfos, nameKinds);
                default -> skipFully(in, length);
            }
        }
    }

    private void scanLoadClass(HprofDataInput in, Map<Long, NameKind> nameKinds) throws IOException {
        skipFully(in, 4);
        long classId = in.readId();
        skipFully(in, 4);
        long nameId = in.readId();
        nameKinds.putIfAbsent(nameId, NameKind.CLASS_NAME);
        if (verboseHelper != null) {
            verboseHelper.recordClassNameId(classId, nameId);
        }
    }

    private void scanStartThread(HprofDataInput in, int idSize, Map<Long, NameKind> nameKinds) throws IOException {
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

    private void scanFrame(HprofDataInput in, int idSize, Map<Long, NameKind> nameKinds) throws IOException {
        skipFully(in, idSize); // frameId
        long methodNameId = in.readId();
        long methodSigId = in.readId();
        long sourceFileNameId = in.readId();
        skipFully(in, 4 + 4); // class serial, line number
        nameKinds.putIfAbsent(methodNameId, NameKind.METHOD_NAME);
        nameKinds.putIfAbsent(methodSigId, NameKind.METHOD_SIGNATURE);
        nameKinds.putIfAbsent(sourceFileNameId, NameKind.SOURCE_FILE_NAME);
    }

    private void scanHeapDumpSegment(HprofDataInput in, long length, int idSize,
                                     Map<Long, HprofClassInfo> classInfos,
                                     Map<Long, NameKind> nameKinds) throws IOException {
        LimitedInputStream limited = new LimitedInputStream(in.rawStream(), length);
        HprofDataInput segmentIn = new HprofDataInput(limited);
        segmentIn.setIdSize(idSize);

        while (limited.remaining() > 0) {
            int subTag = segmentIn.readU1();
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> skipFully(segmentIn, idSize);
                case HPROF_GC_ROOT_JNI_GLOBAL -> skipFully(segmentIn, idSize + idSize);
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME,
                     HPROF_GC_ROOT_THREAD_OBJ -> skipFully(segmentIn, idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> skipFully(segmentIn, idSize + 4);
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

    private void scanClassDump(HprofDataInput in, int idSize,
                               Map<Long, HprofClassInfo> classInfos,
                               Map<Long, NameKind> nameKinds) throws IOException {
        long classId = in.readId();
        skipFully(in, 4);
        long superClassId = in.readId();
        skipFully(in, idSize * 5L);
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

    private void skipInstanceDump(HprofDataInput in, int idSize) throws IOException {
        skipFully(in, idSize + 4 + idSize);
        long dataLength = in.readU4();
        skipFully(in, dataLength);
    }

    private void skipObjectArrayDump(HprofDataInput in, int idSize) throws IOException {
        skipFully(in, idSize + 4);
        long numElements = in.readU4();
        skipFully(in, idSize);
        skipFully(in, numElements * idSize);
    }

    private void skipPrimitiveArrayDump(HprofDataInput in, int idSize) throws IOException {
        skipFully(in, idSize + 4);
        long numElements = in.readU4();
        HprofType elementType = HprofType.fromCode(in.readU1());
        long elementSize = sizeForType(elementType, idSize);
        skipFully(in, numElements * elementSize);
    }

    private void writeRecordHeader(HprofDataOutput out, int tag, long time, long length) throws IOException {
        out.writeU1(tag);
        out.writeU4(time);
        out.writeU4(length);
    }

    /**
     * HPROF_UTF8:
     *   id   nameId
     *   [u1]* UTF-8 bytes (no trailing zero)
     */
    private void handleUtf8Record(HprofDataInput in, HprofDataOutput out,
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

        NameKind kind = nameKinds.get(id);
        String transformed = transformName(original, transformer, kind);
        if (transformed == null || transformed.equals(original)) {
            // Preserve original bytes (and therefore size) when not changing the string.
            out.writeU1(HPROF_UTF8);
            out.writeU4(time);
            out.writeU4(length);
            out.writeId(id);
            out.writeBytes(data);
            if (verboseHelper != null) {
                verboseHelper.recordNameString(kind, id, original, true);
            }
            return;
        }

        byte[] outBytes = ModifiedUtf8.encode(transformed);
        long newLength = idSize + outBytes.length;

        out.writeU1(HPROF_UTF8);
        out.writeU4(time);
        out.writeU4(newLength);
        out.writeId(id);
        out.writeBytes(outBytes);
        if (verboseHelper != null) {
            verboseHelper.recordNameString(kind, id, transformed, false);
        }
        if (verboseHelper != null) {
            verboseHelper.logUtf8Change(kind, id, original, transformed);
        }
    }

    /**
     * HPROF_HEAP_DUMP(_SEGMENT):
     *   [u1]* heap sub-records, each prefixed with sub-tag.
     */
    private void handleHeapDumpSegment(HprofDataInput in, HprofDataOutput out,
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
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME,
                     HPROF_GC_ROOT_THREAD_OBJ -> copyBytes(segmentIn, out, idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> copyBytes(segmentIn, out, idSize + 4);
                case HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> copyBytes(segmentIn, out, idSize);
                // Typed heap records
                case HPROF_GC_CLASS_DUMP -> handleClassDump(segmentIn, out, idSize, classInfos, flattenedTypesCache,
                    nameKinds);
                case HPROF_GC_INSTANCE_DUMP -> handleInstanceDump(segmentIn, out, idSize, classInfos,
                    flattenedTypesCache);
                case HPROF_GC_OBJ_ARRAY_DUMP -> handleObjectArrayDump(segmentIn, out);
                case HPROF_GC_PRIM_ARRAY_DUMP -> handlePrimitiveArrayDump(segmentIn, out);
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
    private void handleClassDump(HprofDataInput in, HprofDataOutput out,
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
            writeValueByType(in, out, type);
        }

        int staticFieldCount = in.readU2();
        out.writeU2(staticFieldCount);
        String className = resolveClassName(classId);
        for (int i = 0; i < staticFieldCount; i++) {
            long nameId = in.readId();
            HprofType type = HprofType.fromCode(in.readU1());
            out.writeId(nameId);
            out.writeU1(type.code());
            String fieldName = resolveName(nameId, "field#" + nameId);
            writeFieldValueByType(in, out, type, className, fieldName, true);
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
    private void handleInstanceDump(HprofDataInput in, HprofDataOutput out,
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

        if (verboseHelper == null) {
            for (HprofType type : flattened) {
                writeValueByType(in, out, type);
            }
            return;
        }

        List<HprofClassInfo.FieldDef> fieldDefs = flattenedFieldDefs(classId, classInfos);
        if (fieldDefs == null || fieldDefs.size() != flattened.size()) {
            for (HprofType type : flattened) {
                writeValueByType(in, out, type);
            }
            return;
        }

        String className = resolveClassName(classId);
        for (HprofClassInfo.FieldDef def : fieldDefs) {
            String fieldName = resolveName(def.nameId(), "field#" + def.nameId());
            writeFieldValueByType(in, out, def.type(), className, fieldName, false);
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
    private void handleObjectArrayDump(HprofDataInput in, HprofDataOutput out) throws IOException {
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
    private void handleLoadClass(HprofDataInput in, HprofDataOutput out, long time, long length,
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
    private void handleStartThread(HprofDataInput in, HprofDataOutput out, long time, long length,
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
    private void handleFrame(HprofDataInput in, HprofDataOutput out, long time, long length,
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
    private void handlePrimitiveArrayDump(HprofDataInput in, HprofDataOutput out) throws IOException {
        long arrayId = in.readId();
        long stackTraceSerial = in.readU4();
        long numElements = in.readU4();
        HprofType elementType = HprofType.fromCode(in.readU1());

        out.writeId(arrayId);
        out.writeU4(stackTraceSerial);
        out.writeU4(numElements);
        out.writeU1(elementType.code());
        if (numElements > Integer.MAX_VALUE) {
            throw new IOException("Primitive array too large: " + numElements);
        }

        switch (elementType) {
            case BOOLEAN -> {
                boolean[] values = new boolean[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readU1() != 0;
                }
                boolean[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformBooleanArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "boolean", before, values);
                }
                for (boolean value : values) {
                    out.writeU1(value ? 1 : 0);
                }
            }
            case BYTE -> {
                byte[] values = new byte[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (byte) in.readU1();
                }
                byte[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformByteArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "byte", before, values);
                }
                for (byte value : values) {
                    out.writeU1(value);
                }
            }
            case CHAR -> {
                char[] values = new char[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (char) in.readU2();
                }
                char[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformCharArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "char", before, values);
                }
                for (char value : values) {
                    out.writeU2(value);
                }
            }
            case SHORT -> {
                short[] values = new short[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (short) in.readU2();
                }
                short[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformShortArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "short", before, values);
                }
                for (short value : values) {
                    out.writeU2(value);
                }
            }
            case INT -> {
                int[] values = new int[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = (int) in.readU4();
                }
                int[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformIntArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "int", before, values);
                }
                for (int value : values) {
                    out.writeU4(value);
                }
            }
            case LONG -> {
                long[] values = new long[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = in.readU8();
                }
                long[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformLongArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "long", before, values);
                }
                for (long value : values) {
                    out.writeU8(value);
                }
            }
            case FLOAT -> {
                float[] values = new float[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = Float.intBitsToFloat((int) in.readU4());
                }
                float[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformFloatArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "float", before, values);
                }
                for (float value : values) {
                    out.writeU4(Float.floatToRawIntBits(value));
                }
            }
            case DOUBLE -> {
                double[] values = new double[(int) numElements];
                for (int i = 0; i < values.length; i++) {
                    values[i] = Double.longBitsToDouble(in.readU8());
                }
                double[] before = verboseHelper == null ? null : Arrays.copyOf(values, values.length);
                transformer.transformDoubleArray(values);
                if (verboseHelper != null) {
                    verboseHelper.logArrayChanges(arrayId, "double", before, values);
                }
                for (double value : values) {
                    out.writeU8(Double.doubleToRawLongBits(value));
                }
            }
            case OBJECT, ARRAY_OBJECT -> {
                for (long i = 0; i < numElements; i++) {
                    out.writeId(in.readId());
                }
            }
        }
    }

    private void writeValueByType(HprofDataInput in, HprofDataOutput out,
                                  HprofType type) throws IOException {
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

    private void writeFieldValueByType(HprofDataInput in, HprofDataOutput out,
                                       HprofType type, String className, String fieldName,
                                       boolean isStatic) throws IOException {
        switch (type) {
            case OBJECT, ARRAY_OBJECT -> out.writeId(in.readId());
            case BOOLEAN -> {
                int raw = in.readU1();
                boolean value = raw != 0;
                boolean transformed = transformer.transformBoolean(value);
                out.writeU1(transformed == value ? raw : (transformed ? 1 : 0));
                if (verboseHelper != null && transformed != value) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case BYTE -> {
                byte value = (byte) in.readU1();
                byte transformed = transformer.transformByte(value);
                out.writeU1(transformed);
                if (verboseHelper != null && transformed != value) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case CHAR -> {
                char value = (char) in.readU2();
                char transformed = transformer.transformChar(value);
                out.writeU2(transformed);
                if (verboseHelper != null && transformed != value) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case SHORT -> {
                short value = (short) in.readU2();
                short transformed = transformer.transformShort(value);
                out.writeU2(transformed);
                if (verboseHelper != null && transformed != value) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case INT -> {
                int value = (int) in.readU4();
                int transformed = transformer.transformInt(value);
                out.writeU4(transformed);
                if (verboseHelper != null && transformed != value) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case LONG -> {
                long value = in.readU8();
                long transformed = transformer.transformLong(value);
                out.writeU8(transformed);
                if (verboseHelper != null && transformed != value) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case FLOAT -> {
                int bits = (int) in.readU4();
                float value = Float.intBitsToFloat(bits);
                float transformed = transformer.transformFloat(value);
                out.writeU4(Float.floatToRawIntBits(transformed));
                if (verboseHelper != null && Float.floatToRawIntBits(transformed) != bits) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
            case DOUBLE -> {
                long bits = in.readU8();
                double value = Double.longBitsToDouble(bits);
                double transformed = transformer.transformDouble(value);
                out.writeU8(Double.doubleToRawLongBits(transformed));
                if (verboseHelper != null && Double.doubleToRawLongBits(transformed) != bits) {
                    verboseHelper.logFieldChange(className, fieldName, String.valueOf(value),
                            String.valueOf(transformed), isStatic);
                }
            }
        }
    }

    private long sizeForType(HprofType type, int idSize) {
        return switch (type) {
            case OBJECT, ARRAY_OBJECT -> idSize;
            case BOOLEAN, BYTE -> 1;
            case CHAR, SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
        };
    }

    private List<HprofType> flattenedTypes(long classId, Map<Long, HprofClassInfo> classInfos,
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

    private List<HprofClassInfo.FieldDef> flattenedFieldDefs(long classId,
                                                             Map<Long, HprofClassInfo> classInfos) {
        if (classId == 0) {
            return List.of();
        }
        HprofClassInfo info = classInfos.get(classId);
        if (info == null) {
            return null;
        }
        List<HprofClassInfo.FieldDef> result = new ArrayList<>();
        if (info.superClassId() != 0) {
            List<HprofClassInfo.FieldDef> parent = flattenedFieldDefs(info.superClassId(), classInfos);
            if (parent == null) {
                return null;
            }
            result.addAll(parent);
        }
        result.addAll(info.instanceFields());
        return result;
    }

    private String resolveClassName(long classId) {
        if (verboseHelper == null) {
            return "class#" + classId;
        }
        return verboseHelper.resolveClassName(classId);
    }

    private String resolveName(long nameId, String fallback) {
        if (verboseHelper == null) {
            return fallback;
        }
        return verboseHelper.resolveName(nameId, fallback);
    }

    private void copyBytes(HprofDataInput in, HprofDataOutput out, long length) throws IOException {
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

    private void skipFully(HprofDataInput in, long length) throws IOException {
        if (length <= 0) {
            return;
        }
        in.skipFully(length);
    }

    private void skipValueByType(HprofDataInput in, HprofType type, int idSize) throws IOException {
        long size = sizeForType(type, idSize);
        skipFully(in, size);
    }

    private String transformName(String original, HprofTransformer transformer, NameKind kind) {
        if (kind == null) {
            return transformer.transformUtf8String(original);
        }
        return switch (kind) {
            case CLASS_NAME -> transformer.transformClassName(original);
            case FIELD_NAME -> transformer.transformFieldName(original);
            case METHOD_NAME, METHOD_SIGNATURE -> transformer.transformUtf8String(original);
            case SOURCE_FILE_NAME -> transformer.transformSourceFileName(original);
            case THREAD_NAME -> transformer.transformThreadName(original);
            case THREAD_GROUP_NAME -> transformer.transformThreadGroupName(original);
            case THREAD_GROUP_PARENT_NAME -> transformer.transformThreadGroupParentName(original);
        };
    }

    enum NameKind {
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