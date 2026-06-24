/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import me.bechberger.hprof.HprofDataInput;
import me.bechberger.hprof.HprofIO;
import me.bechberger.hprof.HprofType;
import me.bechberger.hprof.ModifiedUtf8;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static me.bechberger.hprof.HprofConstants.*;

/**
 * Main streaming diagnostic orchestrator. Reads an HPROF file and produces a {@link DiagnosticReport}.
 *
 * <p>Performs two streaming passes over the decompressed stream, then calls {@link HeaderScanner}
 * for a third pass.
 */
public final class HprofDiagnose {

    public static final class Options {
        public int topN = 20;
        public int objectAlign = 8;
        public boolean detectDuplicateIds = false;
    }

    public static DiagnosticReport diagnose(Path inputPath, Options options) throws IOException {
        // ---- Pass 1: metadata scan ----
        Set<Long> referencedNameIds = new HashSet<>();
        Map<Long, String> utf8Strings = new HashMap<>();   // nameId -> string
        Map<Long, String> classNames = new HashMap<>();    // classId -> className string
        Map<Long, Long> classNameIds = new HashMap<>();    // classId -> nameId

        int idSizePass1;
        String headerMagicPass1;
        long timestampMsPass1;

        try (InputStream raw = HprofIO.openInputStream(inputPath)) {
            HprofDataInput in = new HprofDataInput(raw);
            HprofHeader header = readHeader(in);
            idSizePass1 = header.idSize();
            headerMagicPass1 = header.magic();
            timestampMsPass1 = header.timestamp();
            in.setIdSize(header.idSize());
            scanForMetadata(in, header.idSize(), utf8Strings, referencedNameIds, classNames, classNameIds);
        }

        // ---- Pass 2: full diagnostic pass ----
        RecordHistogram topLevelHistogram = new RecordHistogram();
        RecordHistogram subrecordHistogram = new RecordHistogram();

        // Class stats: classId -> [instanceCount, totalInstanceBytes, matCompressed, matUncompressed]
        Map<Long, long[]> classStats = new HashMap<>();
        TopNTracker<DiagnosticReport.TopArray> topArrays = new TopNTracker<>(options.topN);
        List<DiagnosticReport.SegmentIssue> segmentIssues = new ArrayList<>();

        // Duplicate ID tracking
        LongHashSet seenIds = options.detectDuplicateIds ? new LongHashSet(1 << 16) : null;
        Map<Long, Integer> duplicateIdCounts = new HashMap<>();
        Map<Long, String> duplicateIdKind = new HashMap<>();

        Pass2State state = new Pass2State(
                idSizePass1, options.objectAlign,
                topLevelHistogram, subrecordHistogram,
                referencedNameIds,
                classStats, topArrays,
                seenIds, duplicateIdCounts, duplicateIdKind,
                segmentIssues
        );

        long trailingOffset = -1;
        String trailingReason = null;
        long lastWellFormedOffset = 0;

        try (InputStream raw = HprofIO.openInputStream(inputPath)) {
            CountingInputStream counting = new CountingInputStream(raw);
            HprofDataInput in = new HprofDataInput(counting);
            HprofHeader header = readHeader(in);
            in.setIdSize(header.idSize());

            try {
                while (true) {
                    int tag = in.readTag();
                    if (tag < 0) {
                        lastWellFormedOffset = counting.position();
                        break;
                    }
                    /* long time = */ in.readU4();
                    long length = in.readU4();

                    // Every top-level record: 9 bytes framing
                    state.segmentFramingBytes += 9;
                    topLevelHistogram.record(tag, 9 + length);

                    switch (tag) {
                        case HPROF_UTF8 -> {
                            long nameId = in.readId();
                            long dataLen = length - header.idSize();
                            if (dataLen < 0) dataLen = 0;
                            if (dataLen > Integer.MAX_VALUE)
                                throw new IOException("UTF8 record too large: " + dataLen);
                            byte[] data = new byte[(int) dataLen];
                            in.readFully(data);

                            long recordTotal = 9 + length;
                            state.utf8TotalBytes += recordTotal;
                            state.utf8RecordCount++;
                            state.utf8StringBytes += recordTotal;

                            if (referencedNameIds.contains(nameId)) {
                                state.utf8ReferencedBytes += recordTotal;
                            } else {
                                state.utf8UnreferencedBytes += recordTotal;
                            }

                            if (dataLen > state.largestRecordBytes) {
                                state.largestRecordBytes = dataLen;
                                try {
                                    String decoded = ModifiedUtf8.decode(data);
                                    state.largestRecordSample = decoded.length() > 80
                                            ? decoded.substring(0, 80) : decoded;
                                } catch (Exception e) {
                                    state.largestRecordSample = "";
                                }
                            }
                        }

                        case HPROF_LOAD_CLASS -> {
                            in.skipFully(length);
                            state.loadClassBytes += 9 + length;
                        }

                        case HPROF_UNLOAD_CLASS, HPROF_HEAP_SUMMARY, HPROF_CPU_SAMPLES,
                             HPROF_CONTROL_SETTINGS, HPROF_ALLOC_SITES -> {
                            in.skipFully(length);
                            state.heapSummaryAndOtherBytes += 9 + length;
                        }

                        case HPROF_FRAME, HPROF_TRACE, HPROF_START_THREAD, HPROF_END_THREAD -> {
                            in.skipFully(length);
                            state.framesTracesThreadsBytes += 9 + length;
                        }

                        case HPROF_HEAP_DUMP_END -> {
                            in.skipFully(length);
                            state.heapDumpEndBytes += 9 + length;
                            lastWellFormedOffset = counting.position();
                        }

                        case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT -> {
                            long segmentStart = counting.position() - 9;
                            LimitedInputStream limited = new LimitedInputStream(counting, length);
                            HprofDataInput segmentIn = new HprofDataInput(limited);
                            segmentIn.setIdSize(header.idSize());

                            parseHeapDumpPayload(segmentIn, limited, header.idSize(), options.objectAlign,
                                    state, subrecordHistogram);

                            long remaining = limited.remaining();
                            if (remaining != 0) {
                                long consumed = length - remaining;
                                segmentIssues.add(new DiagnosticReport.SegmentIssue(
                                        segmentStart, length, consumed,
                                        "Segment consumed " + consumed + " bytes but declared " + length));
                                // drain remaining bytes
                                segmentIn.skipFully(remaining);
                            }
                        }

                        default -> {
                            in.skipFully(length);
                            state.unknownOrUnparseableBytes += 9 + length;
                        }
                    }
                }
            } catch (IOException e) {
                trailingOffset = counting.position();
                trailingReason = "parse error: " + e.getMessage();
            }
        }

        // ---- Pass 3: HeaderScanner ----
        List<DiagnosticReport.HeaderOccurrence> allHeaders = HeaderScanner.scan(inputPath);

        // ---- Build report ----
        long fileSizeBytes = Files.size(inputPath);

        DiagnosticReport.FileSummary fileSummary = new DiagnosticReport.FileSummary(
                inputPath.toString(),
                fileSizeBytes,
                headerMagicPass1,
                idSizePass1,
                timestampMsPass1
        );

        List<DiagnosticReport.RecordStat> recordStats = new ArrayList<>();
        for (RecordHistogram.Entry e : topLevelHistogram.entries()) {
            recordStats.add(new DiagnosticReport.RecordStat(
                    e.tag(), tagName(e.tag()), e.count(), e.totalBytes()));
        }

        List<DiagnosticReport.SubrecordStat> subrecordStats = new ArrayList<>();
        for (RecordHistogram.Entry e : subrecordHistogram.entries()) {
            subrecordStats.add(new DiagnosticReport.SubrecordStat(
                    e.tag(), subTagName(e.tag()), e.count(), e.totalBytes()));
        }

        DiagnosticReport.SizeAttribution sizeAttribution = new DiagnosticReport.SizeAttribution(
                state.heapObjectInstanceBytes,
                state.heapObjectObjArrayBytes,
                state.heapObjectPrimArrayBytes,
                state.classDumpBytes,
                state.gcRootBytes,
                state.utf8StringBytes,
                state.loadClassBytes,
                state.framesTracesThreadsBytes,
                state.heapSummaryAndOtherBytes,
                state.segmentFramingBytes,
                state.heapDumpEndBytes,
                state.unknownOrUnparseableBytes,
                state.matCompressed,
                state.matUncompressed
        );

        DiagnosticReport.Utf8Analysis utf8Analysis = new DiagnosticReport.Utf8Analysis(
                state.utf8TotalBytes,
                state.utf8RecordCount,
                state.utf8ReferencedBytes,
                state.utf8UnreferencedBytes,
                state.largestRecordBytes,
                state.largestRecordSample,
                state.utf8TotalBytes > fileSizeBytes * 0.05
        );

        List<DiagnosticReport.TopClass> topClassesList = classStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                .limit(options.topN)
                .map(e -> {
                    long cid = e.getKey();
                    long[] s = e.getValue();
                    String name = classNames.getOrDefault(cid, "class#" + cid);
                    return new DiagnosticReport.TopClass(cid, name, s[0], s[1], s[2], s[3]);
                })
                .collect(Collectors.toList());

        List<DiagnosticReport.TopArray> topArraysList = topArrays.topEntries().stream()
                .map(TopNTracker.Entry::value)
                .collect(Collectors.toList());

        DiagnosticReport.TrailingBytes trailingBytesRecord = null;
        if (trailingOffset >= 0) {
            trailingBytesRecord = new DiagnosticReport.TrailingBytes(
                    trailingOffset, -1, new byte[0], new byte[0], trailingReason);
        }

        List<DiagnosticReport.DuplicateId> duplicateIdsList;
        if (state.duplicateIdWarning != null) {
            duplicateIdsList = null; // OOM aborted
        } else if (!options.detectDuplicateIds) {
            duplicateIdsList = Collections.emptyList();
        } else {
            duplicateIdsList = duplicateIdCounts.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(20)
                    .map(e -> new DiagnosticReport.DuplicateId(
                            e.getKey(),
                            e.getValue() + 1, // +1: count is extra occurrences beyond first
                            duplicateIdKind.getOrDefault(e.getKey(), "UNKNOWN")))
                    .collect(Collectors.toList());
        }

        return new DiagnosticReport(
                fileSummary,
                recordStats,
                subrecordStats,
                sizeAttribution,
                utf8Analysis,
                topClassesList,
                topArraysList,
                segmentIssues,
                allHeaders,
                trailingBytesRecord,
                duplicateIdsList,
                state.duplicateIdWarning
        );
    }

