/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static me.bechberger.hprof.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Varied HPROF diagnostic scenarios testing edge cases not covered by
 * {@link HprofDiagnoseIntegrationTest} or {@link HprofDiagnoseCorruptionTest}.
 *
 * <p>Each scenario is isolated in a {@code @Nested static class} with its own
 * {@code @BeforeAll} that builds an in-memory HPROF and calls
 * {@link HprofDiagnose#diagnose(Path, HprofDiagnose.Options)}.
 */
class HprofDiagnoseExamplesTest {

    // =========================================================================
    // Scenario 1: idSize=8
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class IdSize8Test {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-idsize8", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void fileSummaryReportsIdSize8() {
            assertEquals(8, report.fileSummary().idSize());
        }

        @Test
        void recordHistogramContainsHeapDumpSegment() {
            assertTrue(containsTagName(report.recordHistogram(), "HPROF_HEAP_DUMP_SEGMENT"),
                    "recordHistogram must contain HPROF_HEAP_DUMP_SEGMENT");
        }

        @Test
        void subrecordHistogramContainsClassDump() {
            assertTrue(containsSubTagName(report.subrecordHistogram(), "HPROF_GC_CLASS_DUMP"),
                    "subrecordHistogram must contain HPROF_GC_CLASS_DUMP for idSize=8 dump");
        }

        @Test
        void topClassesNotEmpty() {
            assertFalse(report.topClasses().isEmpty(),
                    "topClasses should contain entries from idSize=8 dump");
        }

        @Test
        void matSizePositive() {
            assertTrue(report.sizeAttribution().matHeapSizeWithCompressedOops() > 0,
                    "MAT compressed size should be > 0");
        }

        private static final int ID_SIZE = 8;

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            // Header: magic + idSize=8 + timestamp
            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, ID_SIZE);
            writeU8(data, 999999L);

            // UTF8: nameId=1 -> "BigClass"
            writeUtf8Record(data, ID_SIZE, 1L, "BigClass");

            // LOAD_CLASS: serial=1, classId=0x1000, nameId=1
            writeLoadClassRecord(data, ID_SIZE, 1, 0x1000L, 0, 1L);

            // HEAP_DUMP_SEGMENT
            byte[] seg = buildHeapSegment();
            writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, seg);

            // HEAP_DUMP_END
            writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

            data.flush();
            return out.toByteArray();
        }

        private static byte[] buildHeapSegment() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // CLASS_DUMP for classId=0x1000
            s.writeByte(HPROF_GC_CLASS_DUMP);
            writeId8(s, 0x1000L);  // classId
            writeU4(s, 0);         // stackTraceSerial
            writeId8(s, 0L);       // superClassId
            writeId8(s, 0L);       // classLoader
            writeId8(s, 0L);       // signers
            writeId8(s, 0L);       // protectionDomain
            writeId8(s, 0L);       // reserved1
            writeId8(s, 0L);       // reserved2
            writeU4(s, 8);         // instanceSize (one long field)
            writeU2(s, 0);         // constantPoolSize
            writeU2(s, 0);         // staticFieldCount
            writeU2(s, 0);         // instanceFieldCount

            // INSTANCE_DUMP: objectId=0x2000, classId=0x1000, data=long(123456789)
            s.writeByte(HPROF_GC_INSTANCE_DUMP);
            writeId8(s, 0x2000L);  // objectId
            writeU4(s, 0);         // stackTraceSerial
            writeId8(s, 0x1000L);  // classId
            writeU4(s, 8);         // dataLength
            s.writeLong(123456789L); // long value

            s.flush();
            return seg.toByteArray();
        }

        /** Write a u4 (4-byte unsigned int). */
        private static void writeU4(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        /** Write a u8 (8-byte long). */
        private static void writeU8(DataOutputStream out, long value) throws IOException {
            out.writeLong(value);
        }

        /** Write a u2 (2-byte unsigned short). */
        private static void writeU2(DataOutputStream out, int value) throws IOException {
            out.writeShort(value & 0xFFFF);
        }

        /** Write an 8-byte identifier. */
        private static void writeId8(DataOutputStream out, long value) throws IOException {
            out.writeLong(value);
        }

        private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
            out.writeByte(tag);
            writeU4(out, time);
            writeU4(out, payload.length);
            out.write(payload);
        }

        private static void writeUtf8Record(DataOutputStream data, int idSize, long id, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeId8(p, id);
            p.write(bytes);
            p.flush();
            writeRecord(data, HPROF_UTF8, 0, payload.toByteArray());
        }

        private static void writeLoadClassRecord(DataOutputStream data, int idSize,
                                                  int serial, long classId, int stackSerial, long nameId) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeU4(p, serial);
            writeId8(p, classId);
            writeU4(p, stackSerial);
            writeId8(p, nameId);
            p.flush();
            writeRecord(data, HPROF_LOAD_CLASS, 0, payload.toByteArray());
        }
    }

    // =========================================================================
    // Scenario 2: HPROF_HEAP_DUMP (tag 0x0C) instead of HPROF_HEAP_DUMP_SEGMENT
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class HeapDumpTagTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-hprof-heap-dump", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void recordHistogramContainsHprofHeapDump() {
            assertTrue(containsTagName(report.recordHistogram(), "HPROF_HEAP_DUMP"),
                    "recordHistogram must contain HPROF_HEAP_DUMP (tag 0x0C)");
        }

        @Test
        void recordHistogramDoesNotContainSegment() {
            assertFalse(containsTagName(report.recordHistogram(), "HPROF_HEAP_DUMP_SEGMENT"),
                    "should not contain HPROF_HEAP_DUMP_SEGMENT when using HPROF_HEAP_DUMP");
        }

        @Test
        void subrecordHistogramContainsInstanceDump() {
            assertTrue(containsSubTagName(report.subrecordHistogram(), "HPROF_GC_INSTANCE_DUMP"),
                    "subrecordHistogram must contain HPROF_GC_INSTANCE_DUMP");
        }

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

            writeUtf8Record(data, 1L, "DumpClass");
            writeLoadClassRecord(data, 1, 0x100L, 0, 1L);

            // Use HPROF_HEAP_DUMP (0x0C) instead of HPROF_HEAP_DUMP_SEGMENT (0x1C)
            byte[] seg = buildHeapSegment();
            writeRecord(data, HPROF_HEAP_DUMP, 0, seg);

            writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

            data.flush();
            return out.toByteArray();
        }

        private static byte[] buildHeapSegment() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // CLASS_DUMP for classId=0x100
            s.writeByte(HPROF_GC_CLASS_DUMP);
            writeId(s, 0x100L);
            writeU4(s, 0);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeU4(s, 0);
            writeU2(s, 0);
            writeU2(s, 0);
            writeU2(s, 0);

            // INSTANCE_DUMP
            s.writeByte(HPROF_GC_INSTANCE_DUMP);
            writeId(s, 0x200L);
            writeU4(s, 0);
            writeId(s, 0x100L);
            writeU4(s, 0);

            s.flush();
            return seg.toByteArray();
        }

        private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
            out.writeByte(tag);
            writeU4(out, time);
            writeU4(out, payload.length);
            out.write(payload);
        }

        private static void writeId(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
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

        private static void writeUtf8Record(DataOutputStream data, long id, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeId(p, id);
            p.write(bytes);
            p.flush();
            writeRecord(data, HPROF_UTF8, 0, payload.toByteArray());
        }

        private static void writeLoadClassRecord(DataOutputStream data, int serial, long classId,
                                                  int stackSerial, long nameId) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeU4(p, serial);
            writeId(p, classId);
            writeU4(p, stackSerial);
            writeId(p, nameId);
            p.flush();
            writeRecord(data, HPROF_LOAD_CLASS, 0, payload.toByteArray());
        }
    }

    // =========================================================================
    // Scenario 3: Multiple primitive array element types
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class PrimArrayTypesTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-prim-arrays", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void subrecordHistogramContainsPrimArrayDump() {
            assertTrue(containsSubTagName(report.subrecordHistogram(), "HPROF_GC_PRIM_ARRAY_DUMP"),
                    "subrecordHistogram must contain HPROF_GC_PRIM_ARRAY_DUMP");
        }

        @Test
        void primArrayBytesNonZero() {
            assertTrue(report.sizeAttribution().heapObjectPrimArrayBytes() > 0,
                    "heapObjectPrimArrayBytes should be > 0");
        }

        @Test
        void matSizeNonZero() {
            assertTrue(report.sizeAttribution().matHeapSizeWithCompressedOops() > 0,
                    "matHeapSizeWithCompressedOops should be > 0 for primitive arrays");
        }

        @Test
        void topArraysContainsPrimArrays() {
            // All primitive array types (long, double, char, boolean) should result in top-array entries
            assertFalse(report.topArrays().isEmpty(),
                    "topArrays should contain the primitive array entries");
        }

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

            writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, buildHeapSegment());
            writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

            data.flush();
            return out.toByteArray();
        }

        private static byte[] buildHeapSegment() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // long[] — 8 bytes/elem, 4 elements
            writePrimArray(s, 0x301L, HPROF_TYPE_LONG, new long[]{1L, 2L, 3L, 4L});

            // double[] — 8 bytes/elem, 3 elements
            writePrimArrayDouble(s, 0x302L, new double[]{1.0, 2.0, 3.0});

            // char[] — 2 bytes/elem, 5 elements ('H','e','l','l','o')
            writePrimArrayChar(s, 0x303L, new char[]{'H', 'e', 'l', 'l', 'o'});

            // boolean[] — 1 byte/elem, 6 elements
            writePrimArrayBoolean(s, 0x304L, new boolean[]{true, false, true, false, true, true});

            s.flush();
            return seg.toByteArray();
        }

        private static void writePrimArray(DataOutputStream s, long arrayId, int typeCode, long[] longs) throws IOException {
            s.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
            writeId(s, arrayId);
            writeU4(s, 0);
            writeU4(s, longs.length);
            s.writeByte(typeCode); // HPROF_TYPE_LONG = 0x0B
            for (long v : longs) s.writeLong(v);
        }

        private static void writePrimArrayDouble(DataOutputStream s, long arrayId, double[] doubles) throws IOException {
            s.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
            writeId(s, arrayId);
            writeU4(s, 0);
            writeU4(s, doubles.length);
            s.writeByte(HPROF_TYPE_DOUBLE); // 0x07
            for (double v : doubles) s.writeDouble(v);
        }

        private static void writePrimArrayChar(DataOutputStream s, long arrayId, char[] chars) throws IOException {
            s.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
            writeId(s, arrayId);
            writeU4(s, 0);
            writeU4(s, chars.length);
            s.writeByte(HPROF_TYPE_CHAR); // 0x05
            for (char c : chars) s.writeChar(c);
        }

        private static void writePrimArrayBoolean(DataOutputStream s, long arrayId, boolean[] bools) throws IOException {
            s.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
            writeId(s, arrayId);
            writeU4(s, 0);
            writeU4(s, bools.length);
            s.writeByte(HPROF_TYPE_BOOLEAN); // 0x04
            for (boolean b : bools) s.writeByte(b ? 1 : 0);
        }

        private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
            out.writeByte(tag);
            writeU4(out, time);
            writeU4(out, payload.length);
            out.write(payload);
        }

        private static void writeId(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        private static void writeU4(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        private static void writeU8(DataOutputStream out, long value) throws IOException {
            out.writeLong(value);
        }
    }

    // =========================================================================
    // Scenario 4: All GC root types
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class AllGcRootTypesTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-gc-roots", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void gcRootBytesNonZero() {
            assertTrue(report.sizeAttribution().gcRootBytes() > 0,
                    "gcRootBytes must be > 0 when GC root subrecords are present");
        }

        @Test
        void subrecordHistogramContainsAllRootTypes() {
            var hist = report.subrecordHistogram();
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_UNKNOWN"),       "must have UNKNOWN");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_JNI_GLOBAL"),    "must have JNI_GLOBAL");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_JNI_LOCAL"),     "must have JNI_LOCAL");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_JAVA_FRAME"),    "must have JAVA_FRAME");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_NATIVE_STACK"),  "must have NATIVE_STACK");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_STICKY_CLASS"),  "must have STICKY_CLASS");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_THREAD_BLOCK"),  "must have THREAD_BLOCK");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_MONITOR_USED"),  "must have MONITOR_USED");
            assertTrue(containsSubTagName(hist, "HPROF_GC_ROOT_THREAD_OBJ"),    "must have THREAD_OBJ");
        }

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

            writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, buildHeapSegment());
            writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

            data.flush();
            return out.toByteArray();
        }

        private static byte[] buildHeapSegment() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // GC_ROOT_UNKNOWN: objectId
            s.writeByte(HPROF_GC_ROOT_UNKNOWN);
            writeId(s, 0x001L);

            // GC_ROOT_JNI_GLOBAL: objectId, refId
            s.writeByte(HPROF_GC_ROOT_JNI_GLOBAL);
            writeId(s, 0x002L);
            writeId(s, 0x003L);

            // GC_ROOT_JNI_LOCAL: objectId, threadSerial, frameNum
            s.writeByte(HPROF_GC_ROOT_JNI_LOCAL);
            writeId(s, 0x004L);
            writeU4(s, 1);
            writeU4(s, 0);

            // GC_ROOT_JAVA_FRAME: objectId, threadSerial, frameNum
            s.writeByte(HPROF_GC_ROOT_JAVA_FRAME);
            writeId(s, 0x005L);
            writeU4(s, 1);
            writeU4(s, 0);

            // GC_ROOT_NATIVE_STACK: objectId, threadSerial
            s.writeByte(HPROF_GC_ROOT_NATIVE_STACK);
            writeId(s, 0x006L);
            writeU4(s, 1);

            // GC_ROOT_STICKY_CLASS: objectId
            s.writeByte(HPROF_GC_ROOT_STICKY_CLASS);
            writeId(s, 0x007L);

            // GC_ROOT_THREAD_BLOCK: objectId, threadSerial
            s.writeByte(HPROF_GC_ROOT_THREAD_BLOCK);
            writeId(s, 0x008L);
            writeU4(s, 1);

            // GC_ROOT_MONITOR_USED: objectId
            s.writeByte(HPROF_GC_ROOT_MONITOR_USED);
            writeId(s, 0x009L);

            // GC_ROOT_THREAD_OBJ: objectId, threadSerial, stackSerial
            s.writeByte(HPROF_GC_ROOT_THREAD_OBJ);
            writeId(s, 0x00AL);
            writeU4(s, 1);
            writeU4(s, 0);

            s.flush();
            return seg.toByteArray();
        }

        private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
            out.writeByte(tag);
            writeU4(out, time);
            writeU4(out, payload.length);
            out.write(payload);
        }

        private static void writeId(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        private static void writeU4(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        private static void writeU8(DataOutputStream out, long value) throws IOException {
            out.writeLong(value);
        }
    }

    // =========================================================================
    // Scenario 5: HPROF_FRAME + HPROF_TRACE + HPROF_START_THREAD
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class FramesTracesThreadsTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-frames-traces", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void framesTracesThreadsBytesNonZero() {
            assertTrue(report.sizeAttribution().framesTracesThreadsBytes() > 0,
                    "framesTracesThreadsBytes must be > 0");
        }

        @Test
        void recordHistogramContainsFrame() {
            assertTrue(containsTagName(report.recordHistogram(), "HPROF_FRAME"),
                    "recordHistogram must contain HPROF_FRAME");
        }

        @Test
        void recordHistogramContainsTrace() {
            assertTrue(containsTagName(report.recordHistogram(), "HPROF_TRACE"),
                    "recordHistogram must contain HPROF_TRACE");
        }

        @Test
        void recordHistogramContainsStartThread() {
            assertTrue(containsTagName(report.recordHistogram(), "HPROF_START_THREAD"),
                    "recordHistogram must contain HPROF_START_THREAD");
        }

        @Test
        void utf8ReferencedBytesPositive() {
            assertTrue(report.utf8Analysis().referencedBytes() > 0,
                    "referencedBytes should be > 0 because UTF8 nameIds are used by FRAME/LOAD_CLASS");
        }

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

            // UTF8 name records
            // nameId=1 -> "MyThread", nameId=2 -> "myMethod", nameId=3 -> "()V",
            // nameId=4 -> "MyClass.java", nameId=5 -> "main", nameId=6 -> "workers"
            writeUtf8Record(data, 1L, "MyThread");
            writeUtf8Record(data, 2L, "myMethod");
            writeUtf8Record(data, 3L, "()V");
            writeUtf8Record(data, 4L, "MyClass.java");
            writeUtf8Record(data, 5L, "main");
            writeUtf8Record(data, 6L, "workers");
            writeUtf8Record(data, 7L, "MyClass");

            // LOAD_CLASS: serial=1, classId=0x100, nameId=7
            writeLoadClassRecord(data, 1, 0x100L, 0, 7L);

            // HPROF_FRAME: frameId=0xF1, methodNameId=2 "myMethod", methodSigId=3 "()V",
            //              sourceFileNameId=4 "MyClass.java", classSerial=1, lineNumber=42
            writeFrameRecord(data, 0xF1L, 2L, 3L, 4L, 1, 42);

            // HPROF_TRACE: stackTraceSerial=100, threadSerial=1, frames=[0xF1]
            writeTraceRecord(data, 100, 1, new long[]{0xF1L});

            // HPROF_START_THREAD: threadSerial=1, threadObjectId=0x500, stackTraceSerial=100,
            //                     threadNameId=1 "MyThread", threadGroupNameId=5 "main",
            //                     threadGroupParentNameId=6 "workers"
            writeStartThreadRecord(data, 1, 0x500L, 100, 1L, 5L, 6L);

            // Minimal heap
            writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, buildMinimalHeap());
            writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

            data.flush();
            return out.toByteArray();
        }

        private static byte[] buildMinimalHeap() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // CLASS_DUMP for classId=0x100
            s.writeByte(HPROF_GC_CLASS_DUMP);
            writeId(s, 0x100L);
            writeU4(s, 0);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeU4(s, 0);
            writeU2(s, 0);
            writeU2(s, 0);
            writeU2(s, 0);

            s.flush();
            return seg.toByteArray();
        }

        private static void writeFrameRecord(DataOutputStream data, long frameId,
                                              long methodNameId, long methodSigId,
                                              long sourceFileNameId, int classSerial, int lineNumber) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeId(p, frameId);
            writeId(p, methodNameId);
            writeId(p, methodSigId);
            writeId(p, sourceFileNameId);
            writeU4(p, classSerial);
            writeU4(p, lineNumber);
            p.flush();
            writeRecord(data, HPROF_FRAME, 0, payload.toByteArray());
        }

        private static void writeTraceRecord(DataOutputStream data, int stackTraceSerial,
                                              int threadSerial, long[] frameIds) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeU4(p, stackTraceSerial);
            writeU4(p, threadSerial);
            writeU4(p, frameIds.length);
            for (long frameId : frameIds) writeId(p, frameId);
            p.flush();
            writeRecord(data, HPROF_TRACE, 0, payload.toByteArray());
        }

        private static void writeStartThreadRecord(DataOutputStream data, int threadSerial,
                                                    long threadObjectId, int stackTraceSerial,
                                                    long threadNameId, long threadGroupNameId,
                                                    long threadGroupParentNameId) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeU4(p, threadSerial);
            writeId(p, threadObjectId);
            writeU4(p, stackTraceSerial);
            writeId(p, threadNameId);
            writeId(p, threadGroupNameId);
            writeId(p, threadGroupParentNameId);
            p.flush();
            writeRecord(data, HPROF_START_THREAD, 0, payload.toByteArray());
        }

        private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
            out.writeByte(tag);
            writeU4(out, time);
            writeU4(out, payload.length);
            out.write(payload);
        }

        private static void writeId(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
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

        private static void writeUtf8Record(DataOutputStream data, long id, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeId(p, id);
            p.write(bytes);
            p.flush();
            writeRecord(data, HPROF_UTF8, 0, payload.toByteArray());
        }

        private static void writeLoadClassRecord(DataOutputStream data, int serial, long classId,
                                                  int stackSerial, long nameId) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeU4(p, serial);
            writeId(p, classId);
            writeU4(p, stackSerial);
            writeId(p, nameId);
            p.flush();
            writeRecord(data, HPROF_LOAD_CLASS, 0, payload.toByteArray());
        }
    }

    // =========================================================================
    // Scenario 6: Large UTF8 section (isUnusuallyLarge)
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class LargeUtf8SectionTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-large-utf8", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void utf8IsUnusuallyLarge() {
            assertTrue(report.utf8Analysis().isUnusuallyLarge(),
                    "utf8Analysis.isUnusuallyLarge() must be true when UTF8 > 5% of file size");
        }

        @Test
        void utf8RecordCountCorrect() {
            // We write 20 UTF8 records
            assertEquals(20L, report.utf8Analysis().recordCount(),
                    "should have exactly 20 UTF8 records");
        }

        @Test
        void utf8TotalBytesLargerThan5Percent() {
            long utf8Total = report.utf8Analysis().totalBytes();
            long fileSize = report.fileSummary().fileSizeBytes();
            assertTrue(utf8Total > fileSize * 0.05,
                    "utf8TotalBytes (" + utf8Total + ") must exceed 5% of fileSize (" + fileSize + ")");
        }

        private static byte[] buildHprof() throws IOException {
            // Strategy: write 20 UTF8 records with very long strings (~200 bytes each),
            // then just a tiny HEAP_DUMP_END. The UTF8 section will vastly dominate.
            // 20 records * (9 + 4 + 200) = 20 * 213 = 4260 bytes of UTF8
            // Rest of file: header (27 bytes) + HEAP_DUMP_END (9 bytes) = 36 bytes
            // Total ~ 4296 bytes, UTF8 ratio = 4260/4296 ~ 99%  => isUnusuallyLarge = true

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

            String longString = "A".repeat(200); // 200-character string
            for (int i = 1; i <= 20; i++) {
                writeUtf8Record(data, (long) i, longString + i);
            }

            // HEAP_DUMP_END (no actual heap objects)
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
            out.writeInt((int) value);
        }

        private static void writeU4(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        private static void writeU8(DataOutputStream out, long value) throws IOException {
            out.writeLong(value);
        }

        private static void writeUtf8Record(DataOutputStream data, long id, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeId(p, id);
            p.write(bytes);
            p.flush();
            writeRecord(data, HPROF_UTF8, 0, payload.toByteArray());
        }
    }

    // =========================================================================
    // Scenario 7: Empty heap
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class EmptyHeapTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-empty-heap", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void topClassesEmpty() {
            assertTrue(report.topClasses().isEmpty(),
                    "topClasses must be empty for an empty heap");
        }

        @Test
        void heapObjectInstanceBytesZero() {
            assertEquals(0L, report.sizeAttribution().heapObjectInstanceBytes(),
                    "heapObjectInstanceBytes must be 0 for an empty heap");
        }

        @Test
        void heapObjectPrimArrayBytesZero() {
            assertEquals(0L, report.sizeAttribution().heapObjectPrimArrayBytes(),
                    "heapObjectPrimArrayBytes must be 0 for an empty heap");
        }

        @Test
        void heapDumpEndBytesNonZero() {
            assertTrue(report.sizeAttribution().heapDumpEndBytes() > 0,
                    "heapDumpEndBytes should be > 0 (the HEAP_DUMP_END record exists)");
        }

        @Test
        void noTrailingBytes() {
            assertNull(report.trailingBytes(), "trailingBytes must be null for a well-formed empty heap");
        }

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            // Just header + HEAP_DUMP_END, nothing else
            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

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

        private static void writeU4(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
        }

        private static void writeU8(DataOutputStream out, long value) throws IOException {
            out.writeLong(value);
        }
    }

    // =========================================================================
    // Scenario 8: Multiple heap dump segments
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    static class MultipleSegmentsTest {

        private static DiagnosticReport report;
        private static Path tempFile;

        @BeforeAll
        static void buildAndDiagnose() throws Exception {
            byte[] hprof = buildHprof();
            tempFile = Files.createTempFile("examples-multi-segments", ".hprof");
            Files.write(tempFile, hprof);
            report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
        }

        @AfterAll
        static void cleanup() throws Exception {
            if (tempFile != null) Files.deleteIfExists(tempFile);
        }

        @Test
        void recordHistogramContainsTwoSegments() {
            var stat = report.recordHistogram().stream()
                    .filter(e -> "HPROF_HEAP_DUMP_SEGMENT".equals(e.tagName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(stat, "recordHistogram must contain HPROF_HEAP_DUMP_SEGMENT");
            assertEquals(2L, stat.count(), "should have exactly 2 HPROF_HEAP_DUMP_SEGMENT records");
        }

        @Test
        void subrecordHistogramCountsAcrossBothSegments() {
            // Each segment contributes one INSTANCE_DUMP, so total count should be 2
            var stat = report.subrecordHistogram().stream()
                    .filter(e -> "HPROF_GC_INSTANCE_DUMP".equals(e.subTagName()))
                    .findFirst()
                    .orElse(null);
            assertNotNull(stat, "subrecordHistogram must contain HPROF_GC_INSTANCE_DUMP");
            assertEquals(2L, stat.count(),
                    "both segments should contribute to the INSTANCE_DUMP count (expected 2, got " + (stat != null ? stat.count() : "null") + ")");
        }

        @Test
        void bothInstancesCountedInTopClasses() {
            // We have 2 instances of class 0x100 spread across 2 segments
            var myClass = report.topClasses().stream()
                    .filter(c -> c.classId() == 0x100L)
                    .findFirst()
                    .orElse(null);
            assertNotNull(myClass, "topClasses must contain class 0x100");
            assertEquals(2L, myClass.instanceCount(),
                    "should have 2 instances of class 0x100 (one from each segment)");
        }

        private static byte[] buildHprof() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            DataOutputStream data = new DataOutputStream(out);

            data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
            writeU4(data, 4);
            writeU8(data, 0L);

            writeUtf8Record(data, 1L, "SegClass");
            writeLoadClassRecord(data, 1, 0x100L, 0, 1L);

            // Segment 1: CLASS_DUMP + first INSTANCE_DUMP
            writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, buildSegment1());

            // Segment 2: second INSTANCE_DUMP (same classId, different objectId)
            writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, buildSegment2());

            writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

            data.flush();
            return out.toByteArray();
        }

        private static byte[] buildSegment1() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // CLASS_DUMP for classId=0x100
            s.writeByte(HPROF_GC_CLASS_DUMP);
            writeId(s, 0x100L);
            writeU4(s, 0);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeId(s, 0L);
            writeU4(s, 0);
            writeU2(s, 0);
            writeU2(s, 0);
            writeU2(s, 0);

            // First INSTANCE_DUMP
            s.writeByte(HPROF_GC_INSTANCE_DUMP);
            writeId(s, 0x201L);
            writeU4(s, 0);
            writeId(s, 0x100L);
            writeU4(s, 0);

            s.flush();
            return seg.toByteArray();
        }

        private static byte[] buildSegment2() throws IOException {
            ByteArrayOutputStream seg = new ByteArrayOutputStream();
            DataOutputStream s = new DataOutputStream(seg);

            // Second INSTANCE_DUMP in a separate segment
            s.writeByte(HPROF_GC_INSTANCE_DUMP);
            writeId(s, 0x202L);
            writeU4(s, 0);
            writeId(s, 0x100L);
            writeU4(s, 0);

            s.flush();
            return seg.toByteArray();
        }

        private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
            out.writeByte(tag);
            writeU4(out, time);
            writeU4(out, payload.length);
            out.write(payload);
        }

        private static void writeId(DataOutputStream out, long value) throws IOException {
            out.writeInt((int) value);
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

        private static void writeUtf8Record(DataOutputStream data, long id, String value) throws IOException {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeId(p, id);
            p.write(bytes);
            p.flush();
            writeRecord(data, HPROF_UTF8, 0, payload.toByteArray());
        }

        private static void writeLoadClassRecord(DataOutputStream data, int serial, long classId,
                                                  int stackSerial, long nameId) throws IOException {
            ByteArrayOutputStream payload = new ByteArrayOutputStream();
            DataOutputStream p = new DataOutputStream(payload);
            writeU4(p, serial);
            writeId(p, classId);
            writeU4(p, stackSerial);
            writeId(p, nameId);
            p.flush();
            writeRecord(data, HPROF_LOAD_CLASS, 0, payload.toByteArray());
        }
    }

    // =========================================================================
    // Shared helper predicates (static, accessible to all nested classes)
    // =========================================================================

    static boolean containsTagName(java.util.List<DiagnosticReport.RecordStat> hist, String name) {
        return hist.stream().anyMatch(e -> name.equals(e.tagName()));
    }

    static boolean containsSubTagName(java.util.List<DiagnosticReport.SubrecordStat> hist, String name) {
        return hist.stream().anyMatch(e -> name.equals(e.subTagName()));
    }
}
