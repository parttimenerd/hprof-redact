/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import me.bechberger.hprof.HprofDataInput;
import me.bechberger.hprof.HprofFilter;
import me.bechberger.hprof.HprofTransformer;
import me.bechberger.hprof.ZeroPrimitiveTransformer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.bechberger.hprof.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class HprofFilterTest {
    @Test
    void roundTripWithIdentityTransformer() throws Exception {
        byte[] input = buildMinimalHprof(123456, new int[]{1, 2, 3}, "MyClass", "value");
        byte[] output = runFilter(input, new HprofTransformer() {});
        assertArrayEquals(input, output);
    }

    @Test
    void roundTripWithThreadAndFrameRecords() throws Exception {
        byte[] input = buildHprofWithNames();
        byte[] output = runFilter(input, new HprofTransformer() {});
        assertArrayEquals(input, output);
    }

    @Test
    void zeroTransformerClearsInstanceAndArrayValues() throws Exception {
        byte[] input = buildMinimalHprof(123456, new int[]{1, 2, 3}, "MyClass", "value");
        byte[] output = runFilter(input, new ZeroPrimitiveTransformer());

        ParsedValues parsed = parseValues(output);
        assertNotNull(parsed);
        assertEquals(0, parsed.instanceValue);
        assertArrayEquals(new int[]{0, 0, 0}, parsed.arrayValues.stream().mapToInt(Integer::intValue).toArray());
    }

    @Test
    void zeroTransformerClearsCharArraysUsedByStrings() throws Exception {
        char[] chars = new char[]{'H', 'i', '!'};
        byte[] input = buildHprofWithCharArray(chars);
        byte[] output = runFilter(input, new ZeroPrimitiveTransformer());

        char[] result = readFirstCharArray(output);
        assertArrayEquals(new char[]{0, 0, 0}, result);
    }

    @Test
    void transformerCanRewriteUtf8Records() throws Exception {
        byte[] input = buildMinimalHprof(7, new int[]{9}, "OriginalName", "value");
        byte[] output = runFilter(input, new HprofTransformer() {
            @Override
            public String transformUtf8(String value) {
                if (value.equals("OriginalName")) {
                    return "Redacted";
                }
                return value;
            }
        });

        String utf8 = readFirstUtf8(output);
        assertEquals("Redacted", utf8);
    }

    @Test
    void transformerCanRewriteSpecificNames() throws Exception {
        byte[] input = buildHprofWithNames();
        byte[] output = runFilter(input, new HprofTransformer() {
            @Override
            public String transformClassName(String value) {
                return "C";
            }

            @Override
            public String transformFieldName(String value) {
                return "F";
            }

            @Override
            public String transformMethodName(String value) {
                return "M";
            }

            @Override
            public String transformThreadName(String value) {
                return "T";
            }

            @Override
            public String transformThreadGroupName(String value) {
                return "G";
            }

            @Override
            public String transformThreadGroupParentName(String value) {
                return "P";
            }
        });

        Map<Long, String> strings = readAllUtf8(output);
        assertEquals("C", strings.get(1L));
        assertEquals("F", strings.get(2L));
        assertEquals("M", strings.get(3L));
        assertEquals("T", strings.get(4L));
        assertEquals("G", strings.get(5L));
        assertEquals("P", strings.get(6L));
    }


    private static byte[] runFilter(byte[] input, HprofTransformer transformer) throws IOException {
        Path temp = Files.createTempFile("hprof-test", ".hprof");
        try {
            Files.write(temp, input);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            HprofFilter.filter(temp, out, transformer);
            return out.toByteArray();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] buildMinimalHprof(int instanceValue, int[] arrayValues, String className, String fieldName) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);
        writeU8(data, 0);

        writeUtf8Record(data, 1, className);
        writeUtf8Record(data, 2, fieldName);

        byte[] heapSegment = buildHeapSegment(instanceValue, arrayValues);
        writeRecord(data, HPROF_HEAP_DUMP, 0, heapSegment);
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    private static byte[] buildHprofWithNames() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);
        writeU8(data, 0);

        writeLoadClassRecord(data, 1, 0x100, 0, 1);
        writeStartThreadRecord(data, 10, 0x200, 0, 4, 5, 6);
        writeFrameRecord(data, 0x300, 3, 0x400, 0x401, 7, 42);

        byte[] heapSegment = buildClassDumpWithFieldName(0x100, 2);
        writeRecord(data, HPROF_HEAP_DUMP, 0, heapSegment);
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        writeUtf8Record(data, 1, "MyClass");
        writeUtf8Record(data, 2, "myField");
        writeUtf8Record(data, 3, "myMethod");
        writeUtf8Record(data, 4, "thread");
        writeUtf8Record(data, 5, "group");
        writeUtf8Record(data, 6, "parent");
        writeUtf8Record(data, 7, "Source.java");

        data.flush();
        return out.toByteArray();
    }

    private static byte[] buildHprofWithObjectArray(long[] elementIds) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);
        writeU8(data, 0);

        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream seg = new DataOutputStream(segment);
        seg.writeByte(HPROF_GC_OBJ_ARRAY_DUMP);
        writeId(seg, 0x600);
        writeU4(seg, 0);
        writeU4(seg, elementIds.length);
        writeId(seg, 0x700);
        for (long id : elementIds) {
            writeId(seg, id);
        }
        seg.flush();

        writeRecord(data, HPROF_HEAP_DUMP, 0, segment.toByteArray());
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    private static void writeUtf8Record(DataOutputStream data, long id, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream payloadOut = new DataOutputStream(payload);
        writeId(payloadOut, id);
        payloadOut.write(bytes);
        payloadOut.flush();

        writeRecord(data, HPROF_UTF8, 0, payload.toByteArray());
    }

    private static void writeLoadClassRecord(DataOutputStream data, int serial, long classId, int stackSerial, long nameId) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream payloadOut = new DataOutputStream(payload);
        writeU4(payloadOut, serial);
        writeId(payloadOut, classId);
        writeU4(payloadOut, stackSerial);
        writeId(payloadOut, nameId);
        payloadOut.flush();
        writeRecord(data, HPROF_LOAD_CLASS, 0, payload.toByteArray());
    }

    private static void writeStartThreadRecord(DataOutputStream data, int threadSerial, long threadObjectId, int stackSerial,
                                               long threadNameId, long groupNameId, long parentGroupNameId) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream payloadOut = new DataOutputStream(payload);
        writeU4(payloadOut, threadSerial);
        writeId(payloadOut, threadObjectId);
        writeU4(payloadOut, stackSerial);
        writeId(payloadOut, threadNameId);
        writeId(payloadOut, groupNameId);
        writeId(payloadOut, parentGroupNameId);
        payloadOut.flush();
        writeRecord(data, HPROF_START_THREAD, 0, payload.toByteArray());
    }

    private static void writeFrameRecord(DataOutputStream data, long frameId, long methodNameId, long methodSigId,
                                         long sourceFileId, int classSerial, int lineNumber) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream payloadOut = new DataOutputStream(payload);
        writeId(payloadOut, frameId);
        writeId(payloadOut, methodNameId);
        writeId(payloadOut, methodSigId);
        writeId(payloadOut, sourceFileId);
        writeU4(payloadOut, classSerial);
        writeU4(payloadOut, lineNumber);
        payloadOut.flush();
        writeRecord(data, HPROF_FRAME, 0, payload.toByteArray());
    }

    private static byte[] buildHeapSegment(int instanceValue, int[] arrayValues) throws IOException {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(segment);

        data.writeByte(HPROF_GC_CLASS_DUMP);
        writeId(data, 0x100);
        writeU4(data, 0); // stack trace serial
        writeId(data, 0); // super class id
        writeId(data, 0); // class loader
        writeId(data, 0); // signers
        writeId(data, 0); // protection domain
        writeId(data, 0); // reserved1
        writeId(data, 0); // reserved2
        writeU4(data, 4); // instance size
        writeU2(data, 0); // constant pool size
        writeU2(data, 0); // static field count
        writeU2(data, 1); // instance field count
        writeId(data, 2); // field name id
        data.writeByte(HPROF_TYPE_INT);

        data.writeByte(HPROF_GC_INSTANCE_DUMP);
        writeId(data, 0x200);
        writeU4(data, 0);
        writeId(data, 0x100);
        writeU4(data, 4);
        writeU4(data, instanceValue);

        data.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
        writeId(data, 0x300);
        writeU4(data, 0);
        writeU4(data, arrayValues.length);
        data.writeByte(HPROF_TYPE_INT);
        for (int value : arrayValues) {
            writeU4(data, value);
        }

        data.flush();
        return segment.toByteArray();
    }

    private static byte[] buildClassDumpWithFieldName(long classId, long fieldNameId) throws IOException {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(segment);

        data.writeByte(HPROF_GC_CLASS_DUMP);
        writeId(data, classId);
        writeU4(data, 0);
        writeId(data, 0);
        writeId(data, 0);
        writeId(data, 0);
        writeId(data, 0);
        writeId(data, 0);
        writeId(data, 0);
        writeU4(data, 0);
        writeU2(data, 0);
        writeU2(data, 0);
        writeU2(data, 1);
        writeId(data, fieldNameId);
        data.writeByte(HPROF_TYPE_INT);

        data.flush();
        return segment.toByteArray();
    }

    private static byte[] buildHprofWithCharArray(char[] values) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);
        writeU8(data, 0);

        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream seg = new DataOutputStream(segment);
        seg.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
        writeId(seg, 0x500);
        writeU4(seg, 0);
        writeU4(seg, values.length);
        seg.writeByte(HPROF_TYPE_CHAR);
        for (char value : values) {
            writeU2(seg, value);
        }
        seg.flush();

        writeRecord(data, HPROF_HEAP_DUMP, 0, segment.toByteArray());
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
        out.writeByte(tag);
        writeU4(out, time);
        writeU4(out, payload.length);
        out.write(payload);
    }

    private static void writeId(DataOutputStream out, long value) throws IOException {
        writeU4(out, value);
    }

    private static void writeU2(DataOutputStream out, int value) throws IOException {
        out.writeShort(value & 0xFFFF);
    }

    private static void writeU4(DataOutputStream out, long value) throws IOException {
        out.writeInt((int) value);
    }

    private static void writeU8(DataOutputStream out, long value) throws IOException {
        out.writeLong(value);
    }

    private static String readFirstUtf8(byte[] hprof) throws IOException {
        HprofDataInput in = new HprofDataInput(new java.io.ByteArrayInputStream(hprof));
        int idSize = readHeader(in);
        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            in.readU4();
            long length = in.readU4();
            if (tag == HPROF_UTF8) {
                in.readId();
                byte[] data = new byte[(int) (length - idSize)];
                in.readFully(data);
                return new String(data, StandardCharsets.UTF_8);
            }
            skipBytes(in, length);
        }
        return null;
    }

    private static Map<Long, String> readAllUtf8(byte[] hprof) throws IOException {
        HprofDataInput in = new HprofDataInput(new java.io.ByteArrayInputStream(hprof));
        int idSize = readHeader(in);
        Map<Long, String> result = new HashMap<>();
        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            in.readU4();
            long length = in.readU4();
            if (tag == HPROF_UTF8) {
                long id = in.readId();
                byte[] data = new byte[(int) (length - idSize)];
                in.readFully(data);
                result.put(id, new String(data, StandardCharsets.UTF_8));
            } else {
                skipBytes(in, length);
            }
        }
        return result;
    }

    private static long[] readFirstObjectArray(byte[] hprof) throws IOException {
        HprofDataInput in = new HprofDataInput(new java.io.ByteArrayInputStream(hprof));
        int idSize = readHeader(in);
        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            in.readU4();
            long length = in.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                byte[] segment = new byte[(int) length];
                in.readFully(segment);
                return parseFirstObjectArray(segment, idSize);
            }
            skipBytes(in, length);
        }
        return new long[0];
    }

    private static long[] parseFirstObjectArray(byte[] segment, int idSize) throws IOException {
        HprofDataInput in = new HprofDataInput(new java.io.ByteArrayInputStream(segment));
        in.setIdSize(idSize);
        SegmentReader reader = new SegmentReader(in, segment.length, idSize);
        while (reader.remaining > 0) {
            int subTag = reader.readU1();
            if (subTag == HPROF_GC_OBJ_ARRAY_DUMP) {
                reader.readId();
                reader.readU4();
                long count = reader.readU4();
                reader.readId();
                long[] result = new long[(int) count];
                for (int i = 0; i < count; i++) {
                    result[i] = reader.readId();
                }
                return result;
            }
            reader.skipSubRecord(subTag);
        }
        return new long[0];
    }

    private static ParsedValues parseValues(byte[] hprof) throws IOException {
        HprofDataInput in = new HprofDataInput(new ByteArrayInputStream(hprof));
        int idSize = readHeader(in);

        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            in.readU4();
            long length = in.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                byte[] segment = new byte[(int) length];
                in.readFully(segment);
                return parseHeapSegment(segment, idSize);
            }
            skipBytes(in, length);
        }
        return null;
    }

    private static char[] readFirstCharArray(byte[] hprof) throws IOException {
        HprofDataInput in = new HprofDataInput(new ByteArrayInputStream(hprof));
        int idSize = readHeader(in);
        while (true) {
            int tag = in.readTag();
            if (tag < 0) {
                break;
            }
            in.readU4();
            long length = in.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                byte[] segment = new byte[(int) length];
                in.readFully(segment);
                return parseFirstCharArray(segment, idSize);
            }
            skipBytes(in, length);
        }
        return new char[0];
    }

    private static char[] parseFirstCharArray(byte[] segment, int idSize) throws IOException {
        HprofDataInput in = new HprofDataInput(new ByteArrayInputStream(segment));
        in.setIdSize(idSize);
        SegmentReader reader = new SegmentReader(in, segment.length, idSize);
        while (reader.remaining > 0) {
            int subTag = reader.readU1();
            if (subTag == HPROF_GC_PRIM_ARRAY_DUMP) {
                reader.skipId();
                reader.skipU4();
                long count = reader.readU4();
                int elementType = reader.readU1();
                if (elementType == HPROF_TYPE_CHAR) {
                    char[] result = new char[(int) count];
                    for (int i = 0; i < count; i++) {
                        result[i] = (char) reader.readU2();
                    }
                    return result;
                }
                for (int i = 0; i < count; i++) {
                    reader.skipValue(elementType);
                }
            } else {
                throw new IOException("Unexpected sub tag: " + subTag);
            }
        }
        return new char[0];
    }

    private static int readHeader(HprofDataInput in) throws IOException {
        while (true) {
            int b = in.readU1();
            if (b == 0) {
                break;
            }
        }
        int idSize = (int) in.readU4();
        in.readU8();
        in.setIdSize(idSize);
        return idSize;
    }

    private static void skipBytes(HprofDataInput in, long length) throws IOException {
        byte[] buffer = new byte[4096];
        long remaining = length;
        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.length, remaining);
            in.readFully(buffer, 0, chunk);
            remaining -= chunk;
        }
    }

    private static ParsedValues parseHeapSegment(byte[] segment, int idSize) throws IOException {
        HprofDataInput in = new HprofDataInput(new ByteArrayInputStream(segment));
        in.setIdSize(idSize);
        SegmentReader reader = new SegmentReader(in, segment.length, idSize);
        ParsedValues values = new ParsedValues();
        while (reader.remaining > 0) {
            int subTag = reader.readU1();
            switch (subTag) {
                case HPROF_GC_CLASS_DUMP -> {
                    reader.skipId();
                    reader.skipU4();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipU4();
                    int constantPoolSize = reader.readU2();
                    for (int i = 0; i < constantPoolSize; i++) {
                        reader.skipU2();
                        int type = reader.readU1();
                        reader.skipValue(type);
                    }
                    int staticCount = reader.readU2();
                    for (int i = 0; i < staticCount; i++) {
                        reader.skipId();
                        int type = reader.readU1();
                        reader.skipValue(type);
                    }
                    int instanceCount = reader.readU2();
                    for (int i = 0; i < instanceCount; i++) {
                        reader.skipId();
                        reader.readU1();
                    }
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    reader.skipId();
                    reader.skipU4();
                    reader.skipId();
                    long dataLength = reader.readU4();
                    values.instanceValue = reader.readInt();
                    reader.skipBytes(dataLength - 4);
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    reader.skipId();
                    reader.skipU4();
                    long count = reader.readU4();
                    int elementType = reader.readU1();
                    if (elementType == HPROF_TYPE_INT) {
                        List<Integer> ints = new ArrayList<>();
                        for (int i = 0; i < count; i++) {
                            ints.add(reader.readInt());
                        }
                        values.arrayValues = ints;
                    } else {
                        for (int i = 0; i < count; i++) {
                            reader.skipValue(elementType);
                        }
                    }
                }
                default -> throw new IOException("Unexpected sub tag: " + subTag);
            }
        }
        return values;
    }

    private static final class ParsedValues {
        int instanceValue;
        List<Integer> arrayValues = new ArrayList<>();
    }

    private static final class SegmentReader {
        private final HprofDataInput in;
        private final int idSize;
        private long remaining;

        SegmentReader(HprofDataInput in, long remaining, int idSize) {
            this.in = in;
            this.remaining = remaining;
            this.idSize = idSize;
        }

        int readU1() throws IOException {
            remaining -= 1;
            return in.readU1();
        }

        int readU2() throws IOException {
            remaining -= 2;
            return in.readU2();
        }

        long readU4() throws IOException {
            remaining -= 4;
            return in.readU4();
        }

        void skipU2() throws IOException {
            readU2();
        }

        void skipU4() throws IOException {
            readU4();
        }

        long readId() throws IOException {
            remaining -= idSize;
            return in.readId();
        }

        void skipId() throws IOException {
            readId();
        }

        int readInt() throws IOException {
            return (int) readU4();
        }

        void skipValue(int type) throws IOException {
            switch (type) {
                case HPROF_TYPE_OBJECT -> skipId();
                case HPROF_TYPE_BOOLEAN, HPROF_TYPE_BYTE -> skipBytes(1);
                case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> skipBytes(2);
                case HPROF_TYPE_INT, HPROF_TYPE_FLOAT -> skipBytes(4);
                case HPROF_TYPE_LONG, HPROF_TYPE_DOUBLE -> skipBytes(8);
                default -> throw new IOException("Unsupported type: " + type);
            }
        }

        void skipBytes(long length) throws IOException {
            if (length <= 0) {
                return;
            }
            byte[] buffer = new byte[256];
            long remainingBytes = length;
            while (remainingBytes > 0) {
                int chunk = (int) Math.min(buffer.length, remainingBytes);
                in.readFully(buffer, 0, chunk);
                remainingBytes -= chunk;
                remaining -= chunk;
            }
        }

        void skipSubRecord(int subTag) throws IOException {
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> skipBytes(idSize);
                case HPROF_GC_ROOT_JNI_GLOBAL -> skipBytes(idSize + idSize);
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME,
                     HPROF_GC_ROOT_THREAD_OBJ -> skipBytes(idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> skipBytes(idSize + 4);
                case HPROF_GC_CLASS_DUMP -> throw new IOException("CLASS_DUMP not supported here");
                case HPROF_GC_INSTANCE_DUMP -> throw new IOException("INSTANCE_DUMP not supported here");
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    skipId();
                    skipU4();
                    long num = readU4();
                    skipId();
                    skipBytes(num * idSize);
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    skipId();
                    skipU4();
                    long num = readU4();
                    int type = readU1();
                    long elementSize = switch (type) {
                        case HPROF_TYPE_BOOLEAN, HPROF_TYPE_BYTE -> 1;
                        case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> 2;
                        case HPROF_TYPE_INT, HPROF_TYPE_FLOAT -> 4;
                        case HPROF_TYPE_LONG, HPROF_TYPE_DOUBLE -> 8;
                        default -> throw new IOException("Unsupported type: " + type);
                    };
                    skipBytes(num * elementSize);
                }
                default -> throw new IOException("Unexpected sub tag: " + subTag);
            }
        }
    }
}