    // ---- Pass 1 ----

    private static void scanForMetadata(
            HprofDataInput in, int idSize,
            Map<Long, String> utf8Strings,
            Set<Long> referencedNameIds,
            Map<Long, String> classNames,
            Map<Long, Long> classNameIds) throws IOException {

        while (true) {
            int tag = in.readTag();
            if (tag < 0) break;
            /* long time = */ in.readU4();
            long length = in.readU4();

            switch (tag) {
                case HPROF_UTF8 -> {
                    long nameId = in.readId();
                    long dataLen = length - idSize;
                    if (dataLen < 0) dataLen = 0;
                    if (dataLen > Integer.MAX_VALUE)
                        throw new IOException("UTF8 record too large: " + dataLen);
                    byte[] data = new byte[(int) dataLen];
                    in.readFully(data);
                    try {
                        utf8Strings.put(nameId, ModifiedUtf8.decode(data));
                    } catch (Exception e) {
                        utf8Strings.put(nameId, "");
                    }
                }
                case HPROF_LOAD_CLASS -> {
                    in.skipFully(4); // classSerial
                    long classId = in.readId();
                    in.skipFully(4); // stackTraceSerial
                    long nameId = in.readId();
                    referencedNameIds.add(nameId);
                    classNameIds.put(classId, nameId);
                }
                case HPROF_FRAME -> {
                    in.skipFully(idSize); // frameId
                    long methodNameId = in.readId();
                    long methodSigId = in.readId();
                    long sourceFileNameId = in.readId();
                    in.skipFully(4 + 4); // classSerial, lineNumber
                    referencedNameIds.add(methodNameId);
                    referencedNameIds.add(methodSigId);
                    referencedNameIds.add(sourceFileNameId);
                }
                case HPROF_START_THREAD -> {
                    in.skipFully(4); // threadSerial
                    in.skipFully(idSize); // threadObjectId
                    in.skipFully(4); // stackTraceSerial
                    long threadNameId = in.readId();
                    long threadGroupNameId = in.readId();
                    long threadGroupParentNameId = in.readId();
                    referencedNameIds.add(threadNameId);
                    referencedNameIds.add(threadGroupNameId);
                    referencedNameIds.add(threadGroupParentNameId);
                }
                case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT ->
                        scanHeapDumpSegmentForMeta(in, length, idSize, referencedNameIds);
                default -> in.skipFully(length);
            }
        }

        // Resolve classId -> className via nameId
        for (Map.Entry<Long, Long> e : classNameIds.entrySet()) {
            String name = utf8Strings.get(e.getValue());
            if (name != null) {
                classNames.put(e.getKey(), name);
            }
        }
    }

