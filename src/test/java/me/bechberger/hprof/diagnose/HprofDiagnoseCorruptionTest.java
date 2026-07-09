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
 * Synthetic-corruption tests for {@link HprofDiagnose}: each test builds an
 * intentionally abnormal HPROF and asserts the report flags it correctly.
 */
class HprofDiagnoseCorruptionTest {

    // =========================================================================
    // Test 1: duplicate header (concatenated dump)
    // =========================================================================

    @Test
    void duplicateHeaderDetected() throws Exception {
        // Build a valid minimal HPROF block, then concatenate it with itself.
        byte[] singleBlock = buildMinimalHprof();
        byte[] doubleBlock = concat(singleBlock, singleBlock);

        Path tmp = Files.createTempFile("corrupt-dup-header", ".hprof");
        try {
            Files.write(tmp, doubleBlock);
            DiagnosticReport report = HprofDiagnose.diagnose(tmp, new HprofDiagnose.Options());

            var duplicates = report.duplicateHeaders();
            assertNotNull(duplicates);
            // There should be at least one occurrence at an offset > 0
            long extraHeaders = duplicates.stream()
                    .filter(h -> h.decompressedOffset() > 0)
                    .count();
            assertTrue(extraHeaders >= 1, "should detect at least one duplicate header past offset 0");

            // The duplicate should be at the boundary between the two blocks
            long expectedOffset = singleBlock.length;
            boolean foundAtExpectedOffset = duplicates.stream()
                    .anyMatch(h -> h.decompressedOffset() == expectedOffset);
            assertTrue(foundAtExpectedOffset,
                    "duplicate header should be at offset " + expectedOffset +
                    " (got offsets: " + duplicates.stream()
                            .map(h -> String.valueOf(h.decompressedOffset()))
                            .reduce((a, b) -> a + ", " + b).orElse("none") + ")");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // =========================================================================
    // Test 2: HEAP_DUMP_SEGMENT length mismatch
    // =========================================================================

    @Test
    void segmentLengthMismatchDetected() throws Exception {
        // Build a HEAP_DUMP_SEGMENT that declares length=100 but contains only
        // a single STICKY_CLASS root (1 + 4 = 5 bytes with idSize=4).
        byte[] hprof = buildHprofWithOversizedSegment();

        Path tmp = Files.createTempFile("corrupt-seg-length", ".hprof");
        try {
            Files.write(tmp, hprof);
            DiagnosticReport report = HprofDiagnose.diagnose(tmp, new HprofDiagnose.Options());

            var issues = report.segmentIssues();
            assertNotNull(issues, "segmentIssues should not be null");
            assertFalse(issues.isEmpty(), "segmentIssues should be non-empty for a mismatched segment");

            DiagnosticReport.SegmentIssue issue = issues.get(0);
            assertTrue(issue.declaredLength() > issue.consumedBytes(),
                    "declaredLength (" + issue.declaredLength() +
                    ") should be > consumedBytes (" + issue.consumedBytes() + ")");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // =========================================================================
    // Test 3: trailing bytes after HEAP_DUMP_END
    // =========================================================================

    @Test
    void trailingBytesDetected() throws Exception {
        byte[] minimal = buildMinimalHprof();
        byte[] trailing = new byte[50]; // 50 zero bytes
        byte[] hprof = concat(minimal, trailing);

        Path tmp = Files.createTempFile("corrupt-trailing", ".hprof");
        try {
            Files.write(tmp, hprof);
            DiagnosticReport report = HprofDiagnose.diagnose(tmp, new HprofDiagnose.Options());

            assertNotNull(report.trailingBytes(),
                    "trailingBytes should not be null when there are extra bytes after HEAP_DUMP_END");
            // Either byteCount > 0 or reason is non-null (or both)
            boolean hasEvidence = (report.trailingBytes().byteCount() > 0)
                    || (report.trailingBytes().reason() != null);
            assertTrue(hasEvidence,
                    "trailingBytes should have byteCount > 0 or a non-null reason");

            // It must NOT look like a second HPROF header (zeros ≠ magic string)
            long extraHeaders = report.duplicateHeaders().stream()
                    .filter(h -> h.decompressedOffset() > 0)
                    .count();
            assertEquals(0L, extraHeaders,
                    "50 trailing zero bytes should not be detected as a duplicate header");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // =========================================================================
    // Test 4: duplicate object IDs
    // =========================================================================

    @Test
    void duplicateObjectIdsDetected() throws Exception {
        // Build a HEAP_DUMP_SEGMENT with two INSTANCE_DUMP records sharing the same objectId.
        byte[] hprof = buildHprofWithDuplicateObjectId(0x200);

        Path tmp = Files.createTempFile("corrupt-dup-ids", ".hprof");
        try {
            Files.write(tmp, hprof);
            HprofDiagnose.Options options = new HprofDiagnose.Options();
            options.detectDuplicateIds = true;
            DiagnosticReport report = HprofDiagnose.diagnose(tmp, options);

            var dups = report.duplicateIds();
            assertNotNull(dups, "duplicateIds should not be null (OOM should not occur for a tiny dump)");
            assertFalse(dups.isEmpty(), "duplicateIds should be non-empty");

            DiagnosticReport.DuplicateId dup = dups.stream()
                    .filter(d -> d.objectId() == 0x200L)
                    .findFirst()
                    .orElse(null);
            assertNotNull(dup, "should have a DuplicateId entry for objectId=0x200");
            assertTrue(dup.occurrenceCount() >= 1,
                    "occurrenceCount should be >= 1 for a single duplication");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // =========================================================================
    // HPROF builder helpers
    // =========================================================================

    /**
     * Builds a valid minimal HPROF: header + 1 UTF8 + HEAP_DUMP_END.
     */
    private static byte[] buildMinimalHprof() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);    // idSize
        writeU8(data, 0L);   // timestamp

        // One UTF8 record
        writeUtf8Record(data, 1, "Hello");

        // HEAP_DUMP_END
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    /**
     * Builds an HPROF with a HEAP_DUMP_SEGMENT that declares length=100 but contains
     * only a single GC_ROOT_STICKY_CLASS record (5 bytes with idSize=4).
     */
    private static byte[] buildHprofWithOversizedSegment() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);
        writeU8(data, 0L);

        // A HEAP_DUMP_SEGMENT with declared length 100 but actual content of 5 bytes:
        // 1-byte subtag (STICKY_CLASS=0x05) + 4-byte id = 5 bytes total
        ByteArrayOutputStream segContent = new ByteArrayOutputStream();
        DataOutputStream seg = new DataOutputStream(segContent);
        seg.writeByte(HPROF_GC_ROOT_STICKY_CLASS);
        writeId(seg, 0x100L);
        seg.flush();
        byte[] actualContent = segContent.toByteArray(); // 5 bytes

        // Pad to 100 bytes so the record length is valid but mismatched
        byte[] paddedContent = new byte[100];
        System.arraycopy(actualContent, 0, paddedContent, 0, actualContent.length);
        // Remaining 95 bytes are zeros — these will be unrecognised/padding

        writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, paddedContent);
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    /**
     * Builds an HPROF with two INSTANCE_DUMP records sharing the same objectId.
     */
    private static byte[] buildHprofWithDuplicateObjectId(long duplicatedId) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(out);

        data.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(data, 4);
        writeU8(data, 0L);

        // CLASS_DUMP for class 0x100
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        DataOutputStream seg = new DataOutputStream(segment);

        seg.writeByte(HPROF_GC_CLASS_DUMP);
        writeId(seg, 0x100L);
        writeU4(seg, 0);       // stackTraceSerial
        writeId(seg, 0L);      // superClassId
        writeId(seg, 0L);      // classLoader
        writeId(seg, 0L);      // signers
        writeId(seg, 0L);      // protectionDomain
        writeId(seg, 0L);      // reserved1
        writeId(seg, 0L);      // reserved2
        writeU4(seg, 0);       // instanceSize = 0
        writeU2(seg, 0);       // constantPoolSize
        writeU2(seg, 0);       // staticFieldCount
        writeU2(seg, 0);       // instanceFieldCount

        // First INSTANCE_DUMP with duplicatedId
        seg.writeByte(HPROF_GC_INSTANCE_DUMP);
        writeId(seg, duplicatedId);
        writeU4(seg, 0);       // stackTraceSerial
        writeId(seg, 0x100L);  // classId
        writeU4(seg, 0);       // dataLength = 0

        // Second INSTANCE_DUMP with same duplicatedId
        seg.writeByte(HPROF_GC_INSTANCE_DUMP);
        writeId(seg, duplicatedId);
        writeU4(seg, 0);
        writeId(seg, 0x100L);
        writeU4(seg, 0);

        seg.flush();
        writeRecord(data, HPROF_HEAP_DUMP_SEGMENT, 0, segment.toByteArray());
        writeRecord(data, HPROF_HEAP_DUMP_END, 0, new byte[0]);

        data.flush();
        return out.toByteArray();
    }

    // =========================================================================
    // Low-level write helpers
    // =========================================================================

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

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
