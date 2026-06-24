/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import java.util.List;

/**
 * Top-level record containing all sections produced by an HPROF diagnostic pass.
 *
 * @param problems           detected problems and anomalies, most severe first
 * @param fileSummary        summary of the file header and basic metadata
 * @param recordHistogram    one entry per top-level tag seen in the file
 * @param subrecordHistogram one entry per heap-dump subtag seen
 * @param sizeAttribution    breakdown of file bytes by category
 * @param utf8Analysis       analysis of HPROF_UTF8 records
 * @param topClasses         largest classes by total instance bytes
 * @param topArrays          largest arrays (primitive and object) by disk bytes, largest first
 * @param segmentIssues      segments whose consumed bytes differ from declared length
 * @param duplicateHeaders   additional HPROF magic strings found past offset 0
 * @param trailingBytes      bytes after the last parseable record, or null if none
 * @param duplicateIds       duplicate object IDs; empty if not requested, null if OOM aborted
 * @param duplicateIdWarning warning message when OOM aborted duplicate-ID scan, null otherwise
 */
public record DiagnosticReport(
        List<Problem> problems,
        FileSummary fileSummary,
        List<RecordStat> recordHistogram,
        List<SubrecordStat> subrecordHistogram,
        SizeAttribution sizeAttribution,
        Utf8Analysis utf8Analysis,
        List<TopClass> topClasses,
        List<TopArray> topArrays,
        List<SegmentIssue> segmentIssues,
        List<HeaderOccurrence> duplicateHeaders,
        TrailingBytes trailingBytes,
        List<DuplicateId> duplicateIds,
        String duplicateIdWarning
) {

    /**
     * A detected problem or anomaly in the HPROF file.
     *
     * @param severity    severity level
     * @param code        machine-readable code, e.g. {@code "CONCATENATED_DUMP"}
     * @param title       one-line summary, e.g. {@code "Concatenated dump detected"}
     * @param description full explanation with impact and recommended action
     */
    public record Problem(Severity severity, String code, String title, String description) {
        public enum Severity { ERROR, WARNING, INFO }
    }

    /**
     * Summary of the HPROF file header and basic file metadata.
     *
     * @param filePath      path of the HPROF file as given to the tool
     * @param fileSizeBytes total size of the file in bytes
     * @param headerMagic   magic string from the HPROF header, e.g. {@code "JAVA PROFILE 1.0.2"}
     * @param idSize        identifier size in bytes (4 or 8)
     * @param timestampMs   epoch-millisecond timestamp from the HPROF header
     */
    public record FileSummary(
            String filePath,
            long fileSizeBytes,
            String headerMagic,
            int idSize,
            long timestampMs
    ) {}

    /**
     * Statistics for one top-level HPROF record tag.
     *
     * @param tag        numeric tag value
     * @param tagName    human-readable name, e.g. {@code "HPROF_UTF8"}
     * @param count      number of records with this tag
     * @param totalBytes total bytes occupied by all records of this tag,
     *                   including the 9-byte framing per record
     */
    public record RecordStat(
            int tag,
            String tagName,
            long count,
            long totalBytes
    ) {}

    /**
     * Statistics for one heap-dump subrecord type.
     *
     * @param subTag      numeric subtag value
     * @param subTagName  human-readable name, e.g. {@code "HPROF_GC_INSTANCE_DUMP"}
     * @param count       number of subrecords with this subtag
     * @param totalBytes  total bytes for all subrecords of this type,
     *                    including the 1-byte subtag prefix per subrecord
     */
    public record SubrecordStat(
            int subTag,
            String subTagName,
            long count,
            long totalBytes
    ) {}

    /**
     * Breakdown of file bytes by logical category.
     *
     * <p>The sum of all fields should equal the total file size (modulo any
     * unknown/unparseable bytes that cannot be attributed to a specific category).
     *
     * @param heapObjectInstanceBytes       sum of on-disk bytes for HPROF_GC_INSTANCE_DUMP subrecords
     *                                      (idSize + 4 + idSize + 4 + dataLength per record)
     * @param heapObjectObjArrayBytes       sum of on-disk bytes for HPROF_GC_OBJ_ARRAY_DUMP subrecords
     * @param heapObjectPrimArrayBytes      sum of on-disk bytes for HPROF_GC_PRIM_ARRAY_DUMP subrecords
     * @param classDumpBytes                sum of on-disk bytes for HPROF_GC_CLASS_DUMP subrecords
     * @param gcRootBytes                   sum of on-disk bytes for all GC-root subrecords
     * @param utf8StringBytes               total HPROF_UTF8 record bytes including 9-byte framing
     * @param loadClassBytes                total bytes for HPROF_LOAD_CLASS records
     * @param framesTracesThreadsBytes      total bytes for HPROF_FRAME + HPROF_TRACE +
     *                                      HPROF_START_THREAD + HPROF_END_THREAD records
     * @param heapSummaryAndOtherBytes      bytes for HPROF_HEAP_SUMMARY + HPROF_CPU_SAMPLES +
     *                                      HPROF_ALLOC_SITES + HPROF_CONTROL_SETTINGS +
     *                                      HPROF_UNLOAD_CLASS records
     * @param segmentFramingBytes           overhead from record/subrecord framing:
     *                                      9 bytes per top-level record plus 1 byte per heap-dump subtag
     * @param heapDumpEndBytes              bytes for HPROF_HEAP_DUMP_END records
     * @param unknownOrUnparseableBytes     bytes that could not be attributed to any known record type
     * @param matHeapSizeWithCompressedOops estimated MAT heap size assuming reference size = 4 bytes
     * @param matHeapSizeWithoutCompressedOops estimated MAT heap size assuming reference size = idSize bytes
     * @param objectIdOverheadBytes         bytes consumed by HPROF subrecord headers that MAT does not count:
     *                                      for each INSTANCE_DUMP: {@code 1 (subtag) + 2×idSize + 8} minus
     *                                      {@code dataLength}. With idSize=8 this is 25 bytes per object.
     *                                      At 500 million objects ≈ 12.5 GB overhead not visible in MAT.
     * @param compressedRefExpansionBytes   bytes consumed by reference expansion in OBJ_ARRAY_DUMP when
     *                                      idSize=8 but the JVM uses compressed oops (refSize=4): each
     *                                      element is stored as 8 bytes on disk but MAT counts only 4.
     *                                      Overhead = {@code numElements × (idSize − 4)} per array.
     */
    public record SizeAttribution(
            long heapObjectInstanceBytes,
            long heapObjectObjArrayBytes,
            long heapObjectPrimArrayBytes,
            long classDumpBytes,
            long gcRootBytes,
            long utf8StringBytes,
            long loadClassBytes,
            long framesTracesThreadsBytes,
            long heapSummaryAndOtherBytes,
            long segmentFramingBytes,
            long heapDumpEndBytes,
            long unknownOrUnparseableBytes,
            long matHeapSizeWithCompressedOops,
            long matHeapSizeWithoutCompressedOops,
            long objectIdOverheadBytes,
            long compressedRefExpansionBytes
    ) {}

    /**
     * Analysis of the HPROF_UTF8 string-table section.
     *
     * @param totalBytes          total bytes of all HPROF_UTF8 records including 9-byte framing
     * @param recordCount         number of HPROF_UTF8 records
     * @param referencedBytes     bytes of UTF-8 records whose nameId is referenced by
     *                            LOAD_CLASS, HPROF_FRAME, or class field-name entries
     * @param unreferencedBytes   {@code totalBytes - referencedBytes}
     * @param largestRecordBytes  raw payload bytes of the single largest UTF-8 record (excluding framing)
     * @param largestRecordSample first 80 characters of decoded text from the largest record
     * @param isUnusuallyLarge    {@code true} if {@code totalBytes} exceeds 5 % of the file size
     */
    public record Utf8Analysis(
            long totalBytes,
            long recordCount,
            long referencedBytes,
            long unreferencedBytes,
            long largestRecordBytes,
            String largestRecordSample,
            boolean isUnusuallyLarge
    ) {}

    /**
     * A heap-dump segment whose consumed bytes differ from the declared length.
     *
     * @param segmentDecompressedOffset byte offset in the decompressed stream where the
     *                                  HEAP_DUMP_SEGMENT record starts
     * @param declaredLength            body length as recorded in the record header
     * @param consumedBytes             actual bytes consumed while parsing subrecords
     * @param description               human-readable explanation of the discrepancy
     */
    public record SegmentIssue(
            long segmentDecompressedOffset,
            long declaredLength,
            long consumedBytes,
            String description
    ) {}

    /**
     * An occurrence of the HPROF magic string at a position other than offset 0.
     *
     * @param decompressedOffset byte offset in the decompressed stream where the magic was found
     * @param magic              the actual magic string found at that offset
     */
    public record HeaderOccurrence(
            long decompressedOffset,
            String magic
    ) {}

    /**
     * Bytes found after the last parseable record in the file.
     *
     * @param offset     decompressed byte offset where the trailing bytes begin
     * @param byteCount  total number of trailing bytes
     * @param firstBytes up to the first 32 trailing bytes
     * @param lastBytes  up to the last 32 trailing bytes
     * @param reason     explanation, e.g. {@code "after HEAP_DUMP_END"} or
     *                   {@code "after parse error: Unexpected tag 0x42 at offset 12345"}
     */
    public record TrailingBytes(
            long offset,
            long byteCount,
            byte[] firstBytes,
            byte[] lastBytes,
            String reason
    ) {}

    /**
     * Statistics for one Java class as seen in the heap dump.
     *
     * @param classId                        HPROF object ID of the class
     * @param className                      resolved class name, or {@code "class#<id>"} if unknown
     * @param instanceCount                  number of HPROF_GC_INSTANCE_DUMP records for this class
     * @param totalInstanceBytes             sum of dataLength fields across all instances
     * @param matHeapSizeWithCompressedOops  estimated MAT retained heap assuming refSize = 4
     * @param matHeapSizeWithoutCompressedOops estimated MAT retained heap assuming refSize = idSize
     */
    public record TopClass(
            long classId,
            String className,
            long instanceCount,
            long totalInstanceBytes,
            long matHeapSizeWithCompressedOops,
            long matHeapSizeWithoutCompressedOops
    ) {}

    /**
     * Statistics for one array (primitive or object) in the heap dump.
     *
     * @param arrayId                        HPROF object ID of the array
     * @param arrayType                      type descriptor, e.g. {@code "PRIM:byte"}, {@code "PRIM:int"},
     *                                       {@code "OBJ"}
     * @param numElements                    number of elements in the array
     * @param diskBytes                      bytes occupied by this array's subrecord on disk
     * @param matHeapSizeWithCompressedOops  estimated MAT heap size assuming refSize = 4
     * @param matHeapSizeWithoutCompressedOops estimated MAT heap size assuming refSize = idSize
     */
    public record TopArray(
            long arrayId,
            String arrayType,
            long numElements,
            long diskBytes,
            long matHeapSizeWithCompressedOops,
            long matHeapSizeWithoutCompressedOops
    ) {}

    /**
     * An object ID that appears in more than one heap-dump subrecord.
     *
     * @param objectId        the duplicated HPROF object identifier
     * @param occurrenceCount number of times this ID was encountered
     * @param recordKind      kind of record where the duplication was found,
     *                        e.g. {@code "INSTANCE_DUMP"}, {@code "OBJ_ARRAY_DUMP"},
     *                        {@code "PRIM_ARRAY_DUMP"}
     */
    public record DuplicateId(
            long objectId,
            int occurrenceCount,
            String recordKind
    ) {}
}
