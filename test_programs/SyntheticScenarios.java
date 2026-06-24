import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

/**
 * Produces synthetic HPROF files that demonstrate specific anomalies without
 * requiring a live JVM dump. All files use idSize=4.
 *
 * Usage:
 *   javac SyntheticScenarios.java
 *   java SyntheticScenarios <output-dir>
 *
 * Writes:
 *   scenario-5-truncated.hprof         — file cut off mid-segment
 *   scenario-6-segment-mismatch.hprof  — declared segment length > consumed bytes
 *   scenario-7-duplicate-ids.hprof     — same objectId in two INSTANCE_DUMP records
 *   scenario-8-gzip.hprof.gz           — gzip-compressed clean dump
 *   scenario-9-class-overhead.hprof    — 500 class dumps, few instances
 *   scenario-10-utf8-dominant.hprof    — UTF-8 bytes dominate the file
 */
public class SyntheticScenarios {

    // HPROF top-level tags
    static final int HPROF_UTF8             = 0x01;
    static final int HPROF_LOAD_CLASS       = 0x02;
    static final int HPROF_HEAP_DUMP_SEGMENT= 0x1C;
    static final int HPROF_HEAP_DUMP_END    = 0x2C;

    // Heap-dump subrecord tags
    static final int HPROF_GC_ROOT_STICKY_CLASS = 0x05;
    static final int HPROF_GC_CLASS_DUMP        = 0x20;
    static final int HPROF_GC_INSTANCE_DUMP     = 0x21;
    static final int HPROF_GC_OBJ_ARRAY_DUMP    = 0x22;
    static final int HPROF_GC_PRIM_ARRAY_DUMP   = 0x23;

