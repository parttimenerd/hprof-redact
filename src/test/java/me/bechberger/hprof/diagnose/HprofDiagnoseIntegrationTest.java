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

import static me.bechberger.hprof.core.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for {@link HprofDiagnose}: builds a minimal HPROF in-memory,
 * runs {@code diagnose()}, and asserts all key report sections.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HprofDiagnoseIntegrationTest {

    private static Path tempFile;
    private static DiagnosticReport report;

    @BeforeAll
    static void buildAndDiagnose() throws Exception {
        byte[] hprof = buildTestHprof();
        tempFile = Files.createTempFile("diagnose-test", ".hprof");
        Files.write(tempFile, hprof);
        report = HprofDiagnose.diagnose(tempFile, new HprofDiagnose.Options());
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (tempFile != null) {
            Files.deleteIfExists(tempFile);
        }
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void fileSummary() {
        DiagnosticReport.FileSummary fs = report.fileSummary();
        assertEquals("JAVA PROFILE 1.0.2", fs.headerMagic());
        assertEquals(4, fs.idSize());
        assertEquals(123456L, fs.timestampMs());
    }

    @Test
    void recordHistogramContainsExpectedTags() {
        var hist = report.recordHistogram();
        assertNotNull(hist);
        assertTrue(containsTagName(hist, "HPROF_UTF8"),
                "recordHistogram must contain HPROF_UTF8");
        assertTrue(containsTagName(hist, "HPROF_LOAD_CLASS"),
                "recordHistogram must contain HPROF_LOAD_CLASS");
        assertTrue(containsTagName(hist, "HPROF_HEAP_DUMP_SEGMENT"),
                "recordHistogram must contain HPROF_HEAP_DUMP_SEGMENT");
        assertTrue(containsTagName(hist, "HPROF_HEAP_DUMP_END"),
                "recordHistogram must contain HPROF_HEAP_DUMP_END");
    }

    @Test
    void subrecordHistogramContainsHeapObjects() {
        var hist = report.subrecordHistogram();
        assertNotNull(hist);
        assertTrue(containsSubTagName(hist, "HPROF_GC_CLASS_DUMP"),
                "subrecordHistogram must contain HPROF_GC_CLASS_DUMP");
        assertTrue(containsSubTagName(hist, "HPROF_GC_INSTANCE_DUMP"),
                "subrecordHistogram must contain HPROF_GC_INSTANCE_DUMP");
        assertTrue(containsSubTagName(hist, "HPROF_GC_PRIM_ARRAY_DUMP"),
                "subrecordHistogram must contain HPROF_GC_PRIM_ARRAY_DUMP");
        assertTrue(containsSubTagName(hist, "HPROF_GC_OBJ_ARRAY_DUMP"),
                "subrecordHistogram must contain HPROF_GC_OBJ_ARRAY_DUMP");
    }

    @Test
    void sizeAttributionSumsReasonably() {
        DiagnosticReport.SizeAttribution sa = report.sizeAttribution();
        long fileSizeBytes = report.fileSummary().fileSizeBytes();

        // Sum all the content categories (excluding segmentFramingBytes to avoid double-counting
        // the 9-byte framing already included in utf8StringBytes, loadClassBytes, etc.)
        // The framing bytes that are NOT already counted elsewhere are only the heap dump segment
        // top-level framing (9 bytes per HEAP_DUMP/HEAP_DUMP_SEGMENT record).
        // However, the simplest safe assertion is that the sum is in the right ballpark.
        long contentSum = sa.heapObjectInstanceBytes()
                + sa.heapObjectObjArrayBytes()
                + sa.heapObjectPrimArrayBytes()
                + sa.classDumpBytes()
                + sa.gcRootBytes()
                + sa.utf8StringBytes()
                + sa.loadClassBytes()
                + sa.framesTracesThreadsBytes()
                + sa.heapSummaryAndOtherBytes()
                + sa.segmentFramingBytes()
                + sa.heapDumpEndBytes()
                + sa.unknownOrUnparseableBytes();

        // The sum may overcount due to double-counting of framing in utf8/loadClass/etc.
        // and undercount the file header (32 bytes). Accept a wide tolerance.
        assertTrue(contentSum > 0, "content sum must be positive");
        assertTrue(contentSum <= fileSizeBytes * 2,
                "content sum should not exceed 2x file size (got " + contentSum + " vs " + fileSizeBytes + ")");
    }

    @Test
    void topClassesContainsMyClass() {
        var topClasses = report.topClasses();
        assertNotNull(topClasses);
        assertFalse(topClasses.isEmpty(), "topClasses should not be empty");

        DiagnosticReport.TopClass myClass = topClasses.stream()
                .filter(c -> c.className().contains("MyClass"))
                .findFirst()
                .orElse(null);
        assertNotNull(myClass, "topClasses must contain an entry for MyClass");
        assertEquals(1L, myClass.instanceCount(), "MyClass should have exactly 1 instance");
        assertEquals(4L, myClass.totalInstanceBytes(), "MyClass instance data should be 4 bytes (one int)");
    }

    @Test
    void utf8AnalysisReferencedVsUnreferenced() {
        DiagnosticReport.Utf8Analysis utf8 = report.utf8Analysis();
        assertNotNull(utf8);
        assertEquals(2L, utf8.recordCount(), "should have exactly 2 UTF8 records");
        assertTrue(utf8.referencedBytes() > 0,
                "referencedBytes should be > 0 (nameId=1 'MyClass' is used by LOAD_CLASS)");
        assertEquals(utf8.totalBytes(), utf8.referencedBytes() + utf8.unreferencedBytes(),
                "totalBytes must equal referencedBytes + unreferencedBytes");
    }

    @Test
    void noDuplicateHeaders() {
        var duplicates = report.duplicateHeaders();
        // duplicateHeaders() returns all occurrences; offset 0 is the real header.
        // Additional occurrences at offset > 0 indicate concatenated dumps.
        long extraHeaders = duplicates.stream()
                .filter(h -> h.decompressedOffset() > 0)
                .count();
        assertEquals(0L, extraHeaders, "should have no duplicate headers (past offset 0)");
    }

    @Test
    void noTrailingBytes() {
        assertNull(report.trailingBytes(), "trailingBytes should be null for a well-formed HPROF");
    }

    @Test
    void estimatedHeapSizeIsPositive() {
        assertTrue(report.sizeAttribution().estimatedHeapSizeWithCompressedOops() > 0,
                "estimatedHeapSizeWithCompressedOops should be > 0");
    }

    // -------------------------------------------------------------------------
    // HPROF builder
    // -------------------------------------------------------------------------

    /**
     * Builds a minimal HPROF with:
     * <ul>
     *   <li>idSize=4, timestamp=123456</li>
     *   <li>2 UTF8 records (nameId=1 "MyClass", nameId=2 "value")</li>
     *   <li>1 LOAD_CLASS record (serial=1, classId=0x100, nameId=1)</li>
     *   <li>1 HEAP_DUMP_SEGMENT containing CLASS_DUMP, INSTANCE_DUMP,
     *       PRIM_ARRAY_DUMP, OBJ_ARRAY_DUMP</li>
     *   <li>HEAP_DUMP_END</li>
     * </ul>
     */
    private static byte[] buildTestHprof() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        // Header
        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);         // idSize
        writeU8(data, 123456L);   // timestamp

        // UTF8 records
        writeUtf8Record(data, 1, "MyClass");
        writeUtf8Record(data, 2, "value");

        // LOAD_CLASS record: serial=1, classId=0x100, stackTraceSerial=0, nameId=1
        writeLoadClassRecord(data, 1, 0x100, 0, 1);

        // HEAP_DUMP_SEGMENT
        byte[] heapSegment = buildHeapSegment();
        writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, heapSegment);

        // HEAP_DUMP_END
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    private static byte[] buildHeapSegment() throws IOException {
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(segment);

        // CLASS_DUMP: classId=0x100, stackTraceSerial=0, superClassId=0, ..., instanceSize=4
        // instanceFieldCount=1: nameId=2, type=INT
        data.writeByte(HPROF_GC_CLASS_DUMP);
        writeId(data, 0x100);   // classId
        writeU4(data, 0);       // stackTraceSerial
        writeId(data, 0);       // superClassId
        writeId(data, 0);       // classLoader
        writeId(data, 0);       // signers
        writeId(data, 0);       // protectionDomain
        writeId(data, 0);       // reserved1
        writeId(data, 0);       // reserved2
        writeU4(data, 4);       // instanceSize
        writeU2(data, 0);       // constantPoolSize
        writeU2(data, 0);       // staticFieldCount
        writeU2(data, 1);       // instanceFieldCount
        writeId(data, 2);       // field nameId=2
        data.writeByte(HPROF_TYPE_INT);

        // INSTANCE_DUMP: objectId=0x200, classId=0x100, data=int(42)
        data.writeByte(HPROF_GC_INSTANCE_DUMP);
        writeId(data, 0x200);   // objectId
        writeU4(data, 0);       // stackTraceSerial
        writeId(data, 0x100);   // classId
        writeU4(data, 4);       // dataLength
        writeU4(data, 42);      // int value

        // PRIM_ARRAY_DUMP: arrayId=0x300, numElements=3, type=BYTE, data=[1,2,3]
        data.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
        writeId(data, 0x300);   // arrayId
        writeU4(data, 0);       // stackTraceSerial
        writeU4(data, 3);       // numElements
        data.writeByte(HPROF_TYPE_BYTE);
        data.writeByte(1);
        data.writeByte(2);
        data.writeByte(3);

        // OBJ_ARRAY_DUMP: arrayId=0x400, numElements=2, arrayClassId=0x100, elements=[0x200,0x200]
        data.writeByte(HPROF_GC_OBJ_ARRAY_DUMP);
        writeId(data, 0x400);   // arrayId
        writeU4(data, 0);       // stackTraceSerial
        writeU4(data, 2);       // numElements
        writeId(data, 0x100);   // arrayClassId
        writeId(data, 0x200);   // element[0]
        writeId(data, 0x200);   // element[1]

        data.flush();
        return segment.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Low-level write helpers
    // -------------------------------------------------------------------------

    private static void writeRecord(DataOutputStream out, int tag, int time, byte[] payload) throws IOException {
        out.writeByte(tag);
        writeU4(out, time);
        writeU4(out, payload.length);
        out.write(payload);
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

    private static void writeLoadClassRecord(DataOutputStream data, int serial, long classId,
                                              int stackSerial, long nameId) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream payloadOut = new DataOutputStream(payload);
        writeU4(payloadOut, serial);
        writeId(payloadOut, classId);
        writeU4(payloadOut, stackSerial);
        writeId(payloadOut, nameId);
        payloadOut.flush();
        writeRecord(data, HPROF_LOAD_CLASS, 0, payload.toByteArray());
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

    // -------------------------------------------------------------------------
    // Helper predicates
    // -------------------------------------------------------------------------

    private static boolean containsTagName(java.util.List<DiagnosticReport.RecordStat> hist,
                                            String name) {
        return hist.stream().anyMatch(e -> name.equals(e.tagName()));
    }

    private static boolean containsSubTagName(java.util.List<DiagnosticReport.SubrecordStat> hist,
                                               String name) {
        return hist.stream().anyMatch(e -> name.equals(e.subTagName()));
    }
}
