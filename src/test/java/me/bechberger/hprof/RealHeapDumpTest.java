/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import com.sun.management.HotSpotDiagnosticMXBean;
import me.bechberger.hprof.transformer.HprofTransformer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import me.bechberger.hprof.transformer.ZeroPrimitiveTransformer;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static me.bechberger.hprof.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealHeapDumpTest {
    static volatile Payload PAYLOAD;

    static final class Payload {
        int value;
        long longValue;
        byte[] bytes;
        char[] chars;

        Payload(int value, long longValue, byte[] bytes, char[] chars) {
            this.value = value;
            this.longValue = longValue;
            this.bytes = bytes;
            this.chars = chars;
        }
    }

    /** Tiny class with a single int field for the targeted-transformer test. */
    static final class TinyPayload {
        int magic;
        TinyPayload(int magic) { this.magic = magic; }
    }

    static volatile TinyPayload TINY_PAYLOAD;

    /**
     * Creates a heap dump containing a {@link TinyPayload} with {@code magic = 34534534},
     * filters it with a custom transformer that only zeroes that specific int value,
     * then verifies with hprof-slurp --json that the filtered dump is structurally equivalent
     * and that the field was actually changed.
     */
    @Test
    void tinyPayloadCustomTransformerVerifiedBySlurp() throws Exception {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase();
        Assumptions.assumeTrue(vmName.contains("hotspot") || vmName.contains("openjdk"));
        HprofSlurpRunner.ensureInstalledOrSkip();

        final int MAGIC = 34534534;
        TINY_PAYLOAD = new TinyPayload(MAGIC);

        Path dir = Files.createTempDirectory("hprof-tiny");
        Path input = dir.resolve("input.hprof");
        Path filtered = dir.resolve("filtered.hprof");
        Path preJson;
        Path postJson;

        createHeapDump(input);
        assertTrue(Files.exists(input));

        // Custom transformer: only change int values equal to MAGIC
        HprofTransformer transformer = new HprofTransformer() {
            @Override
            public int transformInt(int value) {
                return value == MAGIC ? 0 : value;
            }
        };

        try (OutputStream out = HprofIO.openOutputStream(filtered)) {
            new HprofFilter(transformer, null).filter(input, out);
        }

        // 1. Verify file sizes match (only changing int values, no structural change)
        assertEquals(Files.size(input), Files.size(filtered),
                "Filtered file size must match original (only int values changed)");

        // 2. Verify with hprof-slurp --json that structure is preserved
        preJson = HprofSlurpRunner.runJson(input);
        postJson = HprofSlurpRunner.runJson(filtered);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode pre = normalizeSlurpJson(mapper.readTree(Files.readString(preJson)));
        JsonNode post = normalizeSlurpJson(mapper.readTree(Files.readString(postJson)));
        assertEquals(pre, post, "hprof-slurp JSON must match after targeted int redaction");

        // 3. Parse the filtered heap dump and verify the int was actually zeroed
        int parsedValue = parseTinyPayloadInt(filtered);
        assertEquals(0, parsedValue, "TinyPayload.magic should be 0 after redaction");

        System.out.printf("✓ TinyPayload test passed: magic %d → %d, slurp JSON matches%n", MAGIC, parsedValue);

        // cleanup
        Files.deleteIfExists(input);
        Files.deleteIfExists(filtered);
        Files.deleteIfExists(dir);
        Files.deleteIfExists(preJson);
        Files.deleteIfExists(postJson);
    }

    /**
     * Parse the filtered heap dump to extract the TinyPayload.magic int value.
     * Uses the same multi-pass approach as parseHeap.
     */
    private int parseTinyPayloadInt(Path output) throws Exception {
        int idSize;
        Map<Long, String> utf8 = new HashMap<>();
        Map<Long, Long> classIdToNameId = new HashMap<>();

        try (InputStream in = HprofIO.openInputStream(output)) {
            HprofDataInput data = new HprofDataInput(in);
            idSize = readHeader(data);
            data.setIdSize(idSize);
            while (true) {
                int tag = data.readTag();
                if (tag < 0) break;
                data.readU4();
                long length = data.readU4();
                if (tag == HPROF_UTF8) {
                    long id = data.readId();
                    int size = (int) (length - idSize);
                    byte[] bytes = new byte[size];
                    data.readFully(bytes);
                    try {
                        utf8.put(id, ModifiedUtf8.decode(bytes));
                    } catch (IllegalArgumentException ex) {
                        utf8.put(id, new String(bytes, StandardCharsets.UTF_8));
                    }
                    continue;
                }
                if (tag == HPROF_LOAD_CLASS) {
                    data.readU4();
                    long classId = data.readId();
                    data.readU4();
                    long nameId = data.readId();
                    classIdToNameId.put(classId, nameId);
                    continue;
                }
                data.skipFully(length);
            }
        }

        String tinyInternalName = TinyPayload.class.getName().replace('.', '/');
        long tinyClassId = 0;
        for (var entry : classIdToNameId.entrySet()) {
            Long nameId = entry.getValue();
            String name = nameId == null ? null : utf8.get(nameId);
            if (tinyInternalName.equals(name)) {
                tinyClassId = entry.getKey();
                break;
            }
        }
        assertTrue(tinyClassId != 0, "TinyPayload class not found in heap dump");

        // Pass 2: find instance and read the int field
        Map<Long, HprofClassInfo> classInfos = new HashMap<>();
        Map<Long, List<HprofType>> flattenedTypesCache = new HashMap<>();
        final long searchClassId = tinyClassId;
        int[] result = {Integer.MIN_VALUE};

        try (InputStream in = HprofIO.openInputStream(output)) {
            HprofDataInput data = new HprofDataInput(in);
            readHeader(data);
            data.setIdSize(idSize);
            while (true) {
                int tag = data.readTag();
                if (tag < 0) break;
                data.readU4();
                long length = data.readU4();
                if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                    byte[] segment = new byte[(int) length];
                    data.readFully(segment);
                    HprofDataInput sin = new HprofDataInput(new java.io.ByteArrayInputStream(segment));
                    sin.setIdSize(idSize);
                    SegmentReader reader = new SegmentReader(sin, segment.length, idSize);
                    while (reader.remaining > 0) {
                        int subTag = reader.readU1();
                        switch (subTag) {
                            case HPROF_GC_CLASS_DUMP -> {
                                long classId = reader.readId();
                                reader.readU4();
                                long superClassId = reader.readId();
                                for (int i = 0; i < 5; i++) reader.skipId();
                                reader.readU4();
                                int cpSize = reader.readU2();
                                for (int i = 0; i < cpSize; i++) {
                                    reader.readU2();
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
                                List<HprofClassInfo.FieldDef> fields = new ArrayList<>(instanceCount);
                                for (int i = 0; i < instanceCount; i++) {
                                    long nameId = reader.readId();
                                    HprofType type = HprofType.fromCode(reader.readU1());
                                    fields.add(new HprofClassInfo.FieldDef(nameId, type));
                                }
                                classInfos.put(classId, new HprofClassInfo(classId, superClassId, fields));
                                flattenedTypesCache.remove(classId);
                            }
                            case HPROF_GC_INSTANCE_DUMP -> {
                                reader.readId();
                                reader.readU4();
                                long classId = reader.readId();
                                long dataLength = reader.readU4();
                                if (classId == searchClassId) {
                                    List<HprofType> flattened = flattenedTypes(classId, classInfos, flattenedTypesCache);
                                    long remaining = dataLength;
                                    for (HprofType type : flattened) {
                                        if (type == HprofType.INT) {
                                            result[0] = (int) reader.readU4();
                                            remaining -= 4;
                                        } else {
                                            remaining -= reader.skipValue(type.code());
                                        }
                                    }
                                    if (remaining > 0) reader.skipBytes(remaining);
                                } else {
                                    reader.skipBytes(dataLength);
                                }
                            }
                            default -> reader.skipSubRecord(subTag);
                        }
                    }
                    continue;
                }
                data.skipFully(length);
            }
        }
        assertTrue(result[0] != Integer.MIN_VALUE, "TinyPayload instance not found in heap dump");
        return result[0];
    }

    private static JsonNode normalizeSlurpJson(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            Set<String> volatileKeys = Set.of(
                    "input_file", "input_path", "inputFile",
                    "elapsed_ms", "duration_ms", "duration", "generated_at", "timestamp"
            );
            for (String k : volatileKeys) {
                obj.remove(k);
            }
        }
        return node;
    }

    @Test
    void filtersRealHeapDumpAndParsesKnownClass() throws Exception {
        String vmName = System.getProperty("java.vm.name", "").toLowerCase();
        Assumptions.assumeTrue(vmName.contains("hotspot") || vmName.contains("openjdk"));

        PAYLOAD = new Payload(123, 999_999L, new byte[]{1, 2, 3}, new char[]{'A', 'B'});

        Path dir = Files.createTempDirectory("hprof-real");
        Path input = dir.resolve("input.hprof");
        Path output = dir.resolve("output.hprof");

        createHeapDump(input);
        assertTrue(Files.exists(input));

            try (OutputStream out = HprofIO.openOutputStream(output)) {
                new HprofFilter(new ZeroPrimitiveTransformer(), null).filter(input, out);
        }

        ParsedHeap parsed = parseHeap(output);
        assertNotNull(parsed);
        assertEquals(0, parsed.intValue);
        assertEquals(0L, parsed.longValue);
        assertArrayEquals(new byte[]{0, 0, 0}, parsed.byteArray);
        assertArrayEquals(new char[]{0, 0}, parsed.charArray);
        // HPROF uses JVM internal names with '/' separators
        assertEquals(Payload.class.getName().replace('.', '/'), parsed.className);
    }

    private static void createHeapDump(Path path) throws Exception {
        HotSpotDiagnosticMXBean mxBean = ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic",
                HotSpotDiagnosticMXBean.class);
        mxBean.dumpHeap(path.toString(), true);
    }

    private static ParsedHeap parseHeap(Path output) throws Exception {
        // Pass 1: discover idSize, UTF8 table, and the classId of Payload.
        int idSize;
        Map<Long, String> utf8 = new HashMap<>();
        Map<Long, Long> classIdToNameId = new HashMap<>();

        try (InputStream in = HprofIO.openInputStream(output)) {
            HprofDataInput data = new HprofDataInput(in);
            idSize = readHeader(data);
            data.setIdSize(idSize);

            while (true) {
                int tag = data.readTag();
                if (tag < 0) {
                    break;
                }
                data.readU4();
                long length = data.readU4();

                if (tag == HPROF_UTF8) {
                    long id = data.readId();
                    int size = (int) (length - idSize);
                    byte[] bytes = new byte[size];
                    data.readFully(bytes);
                    // HotSpot uses modified UTF-8 for SymbolTable-derived strings.
                    try {
                        utf8.put(id, ModifiedUtf8.decode(bytes));
                    } catch (IllegalArgumentException ex) {
                        utf8.put(id, new String(bytes, StandardCharsets.UTF_8));
                    }
                    continue;
                }

                if (tag == HPROF_LOAD_CLASS) {
                    data.readU4();
                    long classId = data.readId();
                    data.readU4();
                    long nameId = data.readId();
                    classIdToNameId.put(classId, nameId);
                    continue;
                }

                data.skipFully(length);
            }
        }

        // HotSpot uses JVM internal format with '/' separators in HPROF class names.
        String payloadInternalName = Payload.class.getName().replace('.', '/');
        long payloadClassId = 0;
        for (var entry : classIdToNameId.entrySet()) {
            Long nameId = entry.getValue();
            String name = nameId == null ? null : utf8.get(nameId);
            if (payloadInternalName.equals(name)) {
                payloadClassId = entry.getKey();
                break;
            }
        }

        // Pass 2: parse heap segments knowing payloadClassId.
        ParsedHeap parsed = new ParsedHeap();
        Map<Long, HprofClassInfo> classInfos = new HashMap<>();
        Map<Long, List<HprofType>> flattenedTypesCache = new HashMap<>();

        try (InputStream in = HprofIO.openInputStream(output)) {
            HprofDataInput data = new HprofDataInput(in);
            readHeader(data);
            data.setIdSize(idSize);

            while (true) {
                int tag = data.readTag();
                if (tag < 0) {
                    break;
                }
                data.readU4();
                long length = data.readU4();

                if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                    byte[] segment = new byte[(int) length];
                    data.readFully(segment);
                    parseHeapSegment(segment, payloadClassId, utf8, classIdToNameId,
                            classInfos, flattenedTypesCache, parsed, idSize);
                    continue;
                }

                data.skipFully(length);
            }
        }

        // Pass 3: if the prim arrays appeared before the Payload instance in the
        // heap segment(s), the arrays were not captured yet.  Re-scan the
        // segments looking only for those arrays.
        if (parsed.candidateArrayIds != null
                && (parsed.byteArray.length == 0 || parsed.charArray.length == 0)) {
            try (InputStream in = HprofIO.openInputStream(output)) {
                HprofDataInput data = new HprofDataInput(in);
                readHeader(data);
                data.setIdSize(idSize);

                while (true) {
                    int tag = data.readTag();
                    if (tag < 0) {
                        break;
                    }
                    data.readU4();
                    long length = data.readU4();

                    if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                        byte[] segment = new byte[(int) length];
                        data.readFully(segment);
                        scanForPrimArrays(segment, parsed, idSize);
                        continue;
                    }

                    data.skipFully(length);
                }
            }
        }

        if (parsed.className == null && payloadClassId != 0) {
            Long nameId = classIdToNameId.get(payloadClassId);
            parsed.className = nameId == null ? null : utf8.get(nameId);
        }
        return parsed;
    }

    private static void parseHeapSegment(byte[] segment, long payloadClassId,
                                         Map<Long, String> utf8,
                                         Map<Long, Long> classIdToNameId,
                                         Map<Long, HprofClassInfo> classInfos,
                                         Map<Long, List<HprofType>> flattenedTypesCache,
                                         ParsedHeap parsed, int idSize) throws Exception {
        HprofDataInput in = new HprofDataInput(new java.io.ByteArrayInputStream(segment));
        in.setIdSize(idSize);
        SegmentReader reader = new SegmentReader(in, segment.length, idSize);

        while (reader.remaining > 0) {
            int subTag = reader.readU1();
            switch (subTag) {
                case HPROF_GC_CLASS_DUMP -> {
                    long classId = reader.readId();
                    reader.readU4();
                    long superClassId = reader.readId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.skipId();
                    reader.readU4();

                    int constantPoolSize = reader.readU2();
                    for (int i = 0; i < constantPoolSize; i++) {
                        reader.readU2();
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
                    List<HprofClassInfo.FieldDef> fields = new ArrayList<>(instanceCount);
                    for (int i = 0; i < instanceCount; i++) {
                        long nameId = reader.readId();
                        HprofType type = HprofType.fromCode(reader.readU1());
                        fields.add(new HprofClassInfo.FieldDef(nameId, type));
                    }

                    classInfos.put(classId, new HprofClassInfo(classId, superClassId, fields));
                    flattenedTypesCache.remove(classId);

                    if (payloadClassId != 0 && classId == payloadClassId) {
                        Long nameId = classIdToNameId.get(payloadClassId);
                        parsed.className = nameId == null ? null : utf8.get(nameId);
                    }
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    reader.readId();
                    reader.readU4();
                    long classId = reader.readId();
                    long dataLength = reader.readU4();
                    if (payloadClassId != 0 && classId == payloadClassId) {
                        List<HprofType> flattened = flattenedTypes(classId, classInfos, flattenedTypesCache);
                        long remaining = dataLength;
                        // JDK versions may reorder fields, so collect all OBJECT
                        // references and resolve byte/char arrays by element type later.
                        List<Long> objectIds = new ArrayList<>();
                        for (HprofType type : flattened) {
                            switch (type) {
                                case INT -> {
                                    parsed.intValue = (int) reader.readU4();
                                    remaining -= 4;
                                }
                                case LONG -> {
                                    parsed.longValue = reader.readU8();
                                    remaining -= 8;
                                }
                                case OBJECT -> {
                                    long id = reader.readId();
                                    objectIds.add(id);
                                    remaining -= idSize;
                                }
                                default -> remaining -= reader.skipValue(type.code());
                            }
                        }
                        // Store all candidate object IDs; actual assignment is
                        // deferred to the PRIM_ARRAY_DUMP handler below.
                        parsed.candidateArrayIds = objectIds;
                        if (remaining > 0) {
                            reader.skipBytes(remaining);
                        }
                    } else {
                        reader.skipBytes(dataLength);
                    }
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    long arrayId = reader.readId();
                    reader.readU4();
                    long count = reader.readU4();
                    int elementType = reader.readU1();
                    boolean isByteCandidate = parsed.candidateArrayIds != null
                            && parsed.candidateArrayIds.contains(arrayId)
                            && elementType == HPROF_TYPE_BYTE
                            && parsed.byteArray.length == 0;
                    boolean isCharCandidate = parsed.candidateArrayIds != null
                            && parsed.candidateArrayIds.contains(arrayId)
                            && elementType == HPROF_TYPE_CHAR
                            && parsed.charArray.length == 0;
                    if (isByteCandidate) {
                        parsed.byteArrayId = arrayId;
                        byte[] bytes = new byte[(int) count];
                        for (int i = 0; i < count; i++) {
                            bytes[i] = (byte) reader.readU1();
                        }
                        parsed.byteArray = bytes;
                    } else if (isCharCandidate) {
                        parsed.charArrayId = arrayId;
                        char[] chars = new char[(int) count];
                        for (int i = 0; i < count; i++) {
                            chars[i] = (char) reader.readU2();
                        }
                        parsed.charArray = chars;
                    } else {
                        for (int i = 0; i < count; i++) {
                            reader.skipValue(elementType);
                        }
                    }
                }
                default -> reader.skipSubRecord(subTag);
            }
        }
    }

    /** Re-scan a heap segment looking only for specific prim arrays by ID. */
    private static void scanForPrimArrays(byte[] segment, ParsedHeap parsed, int idSize) throws Exception {
        HprofDataInput in = new HprofDataInput(new java.io.ByteArrayInputStream(segment));
        in.setIdSize(idSize);
        SegmentReader reader = new SegmentReader(in, segment.length, idSize);

        while (reader.remaining > 0) {
            int subTag = reader.readU1();
            if (subTag == HPROF_GC_PRIM_ARRAY_DUMP) {
                long arrayId = reader.readId();
                reader.readU4();
                long count = reader.readU4();
                int elementType = reader.readU1();
                boolean isByteCandidate = parsed.candidateArrayIds != null
                        && parsed.candidateArrayIds.contains(arrayId)
                        && elementType == HPROF_TYPE_BYTE
                        && parsed.byteArray.length == 0;
                boolean isCharCandidate = parsed.candidateArrayIds != null
                        && parsed.candidateArrayIds.contains(arrayId)
                        && elementType == HPROF_TYPE_CHAR
                        && parsed.charArray.length == 0;
                if (isByteCandidate) {
                    parsed.byteArrayId = arrayId;
                    byte[] bytes = new byte[(int) count];
                    for (int i = 0; i < count; i++) {
                        bytes[i] = (byte) reader.readU1();
                    }
                    parsed.byteArray = bytes;
                } else if (isCharCandidate) {
                    parsed.charArrayId = arrayId;
                    char[] chars = new char[(int) count];
                    for (int i = 0; i < count; i++) {
                        chars[i] = (char) reader.readU2();
                    }
                    parsed.charArray = chars;
                } else {
                    for (int i = 0; i < count; i++) {
                        reader.skipValue(elementType);
                    }
                }
            } else {
                reader.skipSubRecord(subTag);
            }
        }
    }

    private static List<HprofType> flattenedTypes(long classId, Map<Long, HprofClassInfo> classInfos,
                                                  Map<Long, List<HprofType>> flattenedTypesCache) {
        List<HprofType> cached = flattenedTypesCache.get(classId);
        if (cached != null) {
            return cached;
        }
        HprofClassInfo info = classInfos.get(classId);
        if (info == null) {
            return List.of();
        }
        List<HprofType> result = new ArrayList<>();
        if (info.superClassId() != 0) {
            result.addAll(flattenedTypes(info.superClassId(), classInfos, flattenedTypesCache));
        }
        result.addAll(info.instanceFieldTypes());
        flattenedTypesCache.put(classId, result);
        return result;
    }

    private static int readHeader(HprofDataInput in) throws Exception {
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

    private static final class ParsedHeap {
        String className;
        int intValue;
        long longValue;
        long byteArrayId;
        long charArrayId;
        byte[] byteArray = new byte[0];
        char[] charArray = new char[0];
        /** All OBJECT field IDs from the Payload instance; used to identify arrays by element type. */
        List<Long> candidateArrayIds;
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

        int readU1() throws Exception {
            remaining -= 1;
            return in.readU1();
        }

        int readU2() throws Exception {
            remaining -= 2;
            return in.readU2();
        }

        long readU4() throws Exception {
            remaining -= 4;
            return in.readU4();
        }

        long readU8() throws Exception {
            remaining -= 8;
            return in.readU8();
        }

        long readId() throws Exception {
            remaining -= idSize;
            return in.readId();
        }

        void skipId() throws Exception {
            readId();
        }

        long skipValue(int type) throws Exception {
            return switch (type) {
                case HPROF_TYPE_OBJECT -> {
                    readId();
                    yield idSize;
                }
                case HPROF_TYPE_BOOLEAN, HPROF_TYPE_BYTE -> {
                    readU1();
                    yield 1;
                }
                case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> {
                    readU2();
                    yield 2;
                }
                case HPROF_TYPE_INT, HPROF_TYPE_FLOAT -> {
                    readU4();
                    yield 4;
                }
                case HPROF_TYPE_LONG, HPROF_TYPE_DOUBLE -> {
                    readU8();
                    yield 8;
                }
                default -> 0;
            };
        }

        void skipBytes(long length) throws Exception {
            if (length <= 0) {
                return;
            }
            byte[] buffer = new byte[4096];
            long remainingBytes = length;
            while (remainingBytes > 0) {
                int chunk = (int) Math.min(buffer.length, remainingBytes);
                in.readFully(buffer, 0, chunk);
                remainingBytes -= chunk;
                remaining -= chunk;
            }
        }

        void skipSubRecord(int subTag) throws Exception {
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> skipBytes(idSize);
                case HPROF_GC_ROOT_JNI_GLOBAL -> skipBytes(idSize + idSize);
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME,
                     HPROF_GC_ROOT_THREAD_OBJ -> skipBytes(idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> skipBytes(idSize + 4);
                case HPROF_GC_CLASS_DUMP -> {
                    skipId(); // classId
                    readU4(); // stackTraceSerial
                    for (int i = 0; i < 6; i++) skipId(); // super, loader, signers, pd, reserved x2
                    readU4(); // instance size
                    int cpSize = readU2();
                    for (int i = 0; i < cpSize; i++) {
                        readU2(); // index
                        int type = readU1(); // type
                        skipValue(type);
                    }
                    int staticCount = readU2();
                    for (int i = 0; i < staticCount; i++) {
                        skipId(); // name
                        int type = readU1();
                        skipValue(type);
                    }
                    int instanceCount = readU2();
                    for (int i = 0; i < instanceCount; i++) {
                        skipId(); // name
                        readU1(); // type
                    }
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    skipId(); // objectId
                    readU4(); // stackTraceSerial
                    skipId(); // classId
                    long dataLen = readU4();
                    skipBytes(dataLen);
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    skipId();
                    readU4();
                    long num = readU4();
                    skipId();
                    skipBytes(num * idSize);
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    skipId();
                    readU4();
                    long count = readU4();
                    int elementType = readU1();
                    long elemSize = switch (elementType) {
                        case HPROF_TYPE_BOOLEAN, HPROF_TYPE_BYTE -> 1;
                        case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> 2;
                        case HPROF_TYPE_INT, HPROF_TYPE_FLOAT -> 4;
                        case HPROF_TYPE_LONG, HPROF_TYPE_DOUBLE -> 8;
                        default -> throw new IllegalStateException("Unknown prim array element type: " + elementType);
                    };
                    skipBytes(count * elemSize);
                }
                default -> throw new IllegalStateException("Unsupported sub tag: 0x" + Integer.toHexString(subTag));
            }
        }
    }
}