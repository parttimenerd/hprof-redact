/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import java.io.PrintWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Renders a {@link DiagnosticReport} as human-readable text to a {@link PrintWriter}. */
public final class TextReportWriter {

    private static final DateTimeFormatter TIMESTAMP_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private TextReportWriter() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void write(DiagnosticReport report, PrintWriter out) {
        writeFileSummary(report.fileSummary(), out);
        writeRecordHistogram(report.recordHistogram(), out);
        writeSubrecordHistogram(report.subrecordHistogram(), out);
        writeSizeAttribution(report.sizeAttribution(), out);
        writeUtf8Analysis(report.utf8Analysis(), report.fileSummary().fileSizeBytes(), out);
        writeTopClasses(report.topClasses(), out);
        writeTopArrays(report.topArrays(), out);
        writeSegmentIssues(report.segmentIssues(), out);
        writeDuplicateHeaders(report.duplicateHeaders(), out);
        writeTrailingBytes(report.trailingBytes(), out);
        writeDuplicateIds(report.duplicateIds(), report.duplicateIdWarning(), out);
        out.flush();
    }

    // -------------------------------------------------------------------------
    // Sections
    // -------------------------------------------------------------------------

    private static void writeFileSummary(DiagnosticReport.FileSummary s, PrintWriter out) {
        out.println("=== HPROF Diagnostic Report ===");
        out.printf("File:     %s%n", s.filePath());
        out.printf("Size:     %s (%s bytes)%n",
                formatBytesHuman(s.fileSizeBytes()), formatBytes(s.fileSizeBytes()));
        out.printf("Header:   %s%n", s.headerMagic());
        out.printf("ID size:  %d bytes%n", s.idSize());
        String ts = TIMESTAMP_FMT.format(
                java.time.Instant.ofEpochMilli(s.timestampMs()).atZone(ZoneOffset.UTC)) + " UTC";
        out.printf("Captured: %s%n", ts);
        out.println();
    }

    private static void writeRecordHistogram(List<DiagnosticReport.RecordStat> stats, PrintWriter out) {
        out.println("--- Record Histogram ---");
        out.printf("%-40s %12s   %18s%n", "Tag", "Count", "Total Bytes");
        for (DiagnosticReport.RecordStat e : stats) {
            out.printf("%-40s %12s   %18s%n",
                    e.tagName(),
                    formatBytes(e.count()),
                    formatBytes(e.totalBytes()));
        }
        out.println();
    }

    private static void writeSubrecordHistogram(List<DiagnosticReport.SubrecordStat> stats, PrintWriter out) {
        out.println("--- Heap-Dump Subrecord Histogram ---");
        out.printf("%-40s %12s   %18s%n", "Subrecord", "Count", "Total Bytes");
        for (DiagnosticReport.SubrecordStat e : stats) {
            out.printf("%-40s %12s   %18s%n",
                    e.subTagName(),
                    formatBytes(e.count()),
                    formatBytes(e.totalBytes()));
        }
        out.println();
    }

