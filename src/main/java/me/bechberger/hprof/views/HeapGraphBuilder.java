/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import me.bechberger.hprof.HprofType;
import me.bechberger.hprof.ModifiedUtf8;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.Map;

import static me.bechberger.hprof.HprofConstants.*;

/**
 * Builds a HeapGraph from an HPROF file using three sequential passes:
 * <ol>
 *   <li>Phase A.1 — collect addresses, class metadata, shallow sizes, GC roots</li>
 *   <li>Phase A.2 — resolve edges (addresses now indexed), build forward CSR + count inDegree</li>
 *   <li>Phase B — fill inbound CSR targets; VByte-encode after fill</li>
 * </ol>
 *
 * Each pass opens the file independently via FileChannel with a 1 MB direct ByteBuffer,
 * minimising JVM heap pressure from buffering.
 */
public final class HeapGraphBuilder {

    private static final int BUFFER_SIZE = 1 << 20; // 1 MB direct buffer per pass

    private final Path path;
    private final long fileSize;
    private final boolean gzipped;

    public HeapGraphBuilder(Path path) throws IOException {
        this.path = path;
        this.fileSize = Files.size(path);
        this.gzipped = detectGzip(path);
    }

    private static boolean detectGzip(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            int b1 = in.read();
            int b2 = in.read();
            return b1 == 0x1f && b2 == 0x8b;
        }
    }

    /** Open a fresh Parser for one pass. For gzipped files, decompresses from byte 0. */
    private Parser openParser() throws IOException {
        if (gzipped) {
            InputStream raw = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE);
            InputStream gz  = new GZIPInputStream(raw, BUFFER_SIZE);
            return new Parser(gz);
        }
        return new Parser(path);
    }

    /** Run all three phases and return the fully-populated HeapGraph. */
    public HeapGraph build() throws IOException {
        IdMap idMap = new IdMap();
        // --- Phase A.1 ---
        HeapGraph graph = phaseA1(idMap);
        // --- Phase A.2 ---
        phaseA2(graph);
        // --- RPO DFS (uses forward CSR, frees it after) ---
        RpoDfs.compute(graph);
        // --- Phase B ---
        phaseB(graph);
        // --- Dominator tree (CHK) ---
        DominatorTree.compute(graph);
        // --- Count unreachable objects (before retained sizes) ---
        graph.computeUnreachableStats();
        // --- Retained sizes ---
        RetainedSizes.compute(graph);
        return graph;
    }

    /**
     * Run all phases without freeing intermediate structures.
     * For unit tests only — allows inspecting graph state after build.
     */
    HeapGraph buildForTesting() throws IOException {
        return build();
    }

    // =========================================================
    // Phase A.1: collect addresses + metadata
    // =========================================================

    private HeapGraph phaseA1(IdMap idMap) throws IOException {
        // Estimate N and E from file size (~48 bytes/object, ~2 edges/object)
        int nEstimated = Math.max(64, (int) Math.min(fileSize / 48, Integer.MAX_VALUE / 2L));
        int eEstimated = nEstimated * 2;

        try (Parser p = openParser()) {
            HeapGraph graph = new HeapGraph(path, p.idSize(), fileSize, p.hprofFormat(), idMap);

            // Per-object metadata arrays — allocate at estimated size, grow if needed
            // We use a temporary builder object to accumulate before we know final N.
            A1State state = new A1State(nEstimated, idMap);

            // Map from classId (long) → class serial number (for classIndex lookup later)
            Map<Long, Integer> classIdToSerial = new HashMap<>();
            // Map from classSerial → index in classList
            // We build classList during A.1 and finalise it.

            // --- Top-level record scan ---
            while (true) {
                int tag = p.readTag();
                if (tag < 0) break;
                p.readU4(); // timestamp
                long length = p.readU4();

                switch (tag) {
                    case HPROF_UTF8 -> {
                        long nameId = p.readId();
                        int strLen = (int) (length - p.idSize());
                        byte[] bytes = p.readBytes(strLen);
                        try {
                            graph.utf8Strings.put(nameId, ModifiedUtf8.decode(bytes));
                        } catch (IllegalArgumentException ex) {
                            graph.utf8Strings.put(nameId, new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
                        }
                    }
                    case HPROF_LOAD_CLASS -> {
                        int serial = (int) p.readU4();
                        long classId = p.readId();
                        p.readU4(); // stack trace serial
                        long nameId = p.readId();
                        state.classSerialToId.put(serial, classId);
                        graph.utf8Strings.putIfAbsent(nameId, "?");
                        // Will link classId→name later from utf8Strings
                        state.classIdToNameId.put(classId, nameId);
                        state.classIdToSerial.put(classId, serial);
                    }
                    case HPROF_FRAME -> {
                        long frameId = p.readId();
                        long methodNameId = p.readId();
                        p.skipFully(p.idSize() * 2L + 4 + 4); // sig, source, class serial, line
                        state.frames.put(frameId, methodNameId);
                    }
                    case HPROF_TRACE -> {
                        int traceSerial = (int) p.readU4();
                        p.readU4(); // thread serial (unused for now)
                        int frameCount = (int) p.readU4();
                        long[] frameIds = new long[frameCount];
                        for (int i = 0; i < frameCount; i++) frameIds[i] = p.readId();
                        state.traces.put(traceSerial, frameIds);
                    }
                    case HPROF_START_THREAD -> {
                        int threadSerial = (int) p.readU4();
                        long threadObjId = p.readId();
                        p.readU4(); // trace serial
                        p.skipFully(p.idSize() * 3L); // name, group, parent nameIds
                        state.threadSerialToObjId.put(threadSerial, threadObjId);
                    }
                    case HPROF_HEAP_DUMP, HPROF_HEAP_DUMP_SEGMENT ->
                        scanHeapSegmentA1(p, (int) length, graph, state);
                    default -> p.skipFully(length);
                }
            }

            // --- Finalise IdMap ---
            idMap.sort();
            int N = 1 + idMap.size(); // slot 0 = virtual root
            graph.N = N;

            // --- Allocate per-object arrays ---
            graph.shallowSizeDiv8 = state.flushShallowSizes(N);
            graph.classIndex = state.flushClassIndex(N);

            // Resolve GC roots to indices
            state.flushGCRoots(graph, idMap);

            // Build class list from gathered metadata
            state.buildClassList(graph, idMap);

            // Link thread serial → object index; trace frames
            for (Map.Entry<Integer, Long> e : state.threadSerialToObjId.entrySet()) {
                long objId = e.getValue();
                int idx = idMap.indexOf(objId);
                if (idx >= 0) graph.threadSerialToObjectId.put(e.getKey(), objId);
            }
            state.buildTraceFrames(graph);

            // Build synthetic thread→local edges from frame/stack roots
            graph.syntheticThreadEdges = buildSyntheticEdges(state, graph, idMap);

            // Resolve exclude pairs
            resolveExcludePairs(graph);

            return graph;
        }
    }

    private static Map<Integer, int[]> buildSyntheticEdges(A1State state, HeapGraph graph, IdMap idMap) {
        Map<Integer, int[]> result = new HashMap<>();
        for (Map.Entry<Integer, List<Long>> entry : state.threadLocalsBySerial.entrySet()) {
            int threadSerial = entry.getKey();
            Long threadObjId = graph.threadSerialToObjectId.get(threadSerial);
            if (threadObjId == null) continue;
            int threadIdx = idMap.indexOf(threadObjId);
            if (threadIdx < 0) continue;
            int threadIdxAdjusted = threadIdx + 1; // +1 for virtual root offset
            List<Long> localIds = entry.getValue();
            int[] localIdxArr = new int[localIds.size()];
            int count = 0;
            for (Long localId : localIds) {
                int localIdx = idMap.indexOf(localId);
                if (localIdx >= 0) localIdxArr[count++] = localIdx + 1; // +1 for virtual root offset
            }
            if (count > 0) result.put(threadIdxAdjusted, java.util.Arrays.copyOf(localIdxArr, count));
        }
        return result;
    }

    private void scanHeapSegmentA1(Parser p, int segLength, HeapGraph graph, A1State state) throws IOException {
        int remaining = segLength;
        int ids = p.idSize();
        while (remaining > 0) {
            int subTag = p.readU1(); remaining--;
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> {
                    long id = p.readId(); remaining -= ids;
                    state.appendAddress(id);
                    state.appendGCRoot(id, (byte) subTag);
                }
                case HPROF_GC_ROOT_JNI_GLOBAL -> {
                    long id = p.readId(); p.skipFully(ids); remaining -= ids * 2;
                    state.appendAddress(id);
                    state.appendGCRoot(id, (byte) subTag);
                }
                case HPROF_GC_ROOT_THREAD_OBJ -> {
                    long id = p.readId(); p.skipFully(4 + 4); remaining -= ids + 8;
                    state.appendAddress(id);
                    state.appendGCRoot(id, (byte) subTag);  // Thread object IS a GC root
                }
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME -> {
                    long localId = p.readId();
                    int threadSerial = (int) p.readU4();
                    p.skipFully(4); // frameNumber
                    remaining -= ids + 8;
                    state.appendAddress(localId);  // still needs to be in IdMap
                    // NOT a GC root — will be synthetic edge from thread to local
                    state.threadLocalsBySerial.computeIfAbsent(threadSerial, k -> new ArrayList<>()).add(localId);
                }
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> {
                    long localId = p.readId();
                    int threadSerial = (int) p.readU4();
                    remaining -= ids + 4;
                    state.appendAddress(localId);  // still needs to be in IdMap
                    // NOT a GC root — will be synthetic edge from thread to local
                    state.threadLocalsBySerial.computeIfAbsent(threadSerial, k -> new ArrayList<>()).add(localId);
                }
                case HPROF_GC_CLASS_DUMP -> {
                    int consumed = scanClassDumpA1(p, graph, state, ids);
                    remaining -= consumed;
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    long dataLen = p.readU4(); p.skipFully(dataLen);
                    remaining -= ids + 4 + ids + 4 + (int) dataLen;
                    state.appendAddress(objId);
                    // shallowSize is the instance data length + object header
                    // We approximate: store dataLen / 8 (will be corrected from instanceSize if available)
                    // Actually HPROF doesn't give us exact shallow size for instances in INSTANCE_DUMP;
                    // we get the *data bytes* (excluding header overhead). Use classRecord.instanceSize
                    // which is recorded in CLASS_DUMP. For now, store 0 (will be set in phaseA2 once
                    // classes are resolved). Better: record (dataLen + headerSize) here.
                    // JVM object header is typically 12-16 bytes. We'll use 16 (2 words, uncompressed).
                    int shallowBytes = (int) dataLen + 16; // approximate until class is known
                    state.appendShallowSize(objId, shallowBytes);
                    state.appendClassId(objId, classId);
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    long elemClassId = p.readId(); p.skipFully((long) numElem * ids);
                    remaining -= ids + 4 + 4 + ids + (long) numElem * ids;
                    state.appendAddress(objId);
                    // Shallow size: header + ref-size * numElem (approx 16 byte header)
                    int shallowBytes = 16 + numElem * ids;
                    state.appendShallowSize(objId, shallowBytes);
                    state.appendClassId(objId, elemClassId); // element class
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    int elemType = p.readU1();
                    int elemSize = primTypeSize(elemType);
                    long dataBytes = (long) numElem * elemSize;
                    // Record file position before skipping (for String/Thread name resolution)
                    // We can't know the file offset here without tracking it in Parser.
                    // Store a sentinel; actual offset tracked in phaseA2.
                    p.skipFully(dataBytes);
                    remaining -= ids + 4 + 4 + 1 + (int) dataBytes;
                    state.appendAddress(objId);
                    int shallowBytes = 16 + numElem * elemSize;
                    state.appendShallowSize(objId, shallowBytes);
                    // mark as prim array with type
                    state.primArrayTypes.put(objId, (byte) elemType);
                }
                default -> throw new IOException("Unknown heap sub-record tag: 0x" + Integer.toHexString(subTag)
                        + " at remaining=" + remaining);
            }
        }
    }

    /** Returns bytes consumed. */
    private int scanClassDumpA1(Parser p, HeapGraph graph, A1State state, int ids) throws IOException {
        int consumed = 0;
        long classId = p.readId(); consumed += ids;
        int serial = (int) p.readU4(); consumed += 4;
        long superClassId = p.readId(); consumed += ids;
        long classLoaderId = p.readId(); consumed += ids;
        p.skipFully(ids * 4L); consumed += ids * 4; // signers, domain, reserved×2
        int instanceSize = (int) p.readU4(); consumed += 4;

        state.appendAddress(classId);
        state.classInstanceSizes.put(classId, instanceSize);
        state.classLoaderIds.put(classId, classLoaderId);
        state.classSuperIds.put(classId, superClassId);
        state.classSerialByClassId.put(classId, serial);

        // Constant pool
        int cpCount = p.readU2(); consumed += 2;
        for (int i = 0; i < cpCount; i++) {
            p.readU2(); consumed += 2; // constant pool index
            int type = p.readU1(); consumed += 1;
            int valSize = typeSize(type, ids);
            p.skipFully(valSize); consumed += valSize;
        }

        // Static fields (skip values, just read types for size)
        int sfCount = p.readU2(); consumed += 2;
        for (int i = 0; i < sfCount; i++) {
            p.skipFully(ids); consumed += ids; // name id
            int type = p.readU1(); consumed += 1;
            int valSize = typeSize(type, ids);
            p.skipFully(valSize); consumed += valSize;
        }

        // Instance fields — collect OBJECT-type fields
        int ifCount = p.readU2(); consumed += 2;
        List<long[]> objFields = new ArrayList<>(); // [nameId, offset]
        int offset = 0;
        for (int i = 0; i < ifCount; i++) {
            long nameId = p.readId(); consumed += ids;
            int type = p.readU1(); consumed += 1;
            if (type == HPROF_TYPE_OBJECT) {
                objFields.add(new long[]{nameId, offset});
            }
            offset += typeSize(type, ids);
        }
        state.classObjFields.put(classId, objFields);
        // Approximate shallow size from instanceSize
        state.appendShallowSize(classId, instanceSize > 0 ? instanceSize : 16);
        return consumed;
    }

    // =========================================================
    // Phase A.2: edge resolution + forward CSR + inDegree
    // =========================================================

    private void phaseA2(HeapGraph graph) throws IOException {
        int N = graph.N;
        int ids = graph.idSize;
        IdMap idMap = graph.idMap;
        int eEst = Math.max(N * 2, 64);

        // Allocate forward CSR + inDegree
        int[] fwdOffsets = new int[N + 1];
        int[] fwdCursor  = new int[N]; // write cursor for fwdTargets per src
        int[] inDegree   = new int[N];

        // Two-pass forward CSR: first pass just counts per-src out-degree
        // Then allocate fwdTargets, second fill pass.
        // For memory efficiency, do a single combined scan: count edges for inDegree
        // and fwdDegree in one pass, then fill in a second pass.
        // Actually three sub-passes: (1) count, (2) alloc+fill.
        // We do them as a single re-opened file scan that counts first, then
        // a second re-opened scan that fills. This is 2 file reads total for A.2.

        // --- Sub-pass A.2a: count edges ---
        final int[] outDegree = new int[N];
        final int[] inDegreeCount = inDegree; // effectively-final alias for lambda capture
        try (Parser p = openParser()) {
            scanEdges(p, graph, (srcIdx, dstIdx, nameId) -> {
                outDegree[srcIdx]++;
                inDegreeCount[dstIdx]++;
            });
        }
        // Count synthetic thread→local edges
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                int threadIdx = e.getKey();
                if (threadIdx < N) {
                    int[] locals = e.getValue();
                    outDegree[threadIdx] += locals.length;
                    for (int localIdx : locals) {
                        if (localIdx < N) inDegreeCount[localIdx]++;
                    }
                }
            }
        }

        // Prefix-sum outDegree → fwdOffsets (for fwdTargets allocation)
        int totalEdges = 0;
        for (int i = 0; i < N; i++) {
            fwdOffsets[i] = totalEdges;
            totalEdges += outDegree[i];
        }
        fwdOffsets[N] = totalEdges;
        int[] fwdTargets = new int[totalEdges];
        System.arraycopy(fwdOffsets, 0, fwdCursor, 0, N); // cursor starts at row start

        // Prefix-sum inDegree → inboundOffsets
        int[] inboundOffsets = new int[N + 1];
        int ibTotal = 0;
        for (int i = 0; i < N; i++) {
            inboundOffsets[i] = ibTotal;
            ibTotal += inDegree[i];
        }
        inboundOffsets[N] = ibTotal;

        // --- Sub-pass A.2b: fill forward CSR ---
        // (Inbound CSR fill is deferred to Phase B — RPO DFS only needs fwd CSR)
        final int[] fwdTargetsFinal = fwdTargets;
        final int[] fwdCursorFinal = fwdCursor;
        try (Parser p = openParser()) {
            scanEdges(p, graph, (srcIdx, dstIdx, nameId) -> {
                fwdTargetsFinal[fwdCursorFinal[srcIdx]++] = dstIdx;
            });
        }
        // Fill synthetic thread→local edges into forward CSR
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                int threadIdx = e.getKey();
                if (threadIdx < N) {
                    int[] locals = e.getValue();
                    for (int localIdx : locals) {
                        if (localIdx < N) fwdTargetsFinal[fwdCursorFinal[threadIdx]++] = localIdx;
                    }
                }
            }
        }

        // Store forward CSR for RPO DFS; store totalEdges for Phase B pre-allocation
        graph.fwdOffsets = fwdOffsets;
        graph.fwdTargets = fwdTargets;
        graph.totalEdges = ibTotal;
        // Note: graph.syntheticThreadEdges is kept alive for Phase B (inbound CSR)
    }

    @FunctionalInterface
    private interface EdgeConsumer {
        void accept(int srcIdx, int dstIdx, long nameId) throws IOException;
    }

    private void scanEdges(Parser p, HeapGraph graph, EdgeConsumer consumer) throws IOException {
        int ids = graph.idSize;
        IdMap idMap = graph.idMap;
        while (true) {
            int tag = p.readTag();
            if (tag < 0) break;
            p.readU4();
            long length = p.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                scanEdgesInSegment(p, (int) length, ids, idMap, graph, consumer);
            } else {
                p.skipFully(length);
            }
        }
    }

    private void scanEdgesInSegment(Parser p, int segLen, int ids, IdMap idMap,
                                    HeapGraph graph, EdgeConsumer consumer) throws IOException {
        int remaining = segLen;
        while (remaining > 0) {
            int subTag = p.readU1(); remaining--;
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> {
                    p.skipFully(ids); remaining -= ids;
                }
                case HPROF_GC_ROOT_JNI_GLOBAL -> { p.skipFully(ids * 2L); remaining -= ids * 2; }
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME, HPROF_GC_ROOT_THREAD_OBJ -> {
                    p.skipFully(ids + 8L); remaining -= ids + 8;
                }
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> {
                    p.skipFully(ids + 4L); remaining -= ids + 4;
                }
                case HPROF_GC_CLASS_DUMP -> {
                    // Skip class dump — classes don't have outbound ref edges in instance data
                    int consumed = skipClassDump(p, ids);
                    remaining -= consumed;
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    int dataLen = (int) p.readU4();
                    remaining -= ids + 4 + ids + 4;
                    int srcIdx = objectIndex(idMap, objId);
                    if (srcIdx < 0) { p.skipFully(dataLen); remaining -= dataLen; break; }
                    // Emit edges for each OBJECT-type field
                    Integer classIdx = graph.classIdToIndex.get(classId);
                    ClassRecord cr = classIdx != null ? graph.classList.get(classIdx) : null;
                    byte[] data = p.readBytes(dataLen); remaining -= dataLen;
                    if (cr != null) {
                        for (int fi = 0; fi < cr.objectFieldOffsets().length; fi++) {
                            int off = cr.objectFieldOffsets()[fi];
                            if (off + ids > data.length) continue; // malformed/truncated data
                            long refId = readIdFromBytes(data, off, ids);
                            if (refId != 0) {
                                int dstIdx = objectIndex(idMap, refId);
                                if (dstIdx >= 0) {
                                    consumer.accept(srcIdx, dstIdx, 0L);
                                }
                            }
                        }
                    }
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    p.skipFully(ids); // element class id
                    remaining -= ids + 4 + 4 + ids;
                    int srcIdx = objectIndex(idMap, objId);
                    for (int i = 0; i < numElem; i++) {
                        long refId = p.readId(); remaining -= ids;
                        if (refId != 0 && srcIdx >= 0) {
                            int dstIdx = objectIndex(idMap, refId);
                            if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, 0L);
                        }
                    }
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    int elemType = p.readU1();
                    long dataBytes = (long) numElem * primTypeSize(elemType);
                    p.skipFully(dataBytes);
                    remaining -= ids + 4 + 4 + 1 + (int) dataBytes;
                    // Primitive arrays have no object-ref edges
                }
                default -> throw new IOException("Unknown heap sub-record tag: 0x" + Integer.toHexString(subTag));
            }
        }
    }

    private int skipClassDump(Parser p, int ids) throws IOException {
        int consumed = 0;
        p.skipFully(ids); consumed += ids; // class id (already read in A.1, skip here)
        p.skipFully(4); consumed += 4;     // stack serial
        p.skipFully(ids * 6L); consumed += ids * 6; // super, loader, signers, domain, reserved×2
        p.skipFully(4); consumed += 4; // instance size
        int cpCount = p.readU2(); consumed += 2;
        for (int i = 0; i < cpCount; i++) {
            p.readU2(); consumed += 2;
            int type = p.readU1(); consumed += 1;
            int sz = typeSize(type, ids);
            p.skipFully(sz); consumed += sz;
        }
        int sfCount = p.readU2(); consumed += 2;
        for (int i = 0; i < sfCount; i++) {
            p.skipFully(ids); consumed += ids;
            int type = p.readU1(); consumed += 1;
            int sz = typeSize(type, ids);
            p.skipFully(sz); consumed += sz;
        }
        int ifCount = p.readU2(); consumed += 2;
        for (int i = 0; i < ifCount; i++) {
            p.skipFully(ids); consumed += ids;
            p.readU1(); consumed += 1; // type (size 0 for field defs)
        }
        return consumed;
    }

    // =========================================================
    // Phase B: fill inbound CSR targets + VByte encode
    // =========================================================

    private void phaseB(HeapGraph graph) throws IOException {
        int N = graph.N;
        int ids = graph.idSize;
        int totalEdges = graph.totalEdges;
        IdMap idMap = graph.idMap;

        // Allocate inbound targets + offsets
        int[] inboundOffsets = new int[N + 1];
        // Re-count inDegree (one more sequential scan; cheap for linear file read)
        final int[] inDegree = new int[N];
        try (Parser p = openParser()) {
            scanEdges(p, graph, (src, dst, nameId) -> inDegree[dst]++);
        }
        // Also count synthetic thread→local edges for inbound
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                for (int localIdx : e.getValue()) {
                    if (localIdx < N) inDegree[localIdx]++;
                }
            }
        }
        int total = 0;
        for (int i = 0; i < N; i++) { inboundOffsets[i] = total; total += inDegree[i]; }
        inboundOffsets[N] = total;
        final int[] inboundTargets = new int[total];
        System.arraycopy(inboundOffsets, 0, inDegree, 0, N); // repurpose as write cursor
        graph.inboundOffsets = inboundOffsets;

        // Resolve exclude pairs so CsrBuilder can evaluate them
        // (already done in phaseA1 → resolveExcludePairs)
        short[][] excludePairs = graph.excludePairs;

        try (Parser p = openParser()) {
            scanEdgesWithNames(p, graph, (srcIdx, dstIdx, nameIdx, srcClassIdx) -> {
                int pos = inDegree[dstIdx];
                boolean excluded = isExcluded(excludePairs, srcClassIdx, nameIdx);
                inboundTargets[pos] = excluded ? (srcIdx | Integer.MIN_VALUE) : srcIdx;
                inDegree[dstIdx]++;
            });
        }
        // Fill synthetic thread→local edges into inbound CSR (not excluded)
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                int threadIdx = e.getKey();
                for (int localIdx : e.getValue()) {
                    if (localIdx < N) {
                        int pos = inDegree[localIdx];
                        inboundTargets[pos] = threadIdx; // not excluded
                        inDegree[localIdx]++;
                    }
                }
            }
        }
        // Free synthetic edges — no longer needed
        graph.syntheticThreadEdges = null;

        // VByte encode (inboundOffsets is already the correct prefix sum)
        CsrBuilderEncoder encoder = new CsrBuilderEncoder(graph, inboundTargets, inboundOffsets, N);
        encoder.encodeVByte();
        // inboundTargets is freed inside encoder
    }

    @FunctionalInterface
    private interface NamedEdgeConsumer {
        void accept(int srcIdx, int dstIdx, short nameIdx, short srcClassIdx) throws IOException;
    }

    private void scanEdgesWithNames(Parser p, HeapGraph graph, NamedEdgeConsumer consumer) throws IOException {
        int ids = graph.idSize;
        IdMap idMap = graph.idMap;
        while (true) {
            int tag = p.readTag();
            if (tag < 0) break;
            p.readU4();
            long length = p.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                scanEdgesWithNamesInSegment(p, (int) length, ids, idMap, graph, consumer);
            } else {
                p.skipFully(length);
            }
        }
    }

    private void scanEdgesWithNamesInSegment(Parser p, int segLen, int ids, IdMap idMap,
                                              HeapGraph graph, NamedEdgeConsumer consumer) throws IOException {
        int remaining = segLen;
        while (remaining > 0) {
            int subTag = p.readU1(); remaining--;
            switch (subTag) {
                case HPROF_GC_ROOT_UNKNOWN, HPROF_GC_ROOT_STICKY_CLASS, HPROF_GC_ROOT_MONITOR_USED -> {
                    p.skipFully(ids); remaining -= ids;
                }
                case HPROF_GC_ROOT_JNI_GLOBAL -> { p.skipFully(ids * 2L); remaining -= ids * 2; }
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME, HPROF_GC_ROOT_THREAD_OBJ -> {
                    p.skipFully(ids + 8L); remaining -= ids + 8;
                }
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> {
                    p.skipFully(ids + 4L); remaining -= ids + 4;
                }
                case HPROF_GC_CLASS_DUMP -> {
                    remaining -= skipClassDump(p, ids);
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    int dataLen = (int) p.readU4();
                    remaining -= ids + 4 + ids + 4;
                    int srcIdx = objectIndex(idMap, objId);
                    Integer classIdx = graph.classIdToIndex.get(classId);
                    ClassRecord cr = classIdx != null ? graph.classList.get(classIdx) : null;
                    byte[] data = p.readBytes(dataLen); remaining -= dataLen;
                    if (srcIdx >= 0 && cr != null) {
                        short srcClassIdx = classIdx != null ? (short)(int)classIdx : 0;
                        for (int fi = 0; fi < cr.objectFieldOffsets().length; fi++) {
                            int off = cr.objectFieldOffsets()[fi];
                            if (off + ids > data.length) continue;
                            long refId = readIdFromBytes(data, off, ids);
                            if (refId != 0) {
                                int dstIdx = objectIndex(idMap, refId);
                                if (dstIdx >= 0) {
                                    short nameIdx = fi < cr.objectFieldNameIds().length
                                            ? cr.objectFieldNameIds()[fi] : ClassRecord.NO_NAME;
                                    consumer.accept(srcIdx, dstIdx, nameIdx, srcClassIdx);
                                }
                            }
                        }
                    }
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    p.skipFully(ids); remaining -= ids + 4 + 4 + ids;
                    int srcIdx = objectIndex(idMap, objId);
                    Integer classIdx = graph.classIdToIndex.get(objId); // element class — use src class
                    short srcClassIdx = classIdx != null ? (short)(int)classIdx : 0;
                    for (int i = 0; i < numElem; i++) {
                        long refId = p.readId(); remaining -= ids;
                        if (refId != 0 && srcIdx >= 0) {
                            int dstIdx = objectIndex(idMap, refId);
                            if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, ClassRecord.NO_NAME, srcClassIdx);
                        }
                    }
                }
                case HPROF_GC_PRIM_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    int elemType = p.readU1();
                    long dataBytes = (long) numElem * primTypeSize(elemType);
                    p.skipFully(dataBytes);
                    remaining -= ids + 4 + 4 + 1 + (int) dataBytes;
                }
                default -> throw new IOException("Unknown heap sub-record tag: 0x" + Integer.toHexString(subTag));
            }
        }
    }

    // =========================================================
    // Helpers
    // =========================================================

    private static void skipToHeapSection(Parser p) throws IOException {
        while (true) {
            int tag = p.readTag();
            if (tag < 0) break;
            p.readU4();
            long length = p.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                // Found first heap dump record — caller processes from here
                // We push back the length by storing it; but Parser doesn't support unread.
                // Solution: put the length back as context.
                // Actually, we need to handle the segment content starting here.
                // We redesign: return the *first segment length* and let the caller handle it.
                // For simplicity, re-scan from file start instead. The actual segments are
                // processed in scanEdges which handles the top-level loop itself.
                return;
            }
            p.skipFully(length);
        }
    }

    private static int objectIndex(IdMap idMap, long objId) {
        int idx = idMap.indexOf(objId);
        return idx >= 0 ? idx + 1 : -1; // +1 for virtual root offset
    }

    private static long readIdFromBytes(byte[] data, int offset, int idSize) {
        if (idSize == 4) {
            return Integer.toUnsignedLong(
                (data[offset] << 24) | ((data[offset+1] & 0xFF) << 16) |
                ((data[offset+2] & 0xFF) << 8) | (data[offset+3] & 0xFF));
        } else {
            long v = 0;
            for (int i = 0; i < 8; i++) v = (v << 8) | (data[offset + i] & 0xFF);
            return v;
        }
    }

    private static int typeSize(int type, int ids) {
        return switch (type) {
            case HPROF_TYPE_OBJECT, 1 -> ids; // 1 = ARRAY_OBJECT
            case HPROF_TYPE_BOOLEAN, HPROF_TYPE_BYTE -> 1;
            case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> 2;
            case HPROF_TYPE_FLOAT, HPROF_TYPE_INT -> 4;
            case HPROF_TYPE_DOUBLE, HPROF_TYPE_LONG -> 8;
            default -> 0;
        };
    }

    private static int primTypeSize(int type) {
        return switch (type) {
            case HPROF_TYPE_BOOLEAN, HPROF_TYPE_BYTE -> 1;
            case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> 2;
            case HPROF_TYPE_FLOAT, HPROF_TYPE_INT -> 4;
            case HPROF_TYPE_DOUBLE, HPROF_TYPE_LONG -> 8;
            default -> 1;
        };
    }

    private static boolean isExcluded(short[][] pairs, short classIdx, short nameIdx) {
        if (pairs == null || nameIdx == ClassRecord.NO_NAME) return false;
        for (short[] pair : pairs) {
            if (pair[0] == classIdx && pair[1] == nameIdx) return true;
        }
        return false;
    }

    private static void resolveExcludePairs(HeapGraph graph) {
        // 3 default exclude pairs: Reference:referent, Finalizer:unfinalized, Runtime:<Unfinalized>
        String[][] defaults = {
            {"java/lang/ref/Reference", "referent"},
            {"java/lang/ref/Finalizer", "unfinalized"},
            {"java/lang/Runtime", "<Unfinalized>"}
        };
        List<short[]> resolved = new ArrayList<>();
        for (String[] pair : defaults) {
            String className = pair[0];
            String fieldName = pair[1];
            // Find classIdx
            short classIdx = -1;
            for (int i = 0; i < graph.classList.size(); i++) {
                if (graph.classList.get(i).name().equals(className)) {
                    classIdx = (short) i;
                    break;
                }
            }
            if (classIdx < 0) continue;
            // Find fieldNameIdx
            Short nameIdx = null;
            for (Map.Entry<Long, Short> e : graph.fieldNameIntern.entrySet()) {
                if (fieldName.equals(graph.fieldNameFor(e.getValue()))) {
                    nameIdx = e.getValue();
                    break;
                }
            }
            if (nameIdx == null) continue;
            resolved.add(new short[]{classIdx, nameIdx});
        }
        graph.excludePairs = resolved.toArray(new short[0][]);
    }

    // =========================================================
    // A1State: temporary accumulation buffers during Phase A.1
    // =========================================================

    private static final class A1State {
        private final IdMap idMap;
        // Dynamic address + metadata buffers (parallel arrays, grown together)
        private long[] addrBuf;
        private int[] shallowBuf; // shallow size in bytes
        private long[] classIdBuf; // classId of each object
        private int count;

        final Map<Integer, Long> classSerialToId = new HashMap<>();
        final Map<Long, Long> classIdToNameId = new HashMap<>();
        final Map<Long, Integer> classIdToSerial = new HashMap<>();
        final Map<Long, Integer> classInstanceSizes = new HashMap<>();
        final Map<Long, Long> classLoaderIds = new HashMap<>();
        final Map<Long, Long> classSuperIds = new HashMap<>();
        final Map<Long, Integer> classSerialByClassId = new HashMap<>();
        final Map<Long, List<long[]>> classObjFields = new HashMap<>(); // classId → [[nameId,offset]]
        final Map<Long, Byte> primArrayTypes = new HashMap<>();

        // GC roots (parallel arrays)
        private long[] gcRootAddrs;
        private byte[] gcRootTypes;
        private int gcRootCount;

        // threadSerial → packed list of local object addresses (for synthetic thread→local edges)
        final Map<Integer, List<Long>> threadLocalsBySerial = new HashMap<>();

        // Frames and traces
        final Map<Long, Long> frames = new HashMap<>();    // frameId → methodNameId
        final Map<Integer, long[]> traces = new HashMap<>(); // traceSerial → frameIds
        final Map<Integer, Long> threadSerialToObjId = new HashMap<>();

        A1State(int est, IdMap idMap) {
            this.idMap = idMap;
            addrBuf   = new long[est];
            shallowBuf = new int[est];
            classIdBuf = new long[est];
            count = 0;
            gcRootAddrs = new long[1024];
            gcRootTypes = new byte[1024];
            gcRootCount = 0;
        }

        void appendAddress(long addr) {
            if (count == addrBuf.length) {
                addrBuf   = Arrays.copyOf(addrBuf, count * 2);
                shallowBuf = Arrays.copyOf(shallowBuf, count * 2);
                classIdBuf = Arrays.copyOf(classIdBuf, count * 2);
            }
            addrBuf[count++] = addr;
            idMap.append(addr);
        }

        void appendShallowSize(long addr, int bytes) {
            // find the entry we just appended (it's always the last one)
            if (count > 0 && addrBuf[count-1] == addr) shallowBuf[count-1] = bytes;
        }

        void appendClassId(long addr, long classId) {
            if (count > 0 && addrBuf[count-1] == addr) classIdBuf[count-1] = classId;
        }

        void appendGCRoot(long addr, byte type) {
            if (gcRootCount == gcRootAddrs.length) {
                gcRootAddrs = Arrays.copyOf(gcRootAddrs, gcRootCount * 2);
                gcRootTypes = Arrays.copyOf(gcRootTypes, gcRootCount * 2);
            }
            gcRootAddrs[gcRootCount] = addr;
            gcRootTypes[gcRootCount] = type;
            gcRootCount++;
        }

        /** Flush shallow sizes to graph.shallowSizeDiv8 after idMap is sorted. */
        byte[] flushShallowSizes(int N) {
            byte[] div8 = new byte[N];
            // index 0 = virtual root, size 0
            // For now, return empty; shallowSizes must be set by address→index lookup
            // This is done properly in buildClassList + flushByAddress
            return div8;
        }

        short[] flushClassIndex(int N) {
            return new short[N]; // filled by buildClassList
        }

        void flushGCRoots(HeapGraph graph, IdMap idMap) {
            for (int i = 0; i < gcRootCount; i++) {
                int idx = objectIndex(idMap, gcRootAddrs[i]);
                if (idx >= 0) graph.addGCRoot(idx, gcRootTypes[i]);
            }
            graph.trimRoots();
        }

        void buildClassList(HeapGraph graph, IdMap idMap) {
            // Build ClassRecord for each class
            for (Map.Entry<Long, Integer> e : classSerialByClassId.entrySet()) {
                long classId = e.getKey();
                int serial = e.getValue();
                Long nameId = classIdToNameId.get(classId);
                String name = nameId != null ? graph.utf8Strings.getOrDefault(nameId, "?") : "?";
                long loaderId = classLoaderIds.getOrDefault(classId, 0L);
                long superId = classSuperIds.getOrDefault(classId, 0L);
                int instSize = classInstanceSizes.getOrDefault(classId, 0);
                List<long[]> objFields = classObjFields.getOrDefault(classId, List.of());

                short[] nameIds = new short[objFields.size()];
                int[] offsets = new int[objFields.size()];
                for (int i = 0; i < objFields.size(); i++) {
                    long fieldNameId = objFields.get(i)[0];
                    nameIds[i] = graph.internFieldName(fieldNameId);
                    offsets[i] = (int) objFields.get(i)[1];
                }

                int classIdx = graph.classList.size();
                graph.classList.add(new ClassRecord(classId, name, loaderId, superId,
                        instSize, serial, nameIds, offsets));
                graph.classIdToIndex.put(classId, classIdx);
                graph.classSerialToIndex.put(serial, classIdx);
            }

            // Fill shallowSizeDiv8 and classIndex for all objects
            for (int i = 0; i < count; i++) {
                int objIdx = objectIndex(idMap, addrBuf[i]);
                if (objIdx < 0) continue;
                // Shallow size
                int bytes = shallowBuf[i];
                if (bytes > 0) {
                    int div8 = bytes / 8;
                    if (div8 > 0 && div8 <= 255) {
                        graph.shallowSizeDiv8[objIdx] = (byte) div8;
                    } else if (bytes > 0) {
                        if (graph.overflowSizes == null) graph.overflowSizes = new HeapGraph.LongLongMap(64);
                        graph.overflowSizes.put(objIdx, bytes);
                    }
                }
                // Class index
                long cid = classIdBuf[i];
                Integer cidx = graph.classIdToIndex.get(cid);
                if (cidx != null && cidx <= Short.MAX_VALUE) {
                    graph.classIndex[objIdx] = (short)(int)cidx;
                }
            }
        }

        void buildTraceFrames(HeapGraph graph) {
            for (Map.Entry<Integer, long[]> e : traces.entrySet()) {
                long[] frameIds = e.getValue();
                long[] methodNameIds = new long[frameIds.length];
                for (int i = 0; i < frameIds.length; i++) {
                    methodNameIds[i] = frames.getOrDefault(frameIds[i], 0L);
                }
                graph.traceFrames.put(e.getKey(), methodNameIds);
            }
        }
    }

    // =========================================================
    // CsrBuilderEncoder: VByte encoding of inboundTargets
    // =========================================================

    private static final class CsrBuilderEncoder {
        private final HeapGraph graph;
        private int[] targets;
        private final int[] offsets;
        private final int n;

        CsrBuilderEncoder(HeapGraph graph, int[] targets, int[] offsets, int n) {
            this.graph = graph;
            this.targets = targets;
            this.offsets = offsets;
            this.n = n;
        }

        void encodeVByte() {
            // Sort each row by lower-31-bit src value; re-index excludedEdge via sign-bit trick
            BitSortHelper.sortAndEncode(targets, offsets, n, graph);
            targets = null; // release
        }
    }

    // =========================================================
    // BitSortHelper: sort + VByte encode with embedded exclude flags
    // =========================================================

    static final class BitSortHelper {
        static void sortAndEncode(int[] targets, int[] offsets, int n, HeapGraph graph) {
            int totalEdges = offsets[n];
            java.util.BitSet newExcluded = new java.util.BitSet(totalEdges);
            byte[] stream = new byte[Math.max(totalEdges * 2, 16)];
            int streamPos = 0;
            int logicalEdgeIdx = 0;

            for (int v = 0; v < n; v++) {
                int lo = offsets[v];
                int hi = offsets[v + 1];
                int len = hi - lo;

                if (len > 1) {
                    sortWithFlags(targets, lo, hi);
                }

                offsets[v] = streamPos;
                int prev = 0;
                for (int i = lo; i < hi; i++) {
                    int raw = targets[i];
                    boolean excl = (raw & Integer.MIN_VALUE) != 0;
                    int src = raw & Integer.MAX_VALUE;
                    int delta = src - prev;
                    prev = src;
                    if (streamPos + 8 > stream.length) {
                        stream = Arrays.copyOf(stream, stream.length * 2);
                    }
                    streamPos = VByte.encode(delta, stream, streamPos);
                    if (excl) newExcluded.set(logicalEdgeIdx);
                    logicalEdgeIdx++;
                }
            }

            offsets[n] = streamPos;
            graph.inboundStream = Arrays.copyOf(stream, streamPos);
            graph.excludedEdge = newExcluded;
        }

        private static void sortWithFlags(int[] arr, int lo, int hi) {
            int len = hi - lo;
            if (len <= 16) {
                // Insertion sort (preserves sign bit)
                for (int i = lo + 1; i < hi; i++) {
                    int key = arr[i];
                    int keyVal = key & Integer.MAX_VALUE;
                    int j = i - 1;
                    while (j >= lo && (arr[j] & Integer.MAX_VALUE) > keyVal) {
                        arr[j + 1] = arr[j];
                        j--;
                    }
                    arr[j + 1] = key;
                }
            } else {
                // Strip flags, sort stripped, re-apply flags by linear scan
                int[] stripped = new int[len];
                for (int i = 0; i < len; i++) stripped[i] = arr[lo + i] & Integer.MAX_VALUE;
                Arrays.sort(stripped);
                // Mark used entries in original
                boolean[] used = new boolean[len];
                for (int i = 0; i < len; i++) {
                    int src = stripped[i];
                    boolean excl = false;
                    for (int j = 0; j < len; j++) {
                        if (!used[j] && (arr[lo + j] & Integer.MAX_VALUE) == src) {
                            excl = (arr[lo + j] & Integer.MIN_VALUE) != 0;
                            used[j] = true;
                            break;
                        }
                    }
                    arr[lo + i] = excl ? (src | Integer.MIN_VALUE) : src;
                }
            }
        }
    }

    // =========================================================
    // Parser: HPROF binary reader using direct ByteBuffer
    // =========================================================

    static final class Parser implements AutoCloseable {
        private final FileChannel channel;  // null if stream-based
        private final InputStream stream;   // null if file-based
        private final ByteBuffer buf;
        private int idSize;
        private String hprofFormat;

        Parser(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            stream  = null;
            buf = ByteBuffer.allocateDirect(BUFFER_SIZE);
            buf.limit(0); // empty initially
            readHeader();
        }

        Parser(InputStream in) throws IOException {
            channel = null;
            stream  = in;
            buf = ByteBuffer.allocate(BUFFER_SIZE); // heap buffer for stream path
            buf.limit(0);
            readHeader();
        }

        private void readHeader() throws IOException {
            hprofFormat = readNullTerminatedString();
            idSize = (int) readU4();
            skipFully(8); // timestamp
        }

        String hprofFormat() { return hprofFormat; }
        int idSize() { return idSize; }

        private String readNullTerminatedString() throws IOException {
            StringBuilder sb = new StringBuilder();
            while (true) {
                int b = readU1();
                if (b == 0) break;
                sb.append((char) b);
            }
            return sb.toString();
        }

        int readTag() throws IOException {
            try { return readU1(); }
            catch (EOFException e) { return -1; }
        }

        int readU1() throws IOException {
            ensureBytes(1);
            return buf.get() & 0xFF;
        }

        int readU2() throws IOException {
            ensureBytes(2);
            return buf.getShort() & 0xFFFF;
        }

        long readU4() throws IOException {
            ensureBytes(4);
            return buf.getInt() & 0xFFFFFFFFL;
        }

        long readId() throws IOException {
            if (idSize == 4) return readU4();
            ensureBytes(8);
            return buf.getLong();
        }

        byte[] readBytes(int len) throws IOException {
            byte[] result = new byte[len];
            int offset = 0;
            while (offset < len) {
                int avail = buf.remaining();
                if (avail == 0) { refill(); avail = buf.remaining(); }
                int chunk = Math.min(avail, len - offset);
                buf.get(result, offset, chunk);
                offset += chunk;
            }
            return result;
        }

        void skipFully(long n) throws IOException {
            while (n > 0) {
                int avail = buf.remaining();
                if (avail >= n) { buf.position(buf.position() + (int) n); n = 0; }
                else { n -= avail; buf.position(buf.limit()); refill(); }
            }
        }

        private void ensureBytes(int needed) throws IOException {
            if (buf.remaining() < needed) {
                buf.compact();
                int read = fillBuffer();
                buf.flip();
                if (buf.remaining() < needed) {
                    if (read < 0) throw new EOFException("Unexpected end of HPROF stream");
                    throw new EOFException("Insufficient bytes (needed " + needed + ", have " + buf.remaining() + ")");
                }
            }
        }

        private void refill() throws IOException {
            buf.clear();
            int read = fillBuffer();
            buf.flip();
            if (read < 0 && buf.remaining() == 0) throw new EOFException("Unexpected end of HPROF stream");
        }

        private int fillBuffer() throws IOException {
            if (channel != null) {
                return channel.read(buf);
            }
            // InputStream path: drain into backing array
            byte[] arr = buf.array();
            int off = buf.position();
            int len = buf.remaining();
            int total = 0;
            while (total < len) {
                int n = stream.read(arr, off + total, len - total);
                if (n < 0) { if (total == 0) return -1; break; }
                total += n;
            }
            buf.position(buf.position() + total);
            return total;
        }

        @Override
        public void close() throws IOException {
            if (channel != null) channel.close();
            if (stream  != null) stream.close();
        }
    }
}