    private static void scanHeapDumpSegmentForMeta(
            HprofDataInput in, long length, int idSize,
            Set<Long> referencedNameIds) throws IOException {

        LimitedInputStream limited = new LimitedInputStream(in.rawStream(), length);
        HprofDataInput segmentIn = new HprofDataInput(limited);
        segmentIn.setIdSize(idSize);

        while (limited.remaining() > 0) {
            int subTag = segmentIn.readU1();
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED ->
                        segmentIn.skipFully(idSize);
                case HPROF_GC_ROOT_JNI_GLOBAL ->
                        segmentIn.skipFully((long) idSize * 2);
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME, HPROF_GC_ROOT_THREAD_OBJ ->
                        segmentIn.skipFully(idSize + 4 + 4);
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK ->
                        segmentIn.skipFully(idSize + 4);
                case HPROF_GC_CLASS_DUMP ->
                        scanClassDumpForMeta(segmentIn, idSize, referencedNameIds);
                case HPROF_GC_INSTANCE_DUMP -> {
                    segmentIn.skipFully((long) idSize * 2 + 4);
                    long dataLength = segmentIn.readU4();
                    segmentIn.skipFully(dataLength);
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    segmentIn.skipFully(idSize + 4);
                    long numElements = segmentIn.readU4();
                    segmentIn.skipFully(idSize); // arrayClassId
                    segmentIn.skipFully(numElements * idSize);
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    segmentIn.skipFully(idSize + 4);
                    long numElements = segmentIn.readU4();
                    HprofType elementType = HprofType.fromCode(segmentIn.readU1());
                    segmentIn.skipFully(numElements * (long) MatShallowSizeEstimator.primitiveSize(elementType));
                }
                default -> throw new IOException(
                        "Unsupported heap dump subrecord tag: 0x" + Integer.toHexString(subTag));
            }
        }
    }

    private static void scanClassDumpForMeta(
            HprofDataInput in, int idSize,
            Set<Long> referencedNameIds) throws IOException {
        // classId(id) + stackTraceSerial(u4) + superClassId(id) + classLoaderId(id)
        // + signersId(id) + protectionDomainId(id) + reserved1(id) + reserved2(id) = 1+1+6 = 8 ids before instanceSize
        // Actually: classId=1 id, stackTrace=u4, then 6 more ids, then instanceSize=u4
        in.skipFully(idSize);           // classId
        in.skipFully(4);                // stackTraceSerial
        in.skipFully((long) idSize * 6);// superClassId through reserved2
        in.skipFully(4);                // instanceSize

        int cpSize = in.readU2();
        for (int i = 0; i < cpSize; i++) {
            in.skipFully(2); // cp index
            HprofType type = HprofType.fromCode(in.readU1());
            in.skipFully(sizeForType(type, idSize));
        }

        int staticCount = in.readU2();
        for (int i = 0; i < staticCount; i++) {
            long nameId = in.readId();
            HprofType type = HprofType.fromCode(in.readU1());
            in.skipFully(sizeForType(type, idSize));
            referencedNameIds.add(nameId);
        }

        int instanceCount = in.readU2();
        for (int i = 0; i < instanceCount; i++) {
            long nameId = in.readId();
            in.skipFully(1); // type code
            referencedNameIds.add(nameId);
        }
    }

    // ---- Pass 2 ----

    private static void parseHeapDumpPayload(
            HprofDataInput segmentIn,
            LimitedInputStream limited,
            int idSize,
            int objectAlign,
            Pass2State state,
            RecordHistogram subrecordHistogram) throws IOException {

        while (limited.remaining() > 0) {
            int subTag = segmentIn.readU1();
            // 1-byte subtag goes to segmentFramingBytes
            state.segmentFramingBytes += 1;

            long subRecordPayload; // bytes of payload NOT including the 1-byte subtag

            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN -> {
                    segmentIn.skipFully(idSize);
                    subRecordPayload = idSize;
                    state.gcRootBytes += 1 + subRecordPayload;
                }
                case HPROF_GC_ROOT_JNI_GLOBAL -> {
                    segmentIn.skipFully((long) idSize * 2);
                    subRecordPayload = (long) idSize * 2;
                    state.gcRootBytes += 1 + subRecordPayload;
                }
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME, HPROF_GC_ROOT_THREAD_OBJ -> {
                    segmentIn.skipFully(idSize + 4 + 4);
                    subRecordPayload = idSize + 4 + 4;
                    state.gcRootBytes += 1 + subRecordPayload;
                }
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> {
                    segmentIn.skipFully(idSize + 4);
                    subRecordPayload = idSize + 4;
                    state.gcRootBytes += 1 + subRecordPayload;
                }
                case HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> {
                    segmentIn.skipFully(idSize);
                    subRecordPayload = idSize;
                    state.gcRootBytes += 1 + subRecordPayload;
                }
                case HPROF_GC_CLASS_DUMP -> {
                    subRecordPayload = parseClassDump(segmentIn, idSize);
                    state.classDumpBytes += 1 + subRecordPayload;
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objectId = segmentIn.readId();
                    segmentIn.skipFully(4); // stackTrace
                    long classId = segmentIn.readId();
                    long dataLength = segmentIn.readU4();
                    segmentIn.skipFully(dataLength);

                    subRecordPayload = (long) idSize * 2 + 8 + dataLength;
                    state.heapObjectInstanceBytes += subRecordPayload;

                    long[] cstats = state.classStats.computeIfAbsent(classId, k -> new long[4]);
                    cstats[0]++;
                    cstats[1] += dataLength;
                    long matComp = MatShallowSizeEstimator.instanceMatSize(dataLength);
                    long matUncomp = MatShallowSizeEstimator.instanceMatSize(dataLength);
                    cstats[2] += matComp;
                    cstats[3] += matUncomp;
                    state.matCompressed += matComp;
                    state.matUncompressed += matUncomp;

                    state.trackId(objectId, "INSTANCE_DUMP");
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long arrayId = segmentIn.readId();
                    segmentIn.skipFully(4); // stackTrace
                    long numElements = segmentIn.readU4();
                    /* long arrayClassId = */ segmentIn.readId();
                    segmentIn.skipFully(numElements * idSize);

                    subRecordPayload = (long) idSize * 2 + 4 + 4 + numElements * idSize;
                    state.heapObjectObjArrayBytes += subRecordPayload;

                    long matComp = MatShallowSizeEstimator.objArrayMatSize(numElements, idSize, 4, objectAlign);
                    long matUncomp = MatShallowSizeEstimator.objArrayMatSize(numElements, idSize, idSize, objectAlign);
                    state.matCompressed += matComp;
                    state.matUncompressed += matUncomp;

                    long diskBytes = 1 + subRecordPayload;
                    state.topArrays.add(diskBytes, new DiagnosticReport.TopArray(
                            arrayId, "OBJ", numElements, diskBytes, matComp, matUncomp));

                    state.trackId(arrayId, "OBJ_ARRAY_DUMP");
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    long arrayId = segmentIn.readId();
                    segmentIn.skipFully(4); // stackTrace
                    long numElements = segmentIn.readU4();
                    int elementTypeCode = segmentIn.readU1();
                    HprofType elementType = HprofType.fromCode(elementTypeCode);
                    int elemSize = MatShallowSizeEstimator.primitiveSize(elementType);
                    segmentIn.skipFully(numElements * elemSize);

                    subRecordPayload = idSize + 4 + 4 + 1 + numElements * (long) elemSize;
                    state.heapObjectPrimArrayBytes += subRecordPayload;

                    long matComp = MatShallowSizeEstimator.primArrayMatSize(numElements, elementType, idSize, 4, objectAlign);
                    long matUncomp = MatShallowSizeEstimator.primArrayMatSize(numElements, elementType, idSize, idSize, objectAlign);
                    state.matCompressed += matComp;
                    state.matUncompressed += matUncomp;

                    long diskBytes = 1 + subRecordPayload;
                    String arrayType = "PRIM:" + elementType.name().toLowerCase();
                    state.topArrays.add(diskBytes, new DiagnosticReport.TopArray(
                            arrayId, arrayType, numElements, diskBytes, matComp, matUncomp));

                    state.trackId(arrayId, "PRIM_ARRAY_DUMP");
                }
                default -> throw new IOException(
                        "Unsupported heap dump subrecord tag: 0x" + Integer.toHexString(subTag));
            }

            subrecordHistogram.record(subTag, 1 + subRecordPayload);
        }
    }

    /** Returns the number of payload bytes for a CLASS_DUMP subrecord (not including the 1-byte subtag). */
    private static long parseClassDump(HprofDataInput in, int idSize) throws IOException {
        // classId(id) + stackTraceSerial(u4) + superClassId+...+reserved2 (6 ids) + instanceSize(u4)
        in.skipFully(idSize);           // classId
        in.skipFully(4);                // stackTraceSerial
        in.skipFully((long) idSize * 6);// superClassId, classLoaderId, signersId, protectionDomainId, reserved1, reserved2
        in.skipFully(4);                // instanceSize

        long bytes = (long) idSize * 7 + 8;

        int cpSize = in.readU2();
        bytes += 2;
        for (int i = 0; i < cpSize; i++) {
            in.skipFully(2); // cp index
            HprofType type = HprofType.fromCode(in.readU1());
            long valueSize = sizeForType(type, idSize);
            in.skipFully(valueSize);
            bytes += 2 + 1 + valueSize;
        }

        int staticCount = in.readU2();
        bytes += 2;
        for (int i = 0; i < staticCount; i++) {
            in.skipFully(idSize); // nameId
            HprofType type = HprofType.fromCode(in.readU1());
            long valueSize = sizeForType(type, idSize);
            in.skipFully(valueSize);
            bytes += idSize + 1 + valueSize;
        }

        int instanceCount = in.readU2();
        bytes += 2;
        for (int i = 0; i < instanceCount; i++) {
            in.skipFully(idSize); // nameId
            in.skipFully(1);     // type code
            bytes += idSize + 1;
        }

        return bytes;
    }

    private static long sizeForType(HprofType type, int idSize) {
        return switch (type) {
            case OBJECT, ARRAY_OBJECT -> idSize;
            case BOOLEAN, BYTE -> 1;
            case CHAR, SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
        };
    }

    // ---- Header parsing ----

    private static HprofHeader readHeader(HprofDataInput in) throws IOException {
        StringBuilder magic = new StringBuilder();
        while (true) {
            int b = in.readU1();
            if (b == 0) break;
            magic.append((char) b);
        }
        int idSize = (int) in.readU4();
        long timestamp = in.readU8();
        return new HprofHeader(magic.toString(), idSize, timestamp);
    }

    private record HprofHeader(String magic, int idSize, long timestamp) {}

    // ---- Tag name helpers ----

    static String tagName(int tag) {
        return switch (tag) {
            case 0x01 -> "HPROF_UTF8";
            case 0x02 -> "HPROF_LOAD_CLASS";
            case 0x03 -> "HPROF_UNLOAD_CLASS";
            case 0x04 -> "HPROF_FRAME";
            case 0x05 -> "HPROF_TRACE";
            case 0x06 -> "HPROF_ALLOC_SITES";
            case 0x07 -> "HPROF_HEAP_SUMMARY";
            case 0x0A -> "HPROF_START_THREAD";
            case 0x0B -> "HPROF_END_THREAD";
            case 0x0C -> "HPROF_HEAP_DUMP";
            case 0x0D -> "HPROF_CPU_SAMPLES";
            case 0x0E -> "HPROF_CONTROL_SETTINGS";
            case 0x1C -> "HPROF_HEAP_DUMP_SEGMENT";
            case 0x2C -> "HPROF_HEAP_DUMP_END";
            default -> "UNKNOWN(0x" + Integer.toHexString(tag) + ")";
        };
    }

    static String subTagName(int subTag) {
        return switch (subTag) {
            case 0xFF -> "HPROF_GC_ROOT_UNKNOWN";
            case 0x01 -> "HPROF_GC_ROOT_JNI_GLOBAL";
            case 0x02 -> "HPROF_GC_ROOT_JNI_LOCAL";
            case 0x03 -> "HPROF_GC_ROOT_JAVA_FRAME";
            case 0x04 -> "HPROF_GC_ROOT_NATIVE_STACK";
            case 0x05 -> "HPROF_GC_ROOT_STICKY_CLASS";
            case 0x06 -> "HPROF_GC_ROOT_THREAD_BLOCK";
            case 0x07 -> "HPROF_GC_ROOT_MONITOR_USED";
            case 0x08 -> "HPROF_GC_ROOT_THREAD_OBJ";
            case 0x20 -> "HPROF_GC_CLASS_DUMP";
            case 0x21 -> "HPROF_GC_INSTANCE_DUMP";
            case 0x22 -> "HPROF_GC_OBJ_ARRAY_DUMP";
            case 0x23 -> "HPROF_GC_PRIM_ARRAY_DUMP";
            default -> "UNKNOWN(0x" + Integer.toHexString(subTag) + ")";
        };
    }

    // ---- Inner classes ----

    /** Mutable state bag for pass 2. */
    private static final class Pass2State {
        final int idSize;
        final int objectAlign;
        final RecordHistogram topLevelHistogram;
        final RecordHistogram subrecordHistogram;
        final Set<Long> referencedNameIds;
        final Map<Long, long[]> classStats;
        final TopNTracker<DiagnosticReport.TopArray> topArrays;
        final List<DiagnosticReport.SegmentIssue> segmentIssues;

        long heapObjectInstanceBytes;
        long heapObjectObjArrayBytes;
        long heapObjectPrimArrayBytes;
        long classDumpBytes;
        long gcRootBytes;
        long utf8StringBytes;
        long loadClassBytes;
        long framesTracesThreadsBytes;
        long heapSummaryAndOtherBytes;
        long segmentFramingBytes;
        long heapDumpEndBytes;
        long unknownOrUnparseableBytes;
        long matCompressed;
        long matUncompressed;

        long utf8TotalBytes;
        long utf8RecordCount;
        long utf8ReferencedBytes;
        long utf8UnreferencedBytes;
        long largestRecordBytes;
        String largestRecordSample = "";

        LongHashSet seenIds;
        final Map<Long, Integer> duplicateIdCounts;
        final Map<Long, String> duplicateIdKind;
        String duplicateIdWarning = null;

        Pass2State(int idSize, int objectAlign,
                   RecordHistogram topLevelHistogram,
                   RecordHistogram subrecordHistogram,
                   Set<Long> referencedNameIds,
                   Map<Long, long[]> classStats,
                   TopNTracker<DiagnosticReport.TopArray> topArrays,
                   LongHashSet seenIds,
                   Map<Long, Integer> duplicateIdCounts,
                   Map<Long, String> duplicateIdKind,
                   List<DiagnosticReport.SegmentIssue> segmentIssues) {
            this.idSize = idSize;
            this.objectAlign = objectAlign;
            this.topLevelHistogram = topLevelHistogram;
            this.subrecordHistogram = subrecordHistogram;
            this.referencedNameIds = referencedNameIds;
            this.classStats = classStats;
            this.topArrays = topArrays;
            this.seenIds = seenIds;
            this.duplicateIdCounts = duplicateIdCounts;
            this.duplicateIdKind = duplicateIdKind;
            this.segmentIssues = segmentIssues;
        }

        void trackId(long objectId, String kind) {
            if (seenIds == null) return;
            boolean added = seenIds.add(objectId);
            if (!added) {
                if (seenIds.contains(objectId)) {
                    duplicateIdCounts.merge(objectId, 1, Integer::sum);
                    duplicateIdKind.put(objectId, kind);
                } else {
                    // OOM
                    duplicateIdWarning = "Duplicate-ID detection ran out of memory after "
                            + seenIds.size()
                            + " objects. Re-run with more heap (-Xmx), or omit --detect-duplicate-ids."
                            + " Partial results discarded.";
                    System.err.println("WARNING: " + duplicateIdWarning);
                    seenIds = null;
                }
            }
        }
    }

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
            if (remaining <= 0) return -1;
            int value = in.read();
            if (value < 0) throw new EOFException("Unexpected end of stream");
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(length, remaining);
            int read = in.read(buffer, offset, toRead);
            if (read < 0) throw new EOFException("Unexpected end of stream");
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

    private static final class CountingInputStream extends InputStream {
        private final InputStream in;
        private long position = 0;

        CountingInputStream(InputStream in) {
            this.in = in;
        }

        long position() {
            return position;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) position++;
            return b;
        }

        @Override
        public int read(byte[] buf, int off, int len) throws IOException {
            int n = in.read(buf, off, len);
            if (n > 0) position += n;
            return n;
        }
    }
}