    private static void writeSizeAttribution(DiagnosticReport.SizeAttribution s, PrintWriter out) {
        out.println("--- Size Attribution ---");
        out.printf("%-40s %20s   %23s   %27s%n",
                "Category", "On Disk (bytes)", "MAT Heap (compressed)", "MAT Heap (uncompressed)");

        // heap_objects rows
        printAttrRow(out, "heap_objects.instances",
                s.heapObjectInstanceBytes(), s.matHeapSizeWithCompressedOops(), s.matHeapSizeWithoutCompressedOops());
        printAttrRow(out, "heap_objects.obj_arrays",
                s.heapObjectObjArrayBytes(), 0, 0);
        printAttrRow(out, "heap_objects.prim_arrays",
                s.heapObjectPrimArrayBytes(), 0, 0);
        printAttrRow(out, "class_dumps",         s.classDumpBytes(), 0, 0);
        printAttrRow(out, "gc_roots",             s.gcRootBytes(), 0, 0);
        printAttrRow(out, "utf8_strings",         s.utf8StringBytes(), 0, 0);
        printAttrRow(out, "load_class",           s.loadClassBytes(), 0, 0);
        printAttrRow(out, "frames_traces_threads", s.framesTracesThreadsBytes(), 0, 0);
        printAttrRow(out, "heap_summary_and_other", s.heapSummaryAndOtherBytes(), 0, 0);
        printAttrRow(out, "segment_framing",      s.segmentFramingBytes(), 0, 0);
        printAttrRow(out, "heap_dump_end",        s.heapDumpEndBytes(), 0, 0);
        printAttrRow(out, "unknown_or_unparseable", s.unknownOrUnparseableBytes(), 0, 0);

        out.println("----------------------------------------------------------------------");

        long totalOnDisk = s.heapObjectInstanceBytes() + s.heapObjectObjArrayBytes()
                + s.heapObjectPrimArrayBytes() + s.classDumpBytes() + s.gcRootBytes()
                + s.utf8StringBytes() + s.loadClassBytes() + s.framesTracesThreadsBytes()
                + s.heapSummaryAndOtherBytes() + s.segmentFramingBytes() + s.heapDumpEndBytes()
                + s.unknownOrUnparseableBytes();

        out.printf("%-45s %s%n", "TOTAL on-disk:", formatBytes(totalOnDisk));
        out.printf("%-45s %s%n", "TOTAL MAT heap (compressed):", formatBytes(s.matHeapSizeWithCompressedOops()));
        out.printf("%-45s %s%n", "TOTAL MAT heap (uncompressed):", formatBytes(s.matHeapSizeWithoutCompressedOops()));
        out.println();
        out.println("  NOTE: MAT's \"heap size\" figure counts only heap_objects.* categories.");
        out.println("  Objects not reachable from GC roots are excluded from MAT's default");
        out.println("  view but included here if \"Keep unreachable objects\" was enabled.");
        out.println();
    }

    private static void printAttrRow(PrintWriter out, String label,
                                      long diskBytes, long matComp, long matUncomp) {
        out.printf("%-40s %20s   %23s   %27s%n",
                label,
                formatBytes(diskBytes),
                formatBytes(matComp),
                formatBytes(matUncomp));
    }

    private static void writeUtf8Analysis(DiagnosticReport.Utf8Analysis u, long fileSizeBytes, PrintWriter out) {
        out.println("--- UTF-8 Analysis ---");
        double pct = fileSizeBytes > 0 ? (100.0 * u.totalBytes() / fileSizeBytes) : 0.0;
        String flag = u.isUnusuallyLarge() ? "  [UNUSUALLY LARGE]" : "  [NORMAL]";
        out.printf("Total UTF-8 bytes:      %s (%.1f%% of file)%s%n",
                formatBytes(u.totalBytes()), pct, flag);
        out.printf("  Referenced (MAT-resident after parse):    %s bytes%n",
                formatBytes(u.referencedBytes()));
        out.printf("  Unreferenced (transient during parse):   %s bytes%n",
                formatBytes(u.unreferencedBytes()));
        out.printf("Record count:        %s%n", formatBytes(u.recordCount()));
        String sample = u.largestRecordSample() != null && !u.largestRecordSample().isEmpty()
                ? "  (sample: \"" + u.largestRecordSample() + "...\")"
                : "";
        out.printf("Largest record:     %s bytes%s%n", formatBytes(u.largestRecordBytes()), sample);
        out.println();
        out.println("  NOTE: UTF-8 strings are NOT counted in MAT's heap size. They are decoded");
        out.printf("  into memory during parsing (~%s peak), but only the referenced subset%n",
                formatBytesHuman(u.totalBytes()));
        out.printf("  (~%s) stays resident in MAT's ClassImpl objects for the session.%n",
                formatBytesHuman(u.referencedBytes()));
        out.println();
    }

    private static void writeTopClasses(List<DiagnosticReport.TopClass> classes, PrintWriter out) {
        int n = classes.size();
        out.printf("--- Top %d Classes by Instance Bytes ---%n", n);
        out.printf(" %3s  %-50s %12s   %16s   %16s%n",
                "#", "Class Name", "Instances", "Instance Bytes", "MAT (compressed)");
        int rank = 1;
        for (DiagnosticReport.TopClass c : classes) {
            out.printf(" %3d  %-50s %12s   %16s   %16s%n",
                    rank++,
                    c.className(),
                    formatBytes(c.instanceCount()),
                    formatBytes(c.totalInstanceBytes()),
                    formatBytes(c.matHeapSizeWithCompressedOops()));
        }
        out.println();
    }

