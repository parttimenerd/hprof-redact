/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import me.bechberger.hprof.core.HprofType;
import me.bechberger.hprof.core.ModifiedUtf8;

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
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import static me.bechberger.hprof.core.HprofConstants.*;

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

    /** Allocate a chunked int[][] with exactly {@code size} logical elements. */
    static int[][] allocChunked(int size) {
        int numChunks = (size + HeapGraph.TARGETS_CHUNK_MASK) >>> HeapGraph.TARGETS_CHUNK_BITS;
        if (numChunks == 0) numChunks = 1;
        int[][] chunks = new int[numChunks][];
        for (int c = 0; c < numChunks - 1; c++) {
            chunks[c] = new int[HeapGraph.TARGETS_CHUNK_SIZE];
        }
        // Last chunk: only allocate what's needed.
        int lastSize = size & HeapGraph.TARGETS_CHUNK_MASK;
        chunks[numChunks - 1] = new int[lastSize == 0 ? HeapGraph.TARGETS_CHUNK_SIZE : lastSize];
        return chunks;
    }

    /** Round {@code n} up to a multiple of {@code align}. */
    private static int alignUp(int n, int align) {
        int r = n % align;
        return r == 0 ? n : n + align - r;
    }

    /**
     * MAT-parity per-class instance size — mirrors MAT's calculateInstanceSize + calculateSizeRecursive.
     * Caches results in {@code cache}; recurses through superclass chain via {@code classSuperIds}.
     */
    private static int computeMatInstanceSize(long classId,
                                              LongIntHashMap cache,
                                              LongLongHashMap classSuperIds,
                                              LongIntHashMap ownObjectFieldCount,
                                              LongIntHashMap ownPrimitiveFieldBytes,
                                              int pointerSize, int refSize, int objectAlign) {
        int cached = cache.getIfAbsent(classId, -1);
        if (cached >= 0) return cached;
        int recursive = computeMatSizeRecursive(classId,
                classSuperIds, ownObjectFieldCount, ownPrimitiveFieldBytes,
                pointerSize, refSize);
        int result = alignUp(recursive, objectAlign);
        cache.put(classId, result);
        return result;
    }

    /** MAT's calculateSizeRecursive: walk super chain, aligning at refSize each step. */
    private static int computeMatSizeRecursive(long classId,
                                                LongLongHashMap classSuperIds,
                                                LongIntHashMap ownObjectFieldCount,
                                                LongIntHashMap ownPrimitiveFieldBytes,
                                                int pointerSize, int refSize) {
        long superId = classSuperIds.getIfAbsent(classId, 0L);
        if (superId == 0L) {
            // Terminal case: pointerSize + refSize (object header)
            return pointerSize + refSize;
        }
        int ownObj = ownObjectFieldCount.getIfAbsent(classId, 0);
        int ownPrim = ownPrimitiveFieldBytes.getIfAbsent(classId, 0);
        int ownFieldsSize = ownObj * refSize + ownPrim;
        int superSize = computeMatSizeRecursive(superId,
                classSuperIds, ownObjectFieldCount, ownPrimitiveFieldBytes,
                pointerSize, refSize);
        return alignUp(ownFieldsSize + superSize, refSize);
    }

    private final Path path;
    private final long fileSize;
    private final boolean gzipped;
    private final boolean tarred;   // true for .hprof.tar.gz: gzip-wrap around a POSIX tar archive
    private boolean keepAddressIndex = false; // when true: keep idMap sorted arrays for HTML address display
    private byte[] instanceDataBuf = new byte[256];
    private byte[] stringReadBuf   = new byte[256]; // reused for UTF-8 string records

    public HeapGraphBuilder(Path path) throws IOException {
        this.path = path;
        this.fileSize = Files.size(path);
        String name = path.getFileName().toString();
        this.tarred  = name.endsWith(".tar.gz") || name.endsWith(".tgz");
        this.gzipped = tarred || detectGzip(path);
    }

    /** When set, idMap sorted arrays are kept past A2 for HTML address display (default: free after A2). */
    public HeapGraphBuilder keepAddressIndex(boolean keep) {
        this.keepAddressIndex = keep;
        return this;
    }

    private static boolean detectGzip(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            int b1 = in.read();
            int b2 = in.read();
            return b1 == 0x1f && b2 == 0x8b;
        }
    }

    /**
     * Skip POSIX tar headers in {@code gz} until an entry whose name ends with {@code .hprof}
     * is found. Returns the entry size in bytes so the caller can wrap the stream with a
     * size limit, preventing HPROF-padding bytes at the end from being mis-parsed as records.
     *
     * Tar format: each entry = 512-byte header block + data padded to 512-byte boundary.
     * Header layout (POSIX/ustar): name[0..99], size at [124..135] (NUL-terminated octal ASCII),
     * typeflag at [156] ('0' or '\0' = regular file).
     * GNU extension: size with high-bit set uses big-endian base-256 encoding.
     */
    private static long skipToHprofEntry(InputStream gz) throws IOException {
        byte[] hdr = new byte[512];
        while (true) {
            // Read one 512-byte header block
            int read = 0;
            while (read < 512) {
                int n = gz.read(hdr, read, 512 - read);
                if (n < 0) throw new IOException("No .hprof entry found in tar archive");
                read += n;
            }
            // Two consecutive all-zero blocks = end-of-archive sentinel
            boolean allZero = true;
            for (byte b : hdr) { if (b != 0) { allZero = false; break; } }
            if (allZero) throw new IOException("No .hprof entry found in tar archive (hit end-of-archive)");

            // Parse entry name (NUL-terminated at offset 0, length 100)
            int nameLen = 0;
            while (nameLen < 100 && hdr[nameLen] != 0) nameLen++;
            String entryName = new String(hdr, 0, nameLen, java.nio.charset.StandardCharsets.US_ASCII);

            // Parse size at offset 124, 12 bytes.
            // GNU tar extension: if first byte has high bit set (0x80), the remaining 11 bytes
            // are big-endian binary (base-256). Otherwise NUL/space-terminated octal ASCII.
            long entrySize;
            if ((hdr[124] & 0x80) != 0) {
                // Base-256 encoding: 12 bytes, first byte encodes sign (0x80 = positive)
                entrySize = 0L;
                for (int i = 125; i < 136; i++) entrySize = (entrySize << 8) | (hdr[i] & 0xFF);
            } else {
                int sizeLen = 0;
                for (int i = 124; i < 136 && hdr[i] != 0 && hdr[i] != ' '; i++) sizeLen++;
                String sizeStr = new String(hdr, 124, sizeLen, java.nio.charset.StandardCharsets.US_ASCII).trim();
                entrySize = sizeStr.isEmpty() ? 0L : Long.parseLong(sizeStr, 8);
            }

            // Typeflag at offset 156: '0' or '\0' = regular file
            char typeFlag = (char) hdr[156];
            boolean isRegular = typeFlag == '0' || typeFlag == '\0';

            if (isRegular && entryName.endsWith(".hprof")) {
                // Stream is now positioned at first byte of entry data; return size for limit wrapping
                return entrySize;
            }

            // Skip this entry's data (padded to 512-byte boundary)
            long toSkip = entrySize;
            long remainder = entrySize % 512;
            if (remainder != 0) toSkip += (512 - remainder);
            while (toSkip > 0) {
                long skipped = gz.skip(toSkip);
                if (skipped <= 0) {
                    // skip() may return 0 on some streams; fall back to read
                    int b = gz.read();
                    if (b < 0) throw new IOException("Unexpected EOF while skipping tar entry");
                    toSkip--;
                } else {
                    toSkip -= skipped;
                }
            }
        }
    }

    /** Open a fresh Parser for one pass. Handles plain .hprof, .hprof.gz, and .hprof.tar.gz. */
    private Parser openParser() throws IOException {
        if (gzipped) {
            InputStream raw = new BufferedInputStream(Files.newInputStream(path), BUFFER_SIZE);
            InputStream gz  = new GZIPInputStream(raw, BUFFER_SIZE);
            if (tarred) {
                long entrySize = skipToHprofEntry(gz);
                // Wrap with a size-limited stream so tar padding bytes aren't mis-parsed as HPROF records
                return new Parser(new java.io.FilterInputStream(gz) {
                    long remaining = entrySize;
                    @Override public int read() throws IOException {
                        if (remaining <= 0) return -1;
                        int b = super.read(); if (b >= 0) remaining--;
                        return b;
                    }
                    @Override public int read(byte[] b, int off, int len) throws IOException {
                        if (remaining <= 0) return -1;
                        int n = super.read(b, off, (int) Math.min(len, remaining));
                        if (n > 0) remaining -= n;
                        return n;
                    }
                });
            }
            return new Parser(gz);
        }
        return new Parser(path);
    }

    /** Run all phases and return the fully-populated HeapGraph. */
    public HeapGraph build() throws IOException {
        return buildInternal();
    }

    /**
     * Run all phases without freeing IdMap sorted arrays or post-DOM structures.
     * For unit tests only — preserves inboundStream/inboundOffsets/gcRootIds for inspection.
     */
    HeapGraph buildForTesting() throws IOException {
        keepAddressIndex = true; // tests access idMap.indexOf after build
        // retainInboundCsrForTesting is set on the graph before DOM runs, so DominatorTree
        // skips the early free of inboundOffsets/inboundStream.
        return buildInternal(false, true);
    }

    private HeapGraph buildInternal() throws IOException {
        return buildInternal(true, false);
    }

    private HeapGraph buildInternal(boolean freePostDom) throws IOException {
        return buildInternal(freePostDom, false);
    }

    private HeapGraph buildInternal(boolean freePostDom, boolean retainInboundCsr) throws IOException {
        IdMap idMap = new IdMap();
        long t0 = System.currentTimeMillis();
        HeapGraph graph = phaseA1(idMap);
        graph.retainInboundCsrForTesting = retainInboundCsr;
        System.gc();
        long t1 = System.currentTimeMillis();
        Log.verbose("  [RSS] after A1+GC: %,d KB", Log.rssKb());
        phaseA2(graph);
        System.gc();
        long t2 = System.currentTimeMillis();
        Log.verbose("  [RSS] after A2+GC: %,d KB", Log.rssKb());
        RpoDfs.compute(graph);
        System.gc();
        long t3 = System.currentTimeMillis();
        Log.verbose("  [RSS] after RPO+GC: %,d KB", Log.rssKb());
        DominatorTree.compute(graph);
        if (freePostDom) {
            // Free inbound CSR — only consumed by DominatorTree; freed inside DOM after Phase 1.
            // Null the fields here in case they weren't cleared inside (defensive).
            graph.inboundStream  = null;
            graph.inboundOffsets = null;
            // GC root arrays only needed through DOM (DominatorTree uses gcRootIds/isGCRoot for vrAdjacent)
            graph.gcRootIds   = null;
            graph.gcRootTypes = null;
            graph.isGCRoot    = null;
        }
        System.gc();
        long t4 = System.currentTimeMillis();
        Log.verbose("  [RSS] after DOM+GC: %,d KB", Log.rssKb());
        graph.computeUnreachableStats();
        buildClassObjClassIdx(graph);
        RetainedSizes.compute(graph);
        long t5 = System.currentTimeMillis();
        Log.verbose("  [RSS] after retained: %,d KB", Log.rssKb());
        graph.heapTotalBytes = graph.totalHeapBytes();
        Log.info("  phases: A1=%.1fs  A2=%.1fs  RPO=%.1fs  DOM=%.1fs  retained=%.1fs",
                (t1-t0)/1e3, (t2-t1)/1e3, (t3-t2)/1e3, (t4-t3)/1e3, (t5-t4)/1e3);
        return graph;
    }

    // =========================================================
    // Phase A.1: collect addresses + metadata
    // =========================================================

    private HeapGraph phaseA1(IdMap idMap) throws IOException {
        // Estimate N and E from file size (~48 bytes/object, ~2 edges/object)
        int nEstimated = Math.max(64, (int) Math.min(fileSize / 48, Integer.MAX_VALUE / 2L));

        try (Parser p = openParser()) {
            HeapGraph graph = new HeapGraph(path, p.idSize(), fileSize, p.hprofFormat(), idMap);

            // Per-object metadata arrays — allocate at estimated size, grow if needed
            // We use a temporary builder object to accumulate before we know final N.
            A1State state = new A1State(nEstimated, idMap);

            // Map from classId (long) → class serial number (for classIndex lookup later)
            LongIntHashMap classIdToSerial = new LongIntHashMap();
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
                        if (strLen > stringReadBuf.length) stringReadBuf = new byte[strLen];
                        p.readBytesInto(stringReadBuf, strLen);
                        try {
                            graph.utf8Strings.put(nameId, ModifiedUtf8.decode(stringReadBuf, strLen));
                        } catch (IllegalArgumentException ex) {
                            graph.utf8Strings.put(nameId, new String(stringReadBuf, 0, strLen, java.nio.charset.StandardCharsets.ISO_8859_1));
                        }
                    }
                    case HPROF_LOAD_CLASS -> {
                        int serial = (int) p.readU4();
                        long classId = p.readId();
                        p.readU4(); // stack trace serial
                        long nameId = p.readId();
                        state.classSerialToId.put(serial, classId);
                        graph.utf8Strings.getIfAbsentPut(nameId, "?");
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
            graph.phaseArrays = new HeapGraph.PhaseArrays(N);

            // Propagate compressed-OOPS detection to the graph so MAT-parity size formulas
            // downstream (shallow sizes, class-object sizes) use refSize=4 instead of 8.
            if (state.foundCompressed) {
                graph.refSize = 4;
            }

            // --- Allocate per-object arrays ---
            graph.shallowSizeDiv8 = state.flushShallowSizes(N);
            graph.classIndex = state.flushClassIndex(N);

            // Resolve GC roots to indices
            state.flushGCRoots(graph, idMap);
            // Resolve class dump indices (implicit class roots for reachability)
            state.flushClassDumpIndices(graph, idMap);

            // Build class list from gathered metadata
            state.buildClassList(graph, idMap);

            // Resolve inherited object-field offsets: for each class, concatenate its
            // own object fields with those of its superclasses at the correct offsets.
            // This is essential because HPROF INSTANCE_DUMP data contains subclass fields
            // first, then super's fields, etc. Missing this = huge unreachable count.
            resolveInheritedFieldOffsets(graph);

            // MAT parity: SYSTEM_CLASS root fallback. If no STICKY_CLASS roots were emitted,
            // treat all non-array boot-loader classes as roots (classLoader == 0).
            addSystemClassRootsIfMissing(graph, idMap);

            // Link thread serial → object index; trace frames
            state.threadSerialToObjId.forEachKeyValue((threadSerial, objId) -> {
                int idx = idMap.indexOf(objId);
                if (idx >= 0) graph.threadSerialToObjectId.put(threadSerial, objId);
            });
            state.buildTraceFrames(graph);

            // Build synthetic thread→local edges from frame/stack roots
            graph.syntheticThreadEdges = buildSyntheticEdges(state, graph, idMap);

            // Resolve exclude pairs
            resolveExcludePairs(graph);

            // Free large A1State maps no longer needed after build
            graph.utf8Strings.clear();

            return graph;
        }
    }

    /**
     * MAT parity: expand each class's object-field offsets to include inherited fields.
     * HPROF instance data layout is subclass-first, then super's fields, etc. Without
     * walking the hierarchy, we miss all inherited OBJECT-typed fields and their target
     * objects become unreachable. Replaces each ClassRecord in graph.classList with a
     * new record whose {@code objectFieldOffsets} covers the full hierarchy.
     */
    private static void resolveInheritedFieldOffsets(HeapGraph graph) {
        int n = graph.classList.size();
        // Hoist scratch lists outside the per-class loop to avoid 2×n ArrayList allocations.
        java.util.List<short[]> nameChunks   = new java.util.ArrayList<>(8);
        java.util.List<int[]>   offsetChunks = new java.util.ArrayList<>(8);
        int[] adjScratch = new int[64]; // grow-if-needed for adjusted field offsets per ancestor
        for (int ci = 0; ci < n; ci++) {
            ClassRecord cr = graph.classList.get(ci);
            if (cr.classId() == 0L) continue; // synthesized array class, no instance fields
            // Walk the class hierarchy: subclass fields first, then super, super-super, ...
            nameChunks.clear();
            offsetChunks.clear();
            int baseOffset = 0;
            ClassRecord cur = cr;
            int guard = 0;
            while (cur != null && guard++ < 256) {
                short[] names = cur.objectFieldNameIds();
                int[]   offs  = cur.objectFieldOffsets();
                if (names.length > 0) {
                    if (offs.length > adjScratch.length) adjScratch = new int[offs.length * 2];
                    for (int k = 0; k < offs.length; k++) adjScratch[k] = offs[k] + baseOffset;
                    nameChunks.add(names);
                    offsetChunks.add(java.util.Arrays.copyOf(adjScratch, offs.length));
                }
                baseOffset += cur.ownFieldsSize();
                if (cur.superClassId() == 0L) break;
                int superIdx = graph.classIdToIndex.getIfAbsent(cur.superClassId(), -1);
                if (superIdx < 0) break;
                cur = graph.classList.get(superIdx);
            }
            int total = 0;
            for (short[] a : nameChunks) total += a.length;
            if (total == cr.objectFieldNameIds().length) continue; // no inheritance to add
            short[] fullNames = new short[total];
            int[]   fullOffs  = new int[total];
            int pos = 0;
            for (int k = 0; k < nameChunks.size(); k++) {
                short[] n2 = nameChunks.get(k);
                int[]   o2 = offsetChunks.get(k);
                System.arraycopy(n2, 0, fullNames, pos, n2.length);
                System.arraycopy(o2, 0, fullOffs,  pos, o2.length);
                pos += n2.length;
            }
            graph.classList.set(ci, new ClassRecord.Full(cr.classId(), cr.name(), cr.classLoaderId(),
                    cr.superClassId(), cr.instanceSize(), cr.classSerialNumber(),
                    fullNames, fullOffs, cr.ownFieldsSize()));
        }
    }

    /**
     * MAT parity: mark all non-array class objects from CLASS_DUMP records as STICKY_CLASS roots.
     * MAT treats all loaded classes (those appearing in CLASS_DUMP records) as always reachable,
     * regardless of whether explicit STICKY_CLASS root records exist. Without this, user-loaded
     * classes with no explicit GC root record are unreachable, making their static fields (and
     * transitively referenced objects) also unreachable.
     */
    /** Builds graph.classObjClassIdx: for each class-object node, records the classList index
     *  of the class it represents. Used by RetainedSizes to handle MAT-style group-retained. */
    private static void buildClassObjClassIdx(HeapGraph graph) {
        int N = graph.N;
        int[] result = new int[N];
        java.util.Arrays.fill(result, -1);
        int classCount = graph.classList.size();
        for (int ci = 0; ci < classCount; ci++) {
            int nodeIdx = graph.classNodeIdx != null ? graph.classNodeIdx[ci] : -1;
            if (nodeIdx <= 0 || nodeIdx >= N) continue;
            result[nodeIdx] = ci;
        }
        graph.classObjClassIdx = result;
    }

    private static void addSystemClassRootsIfMissing(HeapGraph graph, IdMap idMap) {
        int added = 0;
        for (ClassRecord cr : graph.classList) {
            if (cr.classId() == 0L) continue;              // synthesized array class
            if (cr.classLoaderId() != 0L) continue;        // non-boot classloader: skip (MAT parity)
            String name = cr.name();
            if (name.length() > 0 && name.charAt(0) == '[') continue; // array class
            int idx = idMap.indexOf(cr.classId());
            if (idx < 0) continue;
            int adjusted = idx + 1;
            if (graph.isGCRoot.get(adjusted)) continue;    // already a root
            graph.addGCRoot(adjusted, (byte) HPROF_GC_ROOT_STICKY_CLASS);
            added++;
        }
        if (added > 0) {
            graph.syntheticRootCount += added;
            graph.trimRoots();
        }
    }

    private static void appendThreadLocal(IntObjectHashMap<long[]> map, int threadSerial, long localId) {
        long[] existing = map.get(threadSerial);
        if (existing == null) {
            map.put(threadSerial, new long[]{localId});
        } else {
            long[] grown = Arrays.copyOf(existing, existing.length + 1);
            grown[existing.length] = localId;
            map.put(threadSerial, grown);
        }
    }

    private static Map<Integer, int[]> buildSyntheticEdges(A1State state, HeapGraph graph, IdMap idMap) {
        Map<Integer, int[]> result = new HashMap<>();
        state.threadLocalsBySerial.forEachKeyValue((threadSerial, localIds) -> {
            long threadObjId = graph.threadSerialToObjectId.getIfAbsent(threadSerial, 0L);
            if (threadObjId == 0L) return;
            int threadIdx = idMap.indexOf(threadObjId);
            if (threadIdx < 0) return;
            int threadIdxAdjusted = threadIdx + 1;
            int[] localIdxArr = new int[localIds.length];
            int count = 0;
            for (long localId : localIds) {
                int localIdx = idMap.indexOf(localId);
                if (localIdx >= 0) { localIdxArr[count++] = localIdx + 1; }
            }
            if (count > 0) { result.put(threadIdxAdjusted, java.util.Arrays.copyOf(localIdxArr, count)); }
        });
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
                    state.appendAddressToIdMapOnly(id);
                    state.appendGCRoot(id, (byte) subTag);
                }
                case HPROF_GC_ROOT_JNI_GLOBAL -> {
                    long id = p.readId(); p.skipFully(ids); remaining -= ids * 2;
                    state.appendAddressToIdMapOnly(id);
                    state.appendGCRoot(id, (byte) subTag);
                }
                case HPROF_GC_ROOT_THREAD_OBJ -> {
                    long id = p.readId();
                    int threadSerial = (int) p.readU4();
                    p.skipFully(4); // stackSerial
                    remaining -= ids + 8;
                    state.appendAddressToIdMapOnly(id);
                    state.appendGCRoot(id, (byte) subTag);  // Thread object IS a GC root
                    // Also populate threadSerial → objectId mapping (same as HPROF_START_THREAD)
                    state.threadSerialToObjId.put(threadSerial, id);
                }
                case HPROF_GC_ROOT_JNI_LOCAL, HPROF_GC_ROOT_JAVA_FRAME -> {
                    long localId = p.readId();
                    int threadSerial = (int) p.readU4();
                    p.skipFully(4); // frameNumber
                    remaining -= ids + 8;
                    state.appendAddressToIdMapOnly(localId);  // still needs to be in IdMap
                    // NOT a GC root — will be synthetic edge from thread to local
                    appendThreadLocal(state.threadLocalsBySerial, threadSerial, localId);
                }
                case HPROF_GC_ROOT_NATIVE_STACK, HPROF_GC_ROOT_THREAD_BLOCK -> {
                    long localId = p.readId();
                    int threadSerial = (int) p.readU4();
                    remaining -= ids + 4;
                    state.appendAddressToIdMapOnly(localId);
                    // NOT a direct GC root — added as synthetic edge from thread object (MAT parity)
                    appendThreadLocal(state.threadLocalsBySerial, threadSerial, localId);
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
                    // shallowSize is the instance data length + object header (12 or 16 bytes).
                    // We defer computation to flush time (buildClassList) where we know refSize
                    // (may be 4 with compressed OOPS). MAT's formula:
                    //   alignUp(fieldBytes(all inherited) + pointerSize + refSize, objectAlign)
                    // where fieldBytes = HPROF instsize (from CLASS_DUMP). Store 0 as sentinel.
                    int shallowBytes = 0;
                    state.appendShallowSize(objId, shallowBytes);
                    state.appendClassId(objId, classId);
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    long elemClassId = p.readId(); p.skipFully((long) numElem * ids);
                    remaining -= (int) (ids + 4 + 4 + ids + (long) numElem * ids);

                    // Compressed-OOPS detection (mirrors MAT's Pass1Parser.readObjectArrayDump).
                    // If the next array's address falls inside the "uncompressed end" region of the
                    // previous array, the previous array actually took less space than 8-byte refs
                    // would require → references are compressed to 4 bytes.
                    if (!state.foundCompressed && ids == 8
                            && objId > state.previousArrayStart
                            && objId < state.previousArrayUncompressedEnd) {
                        state.foundCompressed = true;
                    }
                    state.previousArrayStart = objId;
                    state.previousArrayUncompressedEnd = objId + 16 + (long) numElem * 8;

                    state.appendAddress(objId);
                    // Store numElem in shallowBuf slot for arrays; final byte size computed in flush
                    // once compressed-OOPS is known.
                    state.appendShallowSize(objId, numElem);
                    state.appendClassId(objId, elemClassId); // element class (used to synthesize array class)
                    state.appendArrayType(objId, (byte) -1); // mark as object array
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
                    // Store numElem; final byte size computed in flush.
                    state.appendShallowSize(objId, numElem);
                    state.appendArrayType(objId, (byte) elemType);
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
        state.classDumpIds.add(classId);   // track for implicit class roots
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

        // Static fields (skip values, but count size components for class-object shallow calc).
        // MAT's calculateClassSize: alignUp(sum of static field sizes, objectAlign),
        // where OBJECT fields count as refSize bytes (4 with compressed OOPS).
        int sfCount = p.readU2(); consumed += 2;
        int staticObjectFieldCount = 0;
        int staticPrimitiveFieldBytes = 0;
        for (int i = 0; i < sfCount; i++) {
            p.skipFully(ids); consumed += ids; // name id
            int type = p.readU1(); consumed += 1;
            int valSize = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT) {
                staticObjectFieldCount++;
            } else {
                staticPrimitiveFieldBytes += valSize;
            }
            p.skipFully(valSize); consumed += valSize;
        }

        // Instance fields — collect OBJECT-type fields, and total own-fields size
        int ifCount = p.readU2(); consumed += 2;
        long[] objFieldsBuf = null; // flat [nameId0,off0, nameId1,off1, ...]; sized at end
        int objFieldCount = 0;
        int offset = 0;
        int ownObjectFieldCount = 0;
        int ownPrimitiveFieldBytes = 0;
        for (int i = 0; i < ifCount; i++) {
            long nameId = p.readId(); consumed += ids;
            int type = p.readU1(); consumed += 1;
            int ts = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT) {
                if (objFieldsBuf == null) objFieldsBuf = new long[Math.max(ifCount, 4) * 2];
                else if (objFieldCount * 2 + 2 > objFieldsBuf.length) objFieldsBuf = Arrays.copyOf(objFieldsBuf, objFieldsBuf.length * 2);
                objFieldsBuf[objFieldCount * 2]     = nameId;
                objFieldsBuf[objFieldCount * 2 + 1] = offset;
                objFieldCount++;
                ownObjectFieldCount++;
            } else {
                ownPrimitiveFieldBytes += ts;
            }
            offset += ts;
        }
        if (objFieldCount > 0) {
            state.classObjFields.put(classId, Arrays.copyOf(objFieldsBuf, objFieldCount * 2));
        }
        state.classOwnFieldsSizes.put(classId, offset);
        state.classOwnObjectFieldCount.put(classId, ownObjectFieldCount);
        state.classOwnPrimitiveFieldBytes.put(classId, ownPrimitiveFieldBytes);
        state.classStaticObjectFieldCount.put(classId, staticObjectFieldCount);
        state.classStaticPrimitiveFieldBytes.put(classId, staticPrimitiveFieldBytes);
        // Placeholder — real class-object shallow size computed at flush (needs refSize)
        state.appendShallowSize(classId, 0);
        return consumed;
    }

    // =========================================================
    // Phase A.2: edge resolution + forward CSR + inDegree
    // =========================================================

    private void phaseA2(HeapGraph graph) throws IOException {
        int N = graph.N;
        int ids = graph.idSize;
        IdMap idMap = graph.idMap;

        int[] outDegree = new int[N];
        int[] inDegree  = new int[N];

        // --- Sub-pass A.2a: count exact out-degrees AND in-degrees simultaneously ---
        // Reads actual ref values from instance data so that both outDegree[] and inDegree[]
        // are exact (no upper-bound slack). This requires IdMap.indexOf for each ref but
        // eliminates the in-memory loop that previously scanned fwdTargets to build inDegree.
        final int[] outDegreeF = outDegree;
        final int[] inDegreeF  = inDegree;
        try (Parser p = openParser()) {
            countOutDegrees(p, graph, outDegreeF, inDegreeF);
        }
        // Count synthetic thread→local edges (out-degree for thread, in-degree for locals)
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                int threadIdx = e.getKey();
                if (threadIdx < N) {
                    for (int localIdx : e.getValue()) {
                        if (localIdx >= 0 && localIdx < N) {
                            outDegreeF[threadIdx]++;
                            inDegreeF[localIdx]++;
                        }
                    }
                }
            }
        }

        // Prefix-sum outDegree in-place → becomes fwdOffsets[0..N-1].
        // Use outDegree directly as fwdOffsets (int[N]), saving the 2 GB Arrays.copyOf(N+1)
        // that was previously needed. totalFwdSlots is stored in graph.totalFwdEdges
        // as the N-th sentinel, which RpoDfs reads instead of fwdOffsets[N].
        int totalFwdSlots = 0;
        for (int i = 0; i < N; i++) {
            int deg = outDegree[i];
            outDegree[i] = totalFwdSlots;
            totalFwdSlots += deg;
        }
        int[] fwdOffsets = outDegree; // int[N]; no copy; sentinel is stored in graph.totalFwdEdges
        outDegree = null;
        // NOTE: fwdOffsets (= outDegree) is NOT donated to phaseArrays here.
        // Previously: donate(outDegree) → ibCursor = take() = outDegree; copy inboundOffsets into ibCursor.
        // Now: ibCursor = inDegree (donated below), which already holds inboundOffsets values.

        // Prefix-sum inDegree in-place → becomes inboundOffsets[0..N-1], then extend to N+1.
        int totalInbEdges = 0;
        for (int i = 0; i < N; i++) {
            int deg = inDegree[i];
            inDegree[i] = totalInbEdges;
            totalInbEdges += deg;
        }
        int[] inboundOffsets = java.util.Arrays.copyOf(inDegree, N + 1);
        inboundOffsets[N] = totalInbEdges;
        graph.phaseArrays.donate(inDegree); // donate before losing reference; take() will zero it
        inDegree = null;

        // --- Sub-pass A.2b: fill inboundTargets only, then VByte-encode and free it ---
        // Chunked int[][] avoids a single contiguous allocation that can OOM for large heaps.
        // Each chunk = TARGETS_CHUNK_SIZE ints (256 MB); up to ~25 chunks for 1.67B edges.
        int[][] inboundTargets = allocChunked(totalInbEdges);
        // ibCursor: mutable copy of inboundOffsets[0..N-1] used as write cursors during fill.
        // Take from phaseArrays — gets inDegree storage, which already holds inboundOffsets[0..N-1]
        // (inDegree was prefix-summed in-place to produce inboundOffsets values). No arraycopy needed.
        int[] ibCursor = graph.phaseArrays.takeRaw(); // inDegree storage; values == inboundOffsets[0..N-1]
        // No arraycopy: ibCursor[0..N-1] == inboundOffsets[0..N-1] (inDegree was prefix-summed in-place)

        int[][] excludePairs = graph.excludePairs;
        final int[][] ibT = inboundTargets;
        final int[] ibC = ibCursor;
        try (Parser p = openParser()) {
            scanEdgesWithNames(p, graph, (srcIdx, dstIdx, nameIdx, srcClassIdx) -> {
                boolean excluded = isExcluded(excludePairs, srcClassIdx, nameIdx);
                int pos = ibC[dstIdx]++;
                ibT[pos >>> HeapGraph.TARGETS_CHUNK_BITS][pos & HeapGraph.TARGETS_CHUNK_MASK]
                    = excluded ? (srcIdx | Integer.MIN_VALUE) : srcIdx;
            });
        }
        // Fill synthetic thread→local inbound edges
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                int threadIdx = e.getKey();
                if (threadIdx < N) {
                    for (int localIdx : e.getValue()) {
                        if (localIdx >= 0 && localIdx < N) {
                            int pos = ibC[localIdx]++;
                            ibT[pos >>> HeapGraph.TARGETS_CHUNK_BITS][pos & HeapGraph.TARGETS_CHUNK_MASK]
                                = threadIdx; // not excluded
                        }
                    }
                }
            }
        }
        graph.phaseArrays.donate(ibCursor);
        ibCursor = null;
        Log.debug("  [RSS] A2b after inb fill: %,d KB", Log.rssKb());

        // VByte-encode inbound CSR; inboundTargets freed inside encoder.
        // Encoder sets graph.inboundOffsets (long[]) and graph.inboundStream (byte[][]) directly.
        CsrBuilderEncoder encoder = new CsrBuilderEncoder(graph, inboundTargets, inboundOffsets, N);
        encoder.encodeVByte();
        // inboundTargets freed inside encoder. Do NOT donate inboundOffsets to phaseArrays yet —
        // delaying until after fwdTargets alloc keeps 2 GB out of the A2c peak (the overall max).
        Log.debug("  [RSS] A2b after inb encode+free: %,d KB", Log.rssKb());

        // --- Sub-pass A.2c: fill fwdTargets only (inboundTargets already freed) ---
        // Take ibCursor from phaseArrays BEFORE allocating fwdTargets so phaseArrays is empty
        // during the alloc — saves another 2 GB at A2c peak (big17).
        int[] fwdCursor = graph.phaseArrays.takeRaw(); // gets ibCursor (int[N]) from phaseArrays slot0
        int[][] fwdTargets = allocChunked(totalFwdSlots);
        // Now it's safe to donate stale inboundOffsets — fwdTargets peak already passed.
        // RpoDfs will take it via phaseArrays for dfsParent reuse (big14).
        graph.phaseArrays.donate(inboundOffsets);
        inboundOffsets = null;
        Log.debug("  [RSS] A2c after fwdTargets alloc: %,d KB", Log.rssKb());
        System.arraycopy(fwdOffsets, 0, fwdCursor, 0, N);

        final int[][] fwdT = fwdTargets;
        final int[] fwdC = fwdCursor;
        try (Parser p = openParser()) {
            scanEdgesWithNames(p, graph, (srcIdx, dstIdx, nameIdx, srcClassIdx) -> {
                int pos = fwdC[srcIdx]++;
                fwdT[pos >>> HeapGraph.TARGETS_CHUNK_BITS][pos & HeapGraph.TARGETS_CHUNK_MASK] = dstIdx;
            });
        }
        // Fill synthetic thread→local forward edges
        if (graph.syntheticThreadEdges != null) {
            for (Map.Entry<Integer, int[]> e : graph.syntheticThreadEdges.entrySet()) {
                int threadIdx = e.getKey();
                if (threadIdx < N) {
                    for (int localIdx : e.getValue()) {
                        if (localIdx >= 0 && localIdx < N) {
                            int pos = fwdC[threadIdx]++;
                            fwdT[pos >>> HeapGraph.TARGETS_CHUNK_BITS][pos & HeapGraph.TARGETS_CHUNK_MASK] = localIdx;
                        }
                    }
                }
            }
            graph.syntheticThreadEdges = null;
        }
        graph.phaseArrays.donate(fwdCursor);
        fwdCursor = null;

        // Store compact forward CSR — fwdOffsets[i] is exact start, totalFwdEdges is the N-sentinel
        graph.fwdOffsets    = fwdOffsets;
        graph.totalFwdEdges = totalFwdSlots;
        graph.fwdEnds    = null;
        graph.fwdTargets = fwdTargets;

        // Free per-class field layout arrays — only needed during A2 edge scanning
        for (ClassRecord cr : graph.classList) {
            if (cr instanceof ClassRecord.Full f) {
                f.freeFieldArrays();
            }
        }
        graph.excludedEdge = null; // built during VByte encoding, never read after A2 completes

        // Precompute class-object node indices so idMap.bucket can be freed.
        int[] classNodeIdx = new int[graph.classList.size()];
        java.util.Arrays.fill(classNodeIdx, -1);
        for (int ci = 0; ci < graph.classList.size(); ci++) {
            long classId = graph.classList.get(ci).classId();
            if (classId == 0L) continue;
            int rawIdx = graph.idMap.indexOf(classId);
            if (rawIdx >= 0) classNodeIdx[ci] = rawIdx + 1; // +1 for virtual root offset
        }
        graph.classNodeIdx = classNodeIdx;
        if (keepAddressIndex) {
            graph.idMap.freeBucket(); // HTML mode: keep intBuf/buf for address display
        } else {
            graph.idMap.freeSortedArrays(); // Markdown mode: free ~2 GB now; no address lookups needed
        }
    }

    /** Exact out-degree and in-degree count: reads actual ref values from instance data. */
    private void countOutDegrees(Parser p, HeapGraph graph, int[] outDegree, int[] inDegree) throws IOException {
        int ids = graph.idSize;
        IdMap idMap = graph.idMap;
        while (true) {
            int tag = p.readTag();
            if (tag < 0) break;
            p.readU4();
            long length = p.readU4();
            if (tag == HPROF_HEAP_DUMP || tag == HPROF_HEAP_DUMP_SEGMENT) {
                countOutDegreesInSegment(p, (int) length, ids, idMap, graph, outDegree, inDegree);
            } else {
                p.skipFully(length);
            }
        }
    }

    private void countOutDegreesInSegment(Parser p, int segLen, int ids, IdMap idMap,
                                           HeapGraph graph, int[] outDegree, int[] inDegree) throws IOException {
        int remaining = segLen;
        int N = graph.N;
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
                case HPROF_GC_CLASS_DUMP -> // Class dumps: edges counted exactly (class records are small)
                        remaining -= countClassDumpOutDegrees(p, ids, idMap, graph, outDegree, inDegree);
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    int dataLen = (int) p.readU4();
                    remaining -= ids + 4 + ids + 4;
                    int srcIdx = objectIndex(idMap, objId);
                    int classIdx = graph.classIdToIndex.getIfAbsent(classId, -1);
                    ClassRecord cr = classIdx >= 0 ? graph.classList.get(classIdx) : null;
                    if (dataLen > instanceDataBuf.length) instanceDataBuf = new byte[dataLen];
                    p.readBytesInto(instanceDataBuf, dataLen); remaining -= dataLen;
                    if (srcIdx >= 0 && srcIdx < N) {
                        // class-object edge (always emitted)
                        int classObjIdx = objectIndex(idMap, classId);
                        if (classObjIdx >= 0 && classObjIdx < N) {
                            outDegree[srcIdx]++;
                            inDegree[classObjIdx]++;
                        }
                        // object field edges — read actual refs to get exact counts
                        if (cr != null) {
                            byte[] data = instanceDataBuf;
                            for (int off : cr.objectFieldOffsets()) {
                                if (off + ids > data.length) continue;
                                long refId = readIdFromBytes(data, off, ids);
                                if (refId != 0) {
                                    int dstIdx = objectIndex(idMap, refId);
                                    if (dstIdx >= 0 && dstIdx < N) {
                                        outDegree[srcIdx]++;
                                        inDegree[dstIdx]++;
                                    }
                                }
                            }
                        }
                    }
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    long elemClassId = p.readId();
                    remaining -= ids + 4 + 4 + ids;
                    int srcIdx = objectIndex(idMap, objId);
                    // class-object edge (always emitted)
                    if (srcIdx >= 0 && srcIdx < N && elemClassId != 0) {
                        int classObjIdx = objectIndex(idMap, elemClassId);
                        if (classObjIdx >= 0 && classObjIdx < N) {
                            outDegree[srcIdx]++;
                            inDegree[classObjIdx]++;
                        }
                    }
                    // read each element ref to get exact counts
                    for (int i = 0; i < numElem; i++) {
                        long refId = p.readId(); remaining -= ids;
                        if (refId != 0 && srcIdx >= 0 && srcIdx < N) {
                            int dstIdx = objectIndex(idMap, refId);
                            if (dstIdx >= 0 && dstIdx < N) {
                                outDegree[srcIdx]++;
                                inDegree[dstIdx]++;
                            }
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

    private int countClassDumpOutDegrees(Parser p, int ids, IdMap idMap,
                                          HeapGraph graph, int[] outDegree, int[] inDegree) throws IOException {
        int consumed = 0;
        long classId = p.readId(); consumed += ids;
        p.readU4(); consumed += 4; // stack serial
        long superClassId = p.readId(); consumed += ids;
        long classLoaderId = p.readId(); consumed += ids;
        p.skipFully(ids * 4L); consumed += ids * 4; // signers, domain, reserved×2
        p.readU4(); consumed += 4; // instance size

        int srcIdx = objectIndex(idMap, classId);
        int N = graph.N;

        // Emit classObj→superClass and classObj→classLoader
        if (srcIdx >= 0 && srcIdx < N) {
            if (superClassId != 0) {
                int dstIdx = objectIndex(idMap, superClassId);
                if (dstIdx >= 0 && dstIdx < N) { outDegree[srcIdx]++; inDegree[dstIdx]++; }
            }
            if (classLoaderId != 0) {
                int dstIdx = objectIndex(idMap, classLoaderId);
                if (dstIdx >= 0 && dstIdx < N) { outDegree[srcIdx]++; inDegree[dstIdx]++; }
            }
        }

        // Constant pool
        int cpCount = p.readU2(); consumed += 2;
        for (int i = 0; i < cpCount; i++) {
            p.readU2(); consumed += 2;
            int type = p.readU1(); consumed++;
            int sz = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT && srcIdx >= 0 && srcIdx < N) {
                long refId = p.readId(); consumed += ids;
                if (refId != 0) {
                    int dstIdx = objectIndex(idMap, refId);
                    if (dstIdx >= 0 && dstIdx < N) { outDegree[srcIdx]++; inDegree[dstIdx]++; }
                }
            } else {
                p.skipFully(sz); consumed += sz;
            }
        }
        // Static fields
        int sfCount = p.readU2(); consumed += 2;
        for (int i = 0; i < sfCount; i++) {
            p.skipFully(ids); consumed += ids; // name id
            int type = p.readU1(); consumed++;
            int sz = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT && srcIdx >= 0 && srcIdx < N) {
                long refId = p.readId(); consumed += ids;
                if (refId != 0) {
                    int dstIdx = objectIndex(idMap, refId);
                    if (dstIdx >= 0 && dstIdx < N) { outDegree[srcIdx]++; inDegree[dstIdx]++; }
                }
            } else {
                p.skipFully(sz); consumed += sz;
            }
        }
        // Instance fields (count for class object — but these don't generate extra edges here)
        int ifCount = p.readU2(); consumed += 2;
        for (int i = 0; i < ifCount; i++) {
            p.skipFully(ids + 1); consumed += ids + 1; // nameId + type
        }

        return consumed;
    }

    /**
     * Read a CLASS_DUMP record and emit named edges (same as scanClassDumpEdges but
     * using NamedEdgeConsumer for Phase B's named-edge scan).
     * Emits superClass, classLoader, and OBJECT-type static field edges.
     * Returns bytes consumed.
     */
    private int scanClassDumpNamedEdges(Parser p, int ids, IdMap idMap, HeapGraph graph,
                                         NamedEdgeConsumer consumer) throws IOException {
        int consumed = 0;
        long classId = p.readId(); consumed += ids;
        p.readU4(); consumed += 4; // stack serial
        long superClassId = p.readId(); consumed += ids;
        long classLoaderId = p.readId(); consumed += ids;
        p.skipFully(ids * 4L); consumed += ids * 4; // signers, domain, reserved×2
        p.readU4(); consumed += 4; // instance size

        int srcIdx = objectIndex(idMap, classId);
        int srcClassIdx = 0;

        // Emit classObj→superClass and classObj→classLoader edges (as excluded — don't affect retained)
        if (srcIdx >= 0) {
            if (superClassId != 0) {
                int dstIdx = objectIndex(idMap, superClassId);
                if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, Short.MIN_VALUE, -1);
            }
            if (classLoaderId != 0) {
                int dstIdx = objectIndex(idMap, classLoaderId);
                if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, Short.MIN_VALUE, -1);
            }
        }

        // constant pool - emit OBJECT-type entries as edges (MAT-compatible: they hold
        // MethodType/MemberName/String constants that would otherwise be unreachable)
        int cpCount = p.readU2(); consumed += 2;
        for (int i = 0; i < cpCount; i++) {
            p.readU2(); consumed += 2;
            int type = p.readU1(); consumed++;
            int sz = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT && srcIdx >= 0) {
                long refId = p.readId(); consumed += ids;
                if (refId != 0) {
                    int dstIdx = objectIndex(idMap, refId);
                    if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, Short.MIN_VALUE, srcClassIdx);
                }
            } else {
                p.skipFully(sz); consumed += sz;
            }
        }

        // static fields - emit OBJECT-type as named edges from class object to static value
        int sfCount = p.readU2(); consumed += 2;
        for (int i = 0; i < sfCount; i++) {
            long nameId = p.readId(); consumed += ids;
            int type = p.readU1(); consumed++;
            int sz = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT && srcIdx >= 0) {
                long refId = p.readId(); consumed += ids;
                if (refId != 0) {
                    int dstIdx = objectIndex(idMap, refId);
                    if (dstIdx >= 0) {
                        short nameIdx = graph.internFieldName(nameId);
                        consumer.accept(srcIdx, dstIdx, nameIdx, srcClassIdx);
                    }
                }
            } else {
                p.skipFully(sz); consumed += sz;
            }
        }

        // instance field definitions - skip (no values here)
        int ifCount = p.readU2(); consumed += 2;
        for (int i = 0; i < ifCount; i++) {
            p.skipFully(ids + 1); consumed += ids + 1;
        }
        return consumed;
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

    @FunctionalInterface
    private interface NamedEdgeConsumer {
        void accept(int srcIdx, int dstIdx, short nameIdx, int srcClassIdx);
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
                case HPROF_GC_CLASS_DUMP -> // Emit named edges from class object static OBJECT fields
                        remaining -= scanClassDumpNamedEdges(p, ids, idMap, graph, consumer);
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    int dataLen = (int) p.readU4();
                    remaining -= ids + 4 + ids + 4;
                    int srcIdx = objectIndex(idMap, objId);
                    int classIdx = graph.classIdToIndex.getIfAbsent(classId, -1);
                    ClassRecord cr = classIdx >= 0 ? graph.classList.get(classIdx) : null;
                    if (dataLen > instanceDataBuf.length) instanceDataBuf = new byte[dataLen];
                    p.readBytesInto(instanceDataBuf, dataLen); remaining -= dataLen;
                    byte[] data = instanceDataBuf;
                    if (srcIdx >= 0) {
                        int srcClassIdx = classIdx >= 0 ? classIdx : 0;
                        int classObjIdx = objectIndex(idMap, classId);
                        if (classObjIdx >= 0) consumer.accept(srcIdx, classObjIdx, Short.MIN_VALUE, srcClassIdx);
                        if (cr != null) {
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
                }
                case HPROF_GC_OBJ_ARRAY_DUMP -> {
                    long objId = p.readId(); p.readU4(); int numElem = (int) p.readU4();
                    long elemClassId = p.readId();
                    remaining -= ids + 4 + 4 + ids;
                    int srcIdx = objectIndex(idMap, objId);
                    int classIdx = graph.classIdToIndex.getIfAbsent(elemClassId, -1);
                    int srcClassIdx = classIdx >= 0 ? classIdx : 0;
                    // Emit <class> edge: array → its element-class object (always excluded)
                    if (srcIdx >= 0 && elemClassId != 0) {
                        int classObjIdx = objectIndex(idMap, elemClassId);
                        if (classObjIdx >= 0) consumer.accept(srcIdx, classObjIdx, Short.MIN_VALUE, srcClassIdx);
                    }
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
            case HPROF_TYPE_CHAR, HPROF_TYPE_SHORT -> 2;
            case HPROF_TYPE_FLOAT, HPROF_TYPE_INT -> 4;
            case HPROF_TYPE_DOUBLE, HPROF_TYPE_LONG -> 8;
            default -> 1;
        };
    }

    private static boolean isExcluded(int[][] pairs, int classIdx, short nameIdx) {
        if (nameIdx == Short.MIN_VALUE) return true; // class meta edge (superClass/classLoader)
        if (pairs == null || nameIdx == ClassRecord.NO_NAME) return false;
        for (int[] pair : pairs) {
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
        List<int[]> resolved = new ArrayList<>();
        for (String[] pair : defaults) {
            String className = pair[0];
            String fieldName = pair[1];
            // Find classIdx
            int classIdx = -1;
            for (int i = 0; i < graph.classList.size(); i++) {
                if (graph.classList.get(i).name().equals(className)) {
                    classIdx = i;
                    break;
                }
            }
            if (classIdx < 0) continue;
            // Find fieldNameIdx by iterating the fieldNames list (faster than entrySet iteration)
            short nameIdx = ClassRecord.NO_NAME;
            for (int fi = 1; fi < graph.fieldNames.size(); fi++) {
                if (fieldName.equals(graph.fieldNames.get(fi))) {
                    nameIdx = (short) fi;
                    break;
                }
            }
            if (nameIdx == ClassRecord.NO_NAME) continue;
            resolved.add(new int[]{classIdx, nameIdx});
        }
        graph.excludePairs = resolved.toArray(new int[0][]);
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
        private byte[] arrayTypeBuf; // 0=not array, -1=obj array, >0=prim array elem type
        private int count;

        final IntLongHashMap classSerialToId = new IntLongHashMap();
        final LongLongHashMap classIdToNameId = new LongLongHashMap();
        final LongIntHashMap classIdToSerial = new LongIntHashMap();
        final LongIntHashMap classInstanceSizes = new LongIntHashMap();
        final LongIntHashMap classOwnFieldsSizes = new LongIntHashMap(); // sum of own fields' typeSize
        // MAT-parity components: per-class own field breakdown for calculateInstanceSize
        final LongIntHashMap classOwnObjectFieldCount = new LongIntHashMap();   // # OBJECT fields (own)
        final LongIntHashMap classOwnPrimitiveFieldBytes = new LongIntHashMap(); // primitive field bytes (own)
        // Static field breakdown for calculateClassSize (class-object shallow size)
        final LongIntHashMap classStaticObjectFieldCount = new LongIntHashMap();
        final LongIntHashMap classStaticPrimitiveFieldBytes = new LongIntHashMap();
        final LongLongHashMap classLoaderIds = new LongLongHashMap();
        final LongLongHashMap classSuperIds = new LongLongHashMap();
        final LongIntHashMap classSerialByClassId = new LongIntHashMap();
        final LongObjectHashMap<long[]> classObjFields = new LongObjectHashMap<>(); // classId → [nameId0,off0, nameId1,off1, ...]
        final List<Long> classDumpIds = new ArrayList<>();   // all classId values from CLASS_DUMP records

        // Synthesized array class maps (built in buildClassList second pass)
        final LongIntHashMap objArrayElemToClassIdx = new LongIntHashMap(); // elemClassId → synthetic class index
        int[] primArrayClassIdx = new int[12]; // indexed by HPROF type code (0..11), 0=unset

        // GC roots (parallel arrays)
        private long[] gcRootAddrs;
        private byte[] gcRootTypes;
        private int gcRootCount;

        // threadSerial → packed list of local object addresses (for synthetic thread→local edges)
        final IntObjectHashMap<long[]> threadLocalsBySerial = new IntObjectHashMap<>();

        // Compressed-OOPS detection (mirrors MAT's Pass1Parser heuristic on OBJ_ARRAY_DUMP records)
        long previousArrayStart;
        long previousArrayUncompressedEnd;
        boolean foundCompressed;

        // Frames and traces
        final LongLongHashMap frames = new LongLongHashMap(); // frameId → methodNameId
        final IntObjectHashMap<long[]> traces = new IntObjectHashMap<>(); // traceSerial → frameIds
        final IntLongHashMap threadSerialToObjId = new IntLongHashMap();

        A1State(int est, IdMap idMap) {
            this.idMap = idMap;
            addrBuf   = new long[est];
            shallowBuf = new int[est];
            classIdBuf = new long[est];
            arrayTypeBuf = new byte[est];
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
                arrayTypeBuf = Arrays.copyOf(arrayTypeBuf, count * 2);
            }
            addrBuf[count++] = addr;
            idMap.append(addr);
        }

        /** Register an address in IdMap only (no metadata entry). Used for GC-root locals
         *  that are already in the dump as INSTANCE/ARRAY records — we need them in IdMap
         *  for edge resolution but must not add a metadata-less addrBuf entry that could
         *  corrupt shallow sizes computed from the real dump record. */
        void appendAddressToIdMapOnly(long addr) {
            idMap.append(addr);
        }

        void appendShallowSize(long addr, int bytes) {
            // find the entry we just appended (it's always the last one)
            if (count > 0 && addrBuf[count-1] == addr) shallowBuf[count-1] = bytes;
        }

        void appendClassId(long addr, long classId) {
            if (count > 0 && addrBuf[count-1] == addr) classIdBuf[count-1] = classId;
        }

        void appendArrayType(long addr, byte type) {
            if (count > 0 && addrBuf[count-1] == addr) arrayTypeBuf[count-1] = type;
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

        int[] flushClassIndex(int N) {
            int[] arr = new int[N];
            java.util.Arrays.fill(arr, -1); // -1 = class object / unresolved
            return arr;
        }

        void flushGCRoots(HeapGraph graph, IdMap idMap) {
            for (int i = 0; i < gcRootCount; i++) {
                int idx = objectIndex(idMap, gcRootAddrs[i]);
                if (idx >= 0) graph.addGCRoot(idx, gcRootTypes[i]);
            }
            graph.trimRoots();
        }

        void flushClassDumpIndices(HeapGraph graph, IdMap idMap) {
            for (Long classId : classDumpIds) {
                int idx = objectIndex(idMap, classId);
                if (idx >= 0) graph.addClassDumpIndex(idx);
            }
            graph.trimClassDumpIndices();
        }

        void buildClassList(HeapGraph graph, IdMap idMap) {
            // Build ClassRecord for each class (with own-only field offsets initially)
            classSerialByClassId.forEachKeyValue((classId, serial) -> {
                long nameId = classIdToNameId.getIfAbsent(classId, 0L);
                String name = nameId != 0L ? graph.utf8Strings.getIfAbsent(nameId, () -> "?") : "?";
                long loaderId = classLoaderIds.getIfAbsent(classId, 0L);
                long superId = classSuperIds.getIfAbsent(classId, 0L);
                int instSize = classInstanceSizes.getIfAbsent(classId, 0);
                int ownFieldsSize = classOwnFieldsSizes.getIfAbsent(classId, 0);
                long[] objFields = classObjFields.get(classId);
                int objFieldCount2 = objFields != null ? objFields.length / 2 : 0;

                short[] nameIds = new short[objFieldCount2];
                int[] offsets = new int[objFieldCount2];
                for (int i = 0; i < objFieldCount2; i++) {
                    long fieldNameId = objFields[i * 2];
                    nameIds[i] = graph.internFieldName(fieldNameId);
                    offsets[i] = (int) objFields[i * 2 + 1];
                }

                int classIdx = graph.classList.size();
                graph.classList.add(new ClassRecord.Full(classId, name, loaderId, superId,
                        instSize, serial, nameIds, offsets, ownFieldsSize));
                graph.classIdToIndex.put(classId, classIdx);
                graph.classSerialToIndex.put(serial, classIdx);
            });

            // Second pass: synthesize array class records for obj arrays and prim arrays
            for (int i = 0; i < count; i++) {
                byte atype = arrayTypeBuf[i];
                if (atype == 0) continue; // not an array
                if (atype == (byte) -1) {
                    // Object array: classIdBuf[i] is the ARRAY class ID (per HPROF spec,
                    // HPROF_GC_OBJ_ARRAY_DUMP's fourth field is "array class object ID",
                    // not the element class). That class is typically already registered
                    // via LOAD_CLASS + CLASS_DUMP with a name like "[Ljava/lang/Object;".
                    long arrayClassId = classIdBuf[i];
                    if (!objArrayElemToClassIdx.containsKey(arrayClassId)) {
                        int existingIdx = graph.classIdToIndex.getIfAbsent(arrayClassId, -1);
                        if (existingIdx >= 0) {
                            objArrayElemToClassIdx.put(arrayClassId, existingIdx);
                        } else {
                            // Fallback: array class not registered (rare). Synthesize a placeholder.
                            String arrayName = "[Ljava/lang/Object;";
                            int newClassIdx = graph.classList.size();
                            graph.classList.add(new ArrayClassRecord(arrayName));
                            objArrayElemToClassIdx.put(arrayClassId, newClassIdx);
                        }
                    }
                } else {
                    // Primitive array: atype = HPROF type code
                    int typeCode = atype & 0xFF;
                    if (typeCode < primArrayClassIdx.length && primArrayClassIdx[typeCode] == 0) {
                        String arrayName = switch (typeCode) {
                            case HPROF_TYPE_BOOLEAN -> "boolean[]";
                            case HPROF_TYPE_CHAR    -> "char[]";
                            case HPROF_TYPE_FLOAT   -> "float[]";
                            case HPROF_TYPE_DOUBLE  -> "double[]";
                            case HPROF_TYPE_BYTE    -> "byte[]";
                            case HPROF_TYPE_SHORT   -> "short[]";
                            case HPROF_TYPE_INT     -> "int[]";
                            case HPROF_TYPE_LONG    -> "long[]";
                            default                 -> "array[" + typeCode + "]";
                        };
                        int newClassIdx = graph.classList.size();
                        graph.classList.add(new ArrayClassRecord(arrayName));
                        primArrayClassIdx[typeCode] = newClassIdx + 1; // +1 so 0 means unset
                    }
                }
            }

            // Precompute MAT-parity per-instance size for every class using calculateInstanceSize:
            //   alignUp(calculateSizeRecursive(clazz), objectAlign=8)
            //   calculateSizeRecursive(c) =
            //     (c has no super) ? pointerSize + refSize
            //                      : alignUp(ownFieldsSizeMAT(c) + calculateSizeRecursive(super), refSize)
            //   ownFieldsSizeMAT(c) = ownObjectFieldCount(c)*refSize + ownPrimitiveFieldBytes(c)
            final int pointerSize = graph.pointerSize;
            final int refSize = graph.refSize;
            final int objectAlign = graph.objectAlign;
            final LongIntHashMap matInstanceSize = new LongIntHashMap();
            classSuperIds.forEachKey(classId ->
                    computeMatInstanceSize(classId, matInstanceSize,
                            classSuperIds, classOwnObjectFieldCount, classOwnPrimitiveFieldBytes,
                            pointerSize, refSize, objectAlign));
            // Also compute for class-objects themselves: alignUp(staticFieldBytesMAT, objectAlign)
            //   staticFieldBytesMAT = staticObjectFieldCount * refSize + staticPrimitiveFieldBytes
            final LongIntHashMap matClassSize = new LongIntHashMap();
            classSuperIds.forEachKey(classId -> {
                int sfObj = classStaticObjectFieldCount.getIfAbsent(classId, 0);
                int sfPrim = classStaticPrimitiveFieldBytes.getIfAbsent(classId, 0);
                int sfBytes = sfObj * refSize + sfPrim;
                matClassSize.put(classId, alignUp(sfBytes, objectAlign));
            });

            // Look up java.lang.Class classIdx — for attributing class-object shallow to it (MAT parity).
            int javaLangClassIdx = -1;
            for (int ci = 0; ci < graph.classList.size(); ci++) {
                if ("java/lang/Class".equals(graph.classList.get(ci).name())) {
                    javaLangClassIdx = ci;
                    break;
                }
            }

            // Fill shallowSizeDiv8 and classIndex for all objects
            for (int i = 0; i < count; i++) {
                int objIdx = objectIndex(idMap, addrBuf[i]);
                if (objIdx < 0) continue;
                long cid = classIdBuf[i];
                byte atype = arrayTypeBuf[i];
                int rawShallow = shallowBuf[i]; // for arrays: numElem; for instances: 0 (unused); for class objs: 0
                int bytes;
                boolean isClassObject = false;
                if (atype == (byte) -1) {
                    // Object array: MAT formula
                    //   alignUp(pointerSize + refSize + 4 + numElem * refSize, objectAlign)
                    bytes = alignUp(pointerSize + refSize + 4 + rawShallow * refSize, objectAlign);
                } else if (atype != 0) {
                    // Primitive array: MAT formula
                    //   alignUp(alignUp(pointerSize + refSize + 4, refSize) + numElem * elemSize, objectAlign)
                    int elemSize = primTypeSize(atype & 0xFF);
                    bytes = alignUp(alignUp(pointerSize + refSize + 4, refSize) + rawShallow * elemSize, objectAlign);
                } else if (cid != 0) {
                    // Non-array: either an instance object (has class registered in matInstanceSize)
                    // or a class-object (registered in matClassSize).
                    // A class-object appears in classDumpIds; its classId points to itself.
                    int v = matInstanceSize.getIfAbsent(cid, -1);
                    if (v >= 0) {
                        bytes = v;
                    } else {
                        // Class object: cid IS the class's own id → use matClassSize
                        int cv = matClassSize.getIfAbsent(cid, -1);
                        bytes = cv >= 0 ? cv : alignUp(pointerSize + refSize, objectAlign);
                        isClassObject = true;
                    }
                } else {
                    // No class id (should be rare — class-dump self entry)
                    int cv = matClassSize.getIfAbsent(addrBuf[i], -1);
                    bytes = cv >= 0 ? cv : alignUp(pointerSize + refSize, objectAlign);
                    isClassObject = true;
                }
                if (bytes > 0) {
                    int div8 = bytes / 8;
                    if (div8 > 0 && div8 <= 255) {
                        graph.shallowSizeDiv8[objIdx] = (byte) div8;
                    } else {
                        if (graph.overflowSizes == null) graph.overflowSizes = new HeapGraph.LongLongMap(64);
                        graph.overflowSizes.put(objIdx, bytes);
                    }
                }
                // Class index: use synthesized array class for arrays;
                // MAT parity: class-objects are attributed to java.lang.Class, not their own class.
                int cidx2;
                if (atype == (byte) -1) {
                    cidx2 = objArrayElemToClassIdx.getIfAbsent(cid, -1);
                } else if (atype != 0) {
                    int typeCode = atype & 0xFF;
                    cidx2 = (typeCode < primArrayClassIdx.length) ? primArrayClassIdx[typeCode] - 1 : -1;
                } else if (isClassObject) {
                    cidx2 = javaLangClassIdx; // attribute class-dump objects to java.lang.Class
                } else {
                    cidx2 = graph.classIdToIndex.getIfAbsent(cid, -1);
                }
                if (cidx2 >= 0) {
                    graph.classIndex[objIdx] = cidx2;
                }
            }
            // Free large A1State scan buffers — no longer needed after class/object metadata is built
            addrBuf = null; shallowBuf = null; classIdBuf = null; arrayTypeBuf = null;
        }

        void buildTraceFrames(HeapGraph graph) {
            traces.forEachKeyValue((traceSerial, frameIds) -> {
                long[] methodNameIds = new long[frameIds.length];
                for (int i = 0; i < frameIds.length; i++) {
                    methodNameIds[i] = frames.getIfAbsent(frameIds[i], 0L);
                }
                graph.traceFrames.put(traceSerial, methodNameIds);
            });
        }
    }

    // =========================================================
    // CsrBuilderEncoder: VByte encoding of inboundTargets
    // =========================================================

    private static final class CsrBuilderEncoder {
        private final HeapGraph graph;
        private int[][] targets;
        private final int[] offsets;
        private final int n;

        CsrBuilderEncoder(HeapGraph graph, int[][] targets, int[] offsets, int n) {
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
        static void sortAndEncode(int[][] targets, int[] offsets, int n, HeapGraph graph) {
            int totalEdges = offsets[n];
            java.util.BitSet newExcluded = new java.util.BitSet(totalEdges);

            // Estimate stream size: VByte deltas average ~1.5 bytes each.
            // For small heaps (estimate < CHUNK_SIZE): encode into a single byte[] to minimize
            // peak allocation. For large heaps: use chunked byte[][] (CHUNK_MASK addressing).
            long estimatedBytes = Math.max((long) totalEdges + totalEdges / 2L, 16L);

            if (estimatedBytes < VByte.CHUNK_SIZE) {
                // Single-buffer path: allocate one byte[] at estimated size (no 512 MB chunk).
                sortAndEncodeSingle(targets, offsets, n, newExcluded, (int) estimatedBytes, graph);
            } else {
                // Chunked path: full CHUNK_SIZE chunks for CHUNK_MASK addressing.
                sortAndEncodeChunked(targets, offsets, n, newExcluded, estimatedBytes, graph);
            }
        }

        /** Encode into a single byte[] (heap fits in < 512 MB stream). */
        private static void sortAndEncodeSingle(int[][] targets, int[] offsets, int n,
                java.util.BitSet newExcluded, int estimatedBytes, HeapGraph graph) {
            byte[] singleBuf = new byte[estimatedBytes];
            int[] byteOffsets = new int[n + 1];
            int streamPos = 0;
            int logicalEdgeIdx = 0;
            int[] rowBuf = null; // scratch buffer for row extraction

            for (int v = 0; v < n; v++) {
                int lo = offsets[v];
                int hi = offsets[v + 1];
                int rowLen = hi - lo;
                if (rowLen > 1) {
                    if (rowBuf == null || rowBuf.length < rowLen) rowBuf = new int[rowLen];
                    extractRow(targets, lo, rowLen, rowBuf);
                    sortWithFlags(rowBuf, 0, rowLen);
                    putRow(targets, lo, rowLen, rowBuf);
                }

                byteOffsets[v] = streamPos;
                int prev = 0;
                for (int i = lo; i < hi; i++) {
                    int raw = chunkGet(targets, i);
                    boolean excl = (raw & Integer.MIN_VALUE) != 0;
                    if (excl) { logicalEdgeIdx++; continue; } // skip excluded: don't affect dominator tree
                    int src = raw & Integer.MAX_VALUE;
                    int delta = src - prev;
                    prev = src;

                    // Grow single buffer if needed (rare: estimate was too small).
                    if (streamPos + 8 > singleBuf.length) {
                        singleBuf = Arrays.copyOf(singleBuf, Math.min(singleBuf.length * 2, VByte.CHUNK_SIZE - 1));
                    }
                    streamPos = VByte.encode(delta, singleBuf, streamPos);
                    logicalEdgeIdx++;
                }
            }
            byteOffsets[n] = streamPos;

            // Trim to actual size and wrap in byte[][1].
            if (streamPos < singleBuf.length) singleBuf = Arrays.copyOf(singleBuf, streamPos);
            graph.inboundStream  = new byte[][] { singleBuf };
            graph.inboundOffsets = byteOffsets;
            graph.excludedEdge   = newExcluded;
        }

        /** Encode into chunked byte[][] (heap stream exceeds CHUNK_SIZE).
         *  Source chunks of targets[] are freed (nulled) as soon as the read cursor advances
         *  past them, overlapping source and dest only one chunk at a time (~0.5 GB overlap
         *  instead of full 6+ GB source alive throughout). Output chunks are allocated lazily. */
        private static void sortAndEncodeChunked(int[][] targets, int[] offsets, int n,
                java.util.BitSet newExcluded, long estimatedBytes, HeapGraph graph) {
            // Allocate output array but NOT the chunks yet — allocate each lazily.
            int maxOutChunks = (int) ((estimatedBytes + VByte.CHUNK_SIZE - 1) >>> VByte.CHUNK_BITS) + 2;
            if (maxOutChunks < 1) maxOutChunks = 1;
            byte[][] stream = new byte[maxOutChunks][];

            int[] byteOffsets = new int[n + 1];
            long streamPos = 0;
            int logicalEdgeIdx = 0;
            int[] rowBuf = null;
            int lastFreedSrcChunk = -1; // last source chunk index that has been freed

            for (int v = 0; v < n; v++) {
                int lo = offsets[v];
                int hi = offsets[v + 1];
                int rowLen = hi - lo;

                // Free source chunks strictly before the chunk containing lo.
                // A source chunk c covers logical indices [c*CHUNK_SIZE, (c+1)*CHUNK_SIZE).
                // Once the current row starts in chunk C, all chunks < C will never be read again.
                int currentSrcChunk = lo >>> HeapGraph.TARGETS_CHUNK_BITS;
                while (lastFreedSrcChunk < currentSrcChunk - 1) {
                    lastFreedSrcChunk++;
                    targets[lastFreedSrcChunk] = null;
                }

                if (rowLen > 1) {
                    if (rowBuf == null || rowBuf.length < rowLen) rowBuf = new int[rowLen];
                    extractRow(targets, lo, rowLen, rowBuf);
                    sortWithFlags(rowBuf, 0, rowLen);
                    putRow(targets, lo, rowLen, rowBuf);
                }

                byteOffsets[v] = (int) streamPos;
                int prev = 0;
                for (int i = lo; i < hi; i++) {
                    int raw = chunkGet(targets, i);
                    boolean excl = (raw & Integer.MIN_VALUE) != 0;
                    if (excl) { logicalEdgeIdx++; continue; } // skip excluded: don't affect dominator tree
                    int src = raw & Integer.MAX_VALUE;
                    int delta = src - prev;
                    prev = src;

                    int chunkIdx = (int) (streamPos >>> VByte.CHUNK_BITS);
                    int chunkOff = (int) (streamPos & VByte.CHUNK_MASK);
                    // Ensure the current output chunk exists (lazy allocation).
                    if (stream[chunkIdx] == null) {
                        stream[chunkIdx] = new byte[VByte.CHUNK_SIZE];
                    }
                    if (chunkOff + 8 > VByte.CHUNK_SIZE) {
                        // Near end of current chunk: ensure next chunk exists.
                        if (chunkIdx + 1 >= stream.length) {
                            stream = Arrays.copyOf(stream, stream.length + 2);
                        }
                        if (stream[chunkIdx + 1] == null) {
                            stream[chunkIdx + 1] = new byte[VByte.CHUNK_SIZE];
                        }
                    }

                    streamPos = VByte.encode(delta, stream, streamPos);
                    logicalEdgeIdx++;
                }
            }
            byteOffsets[n] = (int) streamPos;

            // Trim last chunk and discard trailing empty chunks.
            int finalChunkIdx = (int) (streamPos >>> VByte.CHUNK_BITS);
            int finalChunkOff = (int) (streamPos & VByte.CHUNK_MASK);
            if (finalChunkOff < stream[finalChunkIdx].length) {
                stream[finalChunkIdx] = Arrays.copyOf(stream[finalChunkIdx], finalChunkOff);
            }
            if (finalChunkIdx + 1 < stream.length) {
                stream = Arrays.copyOf(stream, finalChunkIdx + 1);
            }

            graph.inboundStream  = stream;
            graph.inboundOffsets = byteOffsets;
            graph.excludedEdge   = newExcluded;
        }

        static int chunkGet(int[][] arr, int idx) {
            return arr[idx >>> HeapGraph.TARGETS_CHUNK_BITS][idx & HeapGraph.TARGETS_CHUNK_MASK];
        }

        /** Copy row [lo, lo+len) from chunked array into flat buf[0..len). */
        private static void extractRow(int[][] arr, int lo, int len, int[] buf) {
            for (int i = 0; i < len; i++) {
                buf[i] = chunkGet(arr, lo + i);
            }
        }

        /** Write flat buf[0..len) back to chunked array at [lo, lo+len). */
        private static void putRow(int[][] arr, int lo, int len, int[] buf) {
            for (int i = 0; i < len; i++) {
                int idx = lo + i;
                arr[idx >>> HeapGraph.TARGETS_CHUNK_BITS][idx & HeapGraph.TARGETS_CHUNK_MASK] = buf[i];
            }
        }

        private static void sortWithFlags(int[] arr, int lo, int hi) {
            int len = hi - lo;
            if (len <= 16) {
                // Insertion sort for small rows (preserves sign bit)
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
                // Pack (src31 << 1 | excl) into a long so Arrays.sort keeps flags O(N log N)
                long[] packed = new long[len];
                for (int i = 0; i < len; i++) {
                    int raw = arr[lo + i];
                    int src = raw & Integer.MAX_VALUE;
                    int excl = (raw >>> 31) & 1;
                    packed[i] = ((long) src << 1) | excl;
                }
                Arrays.sort(packed);
                for (int i = 0; i < len; i++) {
                    long p = packed[i];
                    int src  = (int) (p >>> 1);
                    int excl = (int) (p & 1);
                    arr[lo + i] = excl != 0 ? (src | Integer.MIN_VALUE) : src;
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

        void readBytesInto(byte[] dest, int len) throws IOException {
            int offset = 0;
            while (offset < len) {
                int avail = buf.remaining();
                if (avail == 0) { refill(); avail = buf.remaining(); }
                int chunk = Math.min(avail, len - offset);
                buf.get(dest, offset, chunk);
                offset += chunk;
            }
        }

        void skipFully(long n) throws IOException {
            if (n <= 0) return;
            int avail = buf.remaining();
            if (avail >= n) {
                buf.position(buf.position() + (int) n);
                return;
            }
            // Fast path for FileChannel: seek past large skips without reading through buffer
            if (channel != null) {
                // logical file pos = channel.position() - buf.remaining()
                // target channel pos = channel.position() - avail + n
                long targetPos = channel.position() - avail + n;
                buf.position(buf.limit()); // discard buffered bytes
                channel.position(targetPos);
                // buf has remaining==0; next read call will refill from targetPos
                return;
            }
            // Stream path: drain buffer then read+discard
            n -= avail;
            buf.position(buf.limit());
            while (n > 0) {
                refill();
                avail = buf.remaining();
                if (avail >= n) { buf.position(buf.position() + (int) n); n = 0; }
                else { n -= avail; buf.position(buf.limit()); }
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