    // Primitive type codes
    static final int TYPE_OBJECT = 0x02;
    static final int TYPE_BYTE   = 0x08;
    static final int TYPE_INT    = 0x0A;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java SyntheticScenarios <output-dir>");
            System.exit(1);
        }
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);

        scenario5_truncated(outDir);
        scenario6_segmentMismatch(outDir);
        scenario7_duplicateIds(outDir);
        scenario8_gzip(outDir);
        scenario9_classOverhead(outDir);
        scenario10_utf8Dominant(outDir);
    }

    // ------------------------------------------------------------------
    // Scenario 5: Truncated dump
    // ------------------------------------------------------------------
    static void scenario5_truncated(Path outDir) throws Exception {
        System.out.println("[Scenario 5] Truncated dump ...");

        // Build a complete valid HPROF, then keep only the first 60%
        byte[] full = buildMinimalHprof(100); // 100 instances
        int cutAt = (int)(full.length * 0.60);
        byte[] truncated = new byte[cutAt];
        System.arraycopy(full, 0, truncated, 0, cutAt);

        Path out = outDir.resolve("scenario-5-truncated.hprof");
        Files.write(out, truncated);
        System.out.printf("  full: %.2f KB  truncated: %.2f KB (cut at 60%%)%n%n",
            full.length / 1e3, truncated.length / 1e3);
    }

    // ------------------------------------------------------------------
    // Scenario 6: Segment length mismatch
    // ------------------------------------------------------------------
    static void scenario6_segmentMismatch(Path outDir) throws Exception {
        System.out.println("[Scenario 6] Segment length mismatch ...");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);

        writeHeader(d);
        writeUtf8(d, 1, "MyClass");
        writeLoadClass(d, 1, 0x100, 1);

        // Build segment content: 1 CLASS_DUMP + 1 INSTANCE_DUMP
        byte[] realSegment = buildSegment(5); // 5 instances
        int declaredLength = realSegment.length + 500; // 500 extra bytes declared but not written

        // Write top-level record with inflated length
        d.writeByte(HPROF_HEAP_DUMP_SEGMENT);
        writeU4(d, 0); // time
        writeU4(d, declaredLength);
        d.write(realSegment);
        // The remaining (declaredLength - realSegment.length) bytes are missing — that's the mismatch.
        // We pad them with zeros so the file is well-formed at the byte level but the subrecord
        // parser will encounter zeros (unknown tag 0x00) mid-segment.
        for (int i = 0; i < 500; i++) d.writeByte(0);

        writeRecord(d, HPROF_HEAP_DUMP_END, new byte[0]);
        d.flush();

        Path outPath = outDir.resolve("scenario-6-segment-mismatch.hprof");
        Files.write(outPath, out.toByteArray());
        System.out.printf("  written: %.2f KB  (segment declares %d extra bytes padded with zeros)%n%n",
            out.size() / 1e3, 500);
    }

    // ------------------------------------------------------------------
    // Scenario 7: Duplicate object IDs
    // ------------------------------------------------------------------
    static void scenario7_duplicateIds(Path outDir) throws Exception {
        System.out.println("[Scenario 7] Duplicate object IDs ...");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);

        writeHeader(d);
        writeUtf8(d, 1, "MyClass");
        writeLoadClass(d, 1, 0x100, 1);

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(seg);

        // CLASS_DUMP for classId=0x100
        writeClassDump(s, 0x100, 0);

        // Three INSTANCE_DUMP records — two share objectId=0x200
        writeInstanceDump(s, 0x200, 0x100, new byte[0]); // first occurrence
        writeInstanceDump(s, 0x201, 0x100, new byte[0]); // different id
        writeInstanceDump(s, 0x200, 0x100, new byte[0]); // duplicate of first

        s.flush();
        writeRecord(d, HPROF_HEAP_DUMP_SEGMENT, seg.toByteArray());
        writeRecord(d, HPROF_HEAP_DUMP_END, new byte[0]);
        d.flush();

        Path outPath = outDir.resolve("scenario-7-duplicate-ids.hprof");
        Files.write(outPath, out.toByteArray());
        System.out.printf("  written: %d bytes  (objectId 0x200 appears twice)%n%n", out.size());
    }

    // ------------------------------------------------------------------
    // Scenario 8: GZip — compressed clean dump
    // ------------------------------------------------------------------
    static void scenario8_gzip(Path outDir) throws Exception {
        System.out.println("[Scenario 8] GZip compressed dump ...");

        byte[] uncompressed = buildMinimalHprof(50);

        Path outRaw  = outDir.resolve("scenario-8-gzip.hprof");
        Path outGzip = outDir.resolve("scenario-8-gzip.hprof.gz");
        Files.write(outRaw, uncompressed);
        try (var os = new GZIPOutputStream(Files.newOutputStream(outGzip))) {
            os.write(uncompressed);
        }
        long rawSize  = Files.size(outRaw);
        long gzipSize = Files.size(outGzip);
        System.out.printf("  uncompressed: %.2f KB  gzip: %.2f KB  compression: %.1fx%n%n",
            rawSize / 1e3, gzipSize / 1e3, (double) rawSize / gzipSize);
        Files.deleteIfExists(outRaw); // keep only gzip
    }

    // ------------------------------------------------------------------
    // Scenario 9: Class/root/framing overhead dominates
    // ------------------------------------------------------------------
    static void scenario9_classOverhead(Path outDir) throws Exception {
        System.out.println("[Scenario 9] Class and root overhead ...");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        writeHeader(d);

        // Write 500 class name strings + load_class records
        int N = 500;
        for (int i = 0; i < N; i++) {
            writeUtf8(d, 10000L + i, "com/example/package" + (i / 10) + "/SomeClass" + i);
            writeLoadClass(d, i + 1, 0x1000L + i, 10000L + i);
        }

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(seg);
        // 500 CLASS_DUMPs with instanceSize=0
        for (int i = 0; i < N; i++) {
            writeClassDump(s, 0x1000L + i, 0);
        }
        // Just 2 actual instances
        writeInstanceDump(s, 0x9001, 0x1000, new byte[0]);
        writeInstanceDump(s, 0x9002, 0x1001, new byte[0]);

        s.flush();
        writeRecord(d, HPROF_HEAP_DUMP_SEGMENT, seg.toByteArray());
        writeRecord(d, HPROF_HEAP_DUMP_END, new byte[0]);
        d.flush();

        long total    = out.size();
        long segBytes = seg.size();
        // rough class overhead: each CLASS_DUMP is 1 + 4*2 + 4 + 4 + 2*3 = 9*4+?
        System.out.printf("  total: %.2f KB  segment: %.2f KB  (500 class dumps, 2 instances)%n%n",
            total / 1e3, segBytes / 1e3);

        Path outPath = outDir.resolve("scenario-9-class-overhead.hprof");
        Files.write(outPath, out.toByteArray());
    }

    // ------------------------------------------------------------------
    // Scenario 10: UTF-8 section dominates the file
    // ------------------------------------------------------------------
    static void scenario10_utf8Dominant(Path outDir) throws Exception {
        System.out.println("[Scenario 10] UTF-8 dominant ...");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        writeHeader(d);

        // Write a large UTF-8 record and many smaller ones
        // so UTF-8 bytes > 5% of total file size
        String bigName = "A".repeat(50_000); // 50 KB string name
        writeUtf8(d, 1L, bigName);

        for (int i = 0; i < 200; i++) {
            writeUtf8(d, 1000L + i,
                "me/bechberger/scenario10/VeryLongClassNameToInflateUtf8Section$Inner$" + i);
        }
        // One class and one instance so the heap section exists
        writeUtf8(d, 2L, "Base");
        writeLoadClass(d, 1, 0x100, 2L);

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(seg);
        writeClassDump(s, 0x100, 4);
        writeInstanceDump(s, 0x200, 0x100, new int[]{42});
        s.flush();
        writeRecord(d, HPROF_HEAP_DUMP_SEGMENT, seg.toByteArray());
        writeRecord(d, HPROF_HEAP_DUMP_END, new byte[0]);
        d.flush();

        long total    = out.size();
        long utf8Size = 9L + 4 + bigName.length(); // rough (tag+time+len+nameId+bytes)
        System.out.printf("  total: %.2f KB  utf8 ≈ %.2f KB (%.1f%%)%n%n",
            total / 1e3, utf8Size / 1e3, 100.0 * utf8Size / total);

        Path outPath = outDir.resolve("scenario-10-utf8-dominant.hprof");
        Files.write(outPath, out.toByteArray());
    }

    // ===================================================================
    // Builders
    // ===================================================================

    static byte[] buildMinimalHprof(int instanceCount) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(out);
        writeHeader(d);
        writeUtf8(d, 1L, "MyClass");
        writeLoadClass(d, 1, 0x100, 1L);

        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(seg);
        writeClassDump(s, 0x100, 4); // instanceSize=4 (one int field)
        for (int i = 0; i < instanceCount; i++) {
            writeInstanceDump(s, 0x200L + i, 0x100, new int[]{i});
        }
        // Add some byte[] prim arrays to make dumps heavier
        for (int i = 0; i < instanceCount; i++) {
            writePrimArrayDump(s, 0x5000L + i, TYPE_BYTE, new byte[1000]);
        }
        s.flush();
        writeRecord(d, HPROF_HEAP_DUMP_SEGMENT, seg.toByteArray());
        writeRecord(d, HPROF_HEAP_DUMP_END, new byte[0]);
        d.flush();
        return out.toByteArray();
    }

    static byte[] buildSegment(int instanceCount) throws Exception {
        ByteArrayOutputStream seg = new ByteArrayOutputStream();
        DataOutputStream s = new DataOutputStream(seg);
        writeClassDump(s, 0x100, 4);
        for (int i = 0; i < instanceCount; i++) {
            writeInstanceDump(s, 0x200L + i, 0x100, new int[]{i});
        }
        s.flush();
        return seg.toByteArray();
    }

    // ===================================================================
    // Low-level HPROF writers (idSize=4 throughout)
    // ===================================================================

    static void writeHeader(DataOutputStream d) throws IOException {
        d.write("JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.UTF_8));
        writeU4(d, 4);   // idSize
        writeU8(d, System.currentTimeMillis());
    }

    static void writeRecord(DataOutputStream d, int tag, byte[] payload) throws IOException {
        d.writeByte(tag);
        writeU4(d, 0); // time
        writeU4(d, payload.length);
        d.write(payload);
    }

    static void writeUtf8(DataOutputStream d, long nameId, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        DataOutputStream pd = new DataOutputStream(p);
        writeId(pd, nameId);
        pd.write(bytes);
        pd.flush();
        writeRecord(d, HPROF_UTF8, p.toByteArray());
    }

    static void writeLoadClass(DataOutputStream d, int serial, long classId, long nameId) throws IOException {
        ByteArrayOutputStream p = new ByteArrayOutputStream();
        DataOutputStream pd = new DataOutputStream(p);
        writeU4(pd, serial);
        writeId(pd, classId);
        writeU4(pd, 0); // stackTraceSerial
        writeId(pd, nameId);
        pd.flush();
        writeRecord(d, HPROF_LOAD_CLASS, p.toByteArray());
    }

    static void writeClassDump(DataOutputStream s, long classId, int instanceSize) throws IOException {
        s.writeByte(HPROF_GC_CLASS_DUMP);
        writeId(s, classId);
        writeU4(s, 0);       // stackTraceSerial
        writeId(s, 0);       // superClassId
        writeId(s, 0);       // classLoader
        writeId(s, 0);       // signers
        writeId(s, 0);       // protectionDomain
        writeId(s, 0);       // reserved1
        writeId(s, 0);       // reserved2
        writeU4(s, instanceSize);
        writeU2(s, 0);       // constantPoolSize
        writeU2(s, 0);       // staticFieldCount
        writeU2(s, 0);       // instanceFieldCount
    }

    static void writeInstanceDump(DataOutputStream s, long objectId, long classId, int[] intFields) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        DataOutputStream dd = new DataOutputStream(data);
        for (int v : intFields) dd.writeInt(v);
        dd.flush();

        s.writeByte(HPROF_GC_INSTANCE_DUMP);
        writeId(s, objectId);
        writeU4(s, 0); // stackTraceSerial
        writeId(s, classId);
        writeU4(s, data.size());
        s.write(data.toByteArray());
    }

    static void writeInstanceDump(DataOutputStream s, long objectId, long classId, byte[] payload) throws IOException {
        s.writeByte(HPROF_GC_INSTANCE_DUMP);
        writeId(s, objectId);
        writeU4(s, 0);
        writeId(s, classId);
        writeU4(s, payload.length);
        s.write(payload);
    }

    static void writePrimArrayDump(DataOutputStream s, long arrayId, int type, byte[] data) throws IOException {
        s.writeByte(HPROF_GC_PRIM_ARRAY_DUMP);
        writeId(s, arrayId);
        writeU4(s, 0); // stackTraceSerial
        writeU4(s, data.length);
        s.writeByte(type);
        s.write(data);
    }

    static void writeId(DataOutputStream d, long id) throws IOException {
        d.writeInt((int) id); // idSize=4
    }

    static void writeU2(DataOutputStream d, int v) throws IOException {
        d.writeShort(v & 0xFFFF);
    }

    static void writeU4(DataOutputStream d, long v) throws IOException {
        d.writeInt((int) v);
    }

    static void writeU8(DataOutputStream d, long v) throws IOException {
        d.writeLong(v);
    }
}