    private static void writeTopArrays(List<DiagnosticReport.TopArray> arrays, PrintWriter out) {
        int n = arrays.size();
        out.printf("--- Top %d Largest Arrays ---%n", n);
        out.printf(" %3s  %-12s  %12s   %12s   %16s%n",
                "#", "Type", "Elements", "Disk Bytes", "MAT (compressed)");
        int rank = 1;
        for (DiagnosticReport.TopArray a : arrays) {
            out.printf(" %3d  %-12s  %12s   %12s   %16s%n",
                    rank++,
                    a.arrayType(),
                    formatBytes(a.numElements()),
                    formatBytes(a.diskBytes()),
                    formatBytes(a.matHeapSizeWithCompressedOops()));
        }
        out.println();
    }

    private static void writeSegmentIssues(List<DiagnosticReport.SegmentIssue> issues, PrintWriter out) {
        out.println("--- Segment Issues ---");
        if (issues == null || issues.isEmpty()) {
            out.println("[none]");
        } else {
            for (DiagnosticReport.SegmentIssue issue : issues) {
                out.printf("  Segment at offset %s: declared=%s consumed=%s  %s%n",
                        formatBytes(issue.segmentDecompressedOffset()),
                        formatBytes(issue.declaredLength()),
                        formatBytes(issue.consumedBytes()),
                        issue.description());
            }
        }
        out.println();
    }

    private static void writeDuplicateHeaders(List<DiagnosticReport.HeaderOccurrence> headers, PrintWriter out) {
        out.println("--- Duplicate Headers ---");
        // The first header (offset 0) is normal; extras are those at offset > 0
        List<DiagnosticReport.HeaderOccurrence> extras = headers.stream()
                .filter(h -> h.decompressedOffset() > 0)
                .toList();
        if (extras.isEmpty()) {
            out.println("[none]");
        } else {
            for (DiagnosticReport.HeaderOccurrence h : extras) {
                out.printf("WARNING: Additional HPROF header found at decompressed offset %s (magic: %s)%n",
                        formatBytes(h.decompressedOffset()), h.magic());
            }
        }
        out.println("  NOTE: MAT silently treats each additional header as a separate dump.");
        out.println("  The bytes between dumps all count toward the file size but may be");
        out.println("  double-counted in MAT's UI or may represent a partial/corrupt second dump.");
        out.println();
    }

    private static void writeTrailingBytes(DiagnosticReport.TrailingBytes tb, PrintWriter out) {
        out.println("--- Trailing Bytes ---");
        if (tb == null) {
            out.println("[none]");
        } else {
            long count = tb.byteCount() >= 0 ? tb.byteCount() : -1;
            String countStr = count >= 0 ? formatBytes(count) : "(unknown count)";
            out.printf("WARNING: %s trailing bytes after last well-formed record at offset %s%n",
                    countStr, formatBytes(tb.offset()));
            if (tb.reason() != null && !tb.reason().isEmpty()) {
                out.printf("  Reason: %s%n", tb.reason());
            }
        }
        out.println("  NOTE: MAT silently ignores trailing bytes. They count toward file size");
        out.println("  but are never parsed or reported.");
        out.println();
    }

    private static void writeDuplicateIds(List<DiagnosticReport.DuplicateId> ids,
                                           String warning, PrintWriter out) {
        out.println("--- Duplicate Object IDs ---");
        if (warning != null) {
            out.println("WARNING: " + warning);
        } else if (ids == null || ids.isEmpty()) {
            if (ids == null) {
                out.println("[not requested -- use --detect-duplicate-ids to enable]");
            } else {
                out.println("[none found]");
            }
        } else {
            out.printf("  %-20s  %10s  %s%n", "Object ID", "Occurrences", "Kind");
            for (DiagnosticReport.DuplicateId d : ids) {
                out.printf("  0x%-18x  %10d  %s%n",
                        d.objectId(), d.occurrenceCount(), d.recordKind());
            }
        }
        out.println();
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    /** Returns a comma-separated decimal string, e.g. {@code "12,345,678"}. */
    static String formatBytes(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * Returns a human-readable size string, e.g. {@code "11.8 MB"} or {@code "36.8 GB"}.
     * Uses 1024-based units.
     */
    static String formatBytesHuman(long bytes) {
        if (bytes < 0) return bytes + " bytes";
        if (bytes < 1024L) return bytes + " bytes";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ROOT, "%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024.0) return String.format(Locale.ROOT, "%.1f MB", mb);
        double gb = mb / 1024.0;
        if (gb < 1024.0) return String.format(Locale.ROOT, "%.1f GB", gb);
        double tb = gb / 1024.0;
        return String.format(Locale.ROOT, "%.1f TB", tb);
    }
}
