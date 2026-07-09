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
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

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
        // --- Build classObjClassIdx: node → the classListIndex it represents as a class object ---
        buildClassObjClassIdx(graph);
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
        for (int ci = 0; ci < n; ci++) {
            ClassRecord cr = graph.classList.get(ci);
            if (cr.classId() == 0L) continue; // synthesized array class, no instance fields
            // Walk the class hierarchy: subclass fields first, then super, super-super, ...
            java.util.List<short[]> nameChunks = new java.util.ArrayList<>();
            java.util.List<int[]> offsetChunks = new java.util.ArrayList<>();
            int baseOffset = 0;
            ClassRecord cur = cr;
            int guard = 0;
            while (cur != null && guard++ < 256) {
                short[] names = cur.objectFieldNameIds();
                int[]   offs  = cur.objectFieldOffsets();
                if (names.length > 0) {
                    int[] adj = new int[offs.length];
                    for (int k = 0; k < offs.length; k++) adj[k] = offs[k] + baseOffset;
                    nameChunks.add(names);
                    offsetChunks.add(adj);
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
            graph.classList.set(ci, new ClassRecord(cr.classId(), cr.name(), cr.classLoaderId(),
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
        short[] result = new short[N];
        java.util.Arrays.fill(result, (short) -1);
        int classCount = graph.classList.size();
        for (int ci = 0; ci < classCount; ci++) {
            long classId = graph.classList.get(ci).classId();
            if (classId == 0L) continue;
            int nodeIdx = graph.idMap.indexOf(classId) + 1;
            if (nodeIdx <= 0 || nodeIdx >= N) continue;
            result[nodeIdx] = (short) Math.min(ci, Short.MAX_VALUE);
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

    private static Map<Integer, int[]> buildSyntheticEdges(A1State state, HeapGraph graph, IdMap idMap) {        Map<Integer, int[]> result = new HashMap<>();
        for (Map.Entry<Integer, List<Long>> entry : state.threadLocalsBySerial.entrySet()) {
            int threadSerial = entry.getKey();
            long threadObjId = graph.threadSerialToObjectId.getIfAbsent(threadSerial, 0L);
            if (threadObjId == 0L) continue;
            int threadIdx = idMap.indexOf(threadObjId);
            if (threadIdx < 0) continue;
            int threadIdxAdjusted = threadIdx + 1; // +1 for virtual root offset
            List<Long> localIds = entry.getValue();
            int[] localIdxArr = new int[localIds.size()];
            int count = 0;
            for (Long localId : localIds) {
                int localIdx = idMap.indexOf(localId);
                if (localIdx >= 0) { localIdxArr[count++] = localIdx + 1; }
            }
            if (count > 0) { result.put(threadIdxAdjusted, java.util.Arrays.copyOf(localIdxArr, count)); }
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
                    long id = p.readId();
                    int threadSerial = (int) p.readU4();
                    p.skipFully(4); // stackSerial
                    remaining -= ids + 8;
                    state.appendAddress(id);
                    state.appendGCRoot(id, (byte) subTag);  // Thread object IS a GC root
                    // Also populate threadSerial → objectId mapping (same as HPROF_START_THREAD)
                    state.threadSerialToObjId.put(threadSerial, id);
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
                    remaining -= ids + 4 + 4 + ids + (long) numElem * ids;

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
        List<long[]> objFields = new ArrayList<>(); // [nameId, offset]
        int offset = 0;
        int ownObjectFieldCount = 0;
        int ownPrimitiveFieldBytes = 0;
        for (int i = 0; i < ifCount; i++) {
            long nameId = p.readId(); consumed += ids;
            int type = p.readU1(); consumed += 1;
            int ts = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT) {
                objFields.add(new long[]{nameId, offset});
                ownObjectFieldCount++;
            } else {
                ownPrimitiveFieldBytes += ts;
            }
            offset += ts;
        }
        state.classObjFields.put(classId, objFields);
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
                    // Emit edges from class object static OBJECT fields
                    remaining -= scanClassDumpEdges(p, ids, idMap, graph, consumer);
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    int dataLen = (int) p.readU4();
                    remaining -= ids + 4 + ids + 4;
                    int srcIdx = objectIndex(idMap, objId);
                    if (srcIdx < 0) { p.skipFully(dataLen); remaining -= dataLen; break; }
                    // Emit <class> edge: instance → its class object (MAT-compatible)
                    int classObjIdx = objectIndex(idMap, classId);
                    if (classObjIdx >= 0) consumer.accept(srcIdx, classObjIdx, 0L);
                    // Emit edges for each OBJECT-type field
                    int classIdx = graph.classIdToIndex.getIfAbsent(classId, -1);
                    ClassRecord cr = classIdx >= 0 ? graph.classList.get(classIdx) : null;
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
                    long elemClassId = p.readId();
                    remaining -= ids + 4 + 4 + ids;
                    int srcIdx = objectIndex(idMap, objId);
                    // Emit <class> edge: array → its element-class object (MAT-compatible)
                    if (srcIdx >= 0 && elemClassId != 0) {
                        int classObjIdx = objectIndex(idMap, elemClassId);
                        if (classObjIdx >= 0) consumer.accept(srcIdx, classObjIdx, 0L);
                    }
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

    /**
     * Read a CLASS_DUMP record and emit edges: classObj→superClass, classObj→loader,
     * classObj→staticObjectField for each OBJECT-type static field.
     * Returns bytes consumed.
     */
    private int scanClassDumpEdges(Parser p, int ids, IdMap idMap, HeapGraph graph,
                                    EdgeConsumer consumer) throws IOException {
        int consumed = 0;
        long classId = p.readId(); consumed += ids;
        p.readU4(); consumed += 4; // stack serial
        long superClassId = p.readId(); consumed += ids;
        long classLoaderId = p.readId(); consumed += ids;
        p.skipFully(ids * 4L); consumed += ids * 4; // signers, domain, reserved×2
        p.readU4(); consumed += 4; // instance size

        int srcIdx = objectIndex(idMap, classId);
        // Emit classObj→superClass and classObj→classLoader edges for reachability.
        if (srcIdx >= 0) {
            if (superClassId != 0) {
                int dstIdx = objectIndex(idMap, superClassId);
                if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, 0L);
            }
            if (classLoaderId != 0) {
                int dstIdx = objectIndex(idMap, classLoaderId);
                if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, 0L);
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
                    if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, 0L);
                }
            } else {
                p.skipFully(sz); consumed += sz;
            }
        }

        // static fields - emit OBJECT-type as edges from class object to static value
        int sfCount = p.readU2(); consumed += 2;
        for (int i = 0; i < sfCount; i++) {
            p.skipFully(ids); consumed += ids; // name id
            int type = p.readU1(); consumed++;
            int sz = typeSize(type, ids);
            if (type == HPROF_TYPE_OBJECT && srcIdx >= 0) {
                long refId = p.readId(); consumed += ids;
                if (refId != 0) {
                    int dstIdx = objectIndex(idMap, refId);
                    if (dstIdx >= 0) {
                        consumer.accept(srcIdx, dstIdx, 0L);
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
        short srcClassIdx = 0;

        // Emit classObj→superClass and classObj→classLoader edges (as excluded — don't affect retained)
        if (srcIdx >= 0) {
            if (superClassId != 0) {
                int dstIdx = objectIndex(idMap, superClassId);
                if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, Short.MIN_VALUE, (short) -1);
            }
            if (classLoaderId != 0) {
                int dstIdx = objectIndex(idMap, classLoaderId);
                if (dstIdx >= 0) consumer.accept(srcIdx, dstIdx, Short.MIN_VALUE, (short) -1);
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
                    // Emit named edges from class object static OBJECT fields
                    remaining -= scanClassDumpNamedEdges(p, ids, idMap, graph, consumer);
                }
                case HPROF_GC_INSTANCE_DUMP -> {
                    long objId = p.readId(); p.readU4(); long classId = p.readId();
                    int dataLen = (int) p.readU4();
                    remaining -= ids + 4 + ids + 4;
                    int srcIdx = objectIndex(idMap, objId);
                    int classIdx = graph.classIdToIndex.getIfAbsent(classId, -1);
                    ClassRecord cr = classIdx >= 0 ? graph.classList.get(classIdx) : null;
                    byte[] data = p.readBytes(dataLen); remaining -= dataLen;
                    if (srcIdx >= 0) {
                        short srcClassIdx = classIdx >= 0 ? (short) classIdx : 0;
                        // Emit <class> edge: instance → its class object (MAT-compatible, always excluded)
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
                    short srcClassIdx = classIdx >= 0 ? (short) classIdx : 0;
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
        if (nameIdx == Short.MIN_VALUE) return true; // class meta edge (superClass/classLoader)
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
        final Map<Long, List<long[]>> classObjFields = new HashMap<>(); // classId → [[nameId,offset]]
        final List<Long> classDumpIds = new ArrayList<>();   // all classId values from CLASS_DUMP records

        // Synthesized array class maps (built in buildClassList second pass)
        final LongIntHashMap objArrayElemToClassIdx = new LongIntHashMap(); // elemClassId → synthetic class index
        int[] primArrayClassIdx = new int[12]; // indexed by HPROF type code (0..11), 0=unset

        // GC roots (parallel arrays)
        private long[] gcRootAddrs;
        private byte[] gcRootTypes;
        private int gcRootCount;

        // threadSerial → packed list of local object addresses (for synthetic thread→local edges)
        final Map<Integer, List<Long>> threadLocalsBySerial = new HashMap<>();

        // Compressed-OOPS detection (mirrors MAT's Pass1Parser heuristic on OBJ_ARRAY_DUMP records)
        long previousArrayStart;
        long previousArrayUncompressedEnd;
        boolean foundCompressed;

        // Frames and traces
        final LongLongHashMap frames = new LongLongHashMap(); // frameId → methodNameId
        final Map<Integer, long[]> traces = new HashMap<>(); // traceSerial → frameIds
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

        short[] flushClassIndex(int N) {
            short[] arr = new short[N];
            java.util.Arrays.fill(arr, (short) -1); // -1 = class object / unresolved
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
                String name = nameId != 0L ? graph.utf8Strings.getOrDefault(nameId, "?") : "?";
                long loaderId = classLoaderIds.getIfAbsent(classId, 0L);
                long superId = classSuperIds.getIfAbsent(classId, 0L);
                int instSize = classInstanceSizes.getIfAbsent(classId, 0);
                int ownFieldsSize = classOwnFieldsSizes.getIfAbsent(classId, 0);
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
                            graph.classList.add(new ClassRecord(0L, arrayName, 0L, 0L,
                                    0, 0, new short[0], new int[0], 0));
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
                        graph.classList.add(new ClassRecord(0L, arrayName, 0L, 0L,
                                0, 0, new short[0], new int[0], 0));
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
                if (cidx2 >= 0 && cidx2 <= Short.MAX_VALUE) {
                    graph.classIndex[objIdx] = (short) cidx2;
                }
            }
        }

        void buildTraceFrames(HeapGraph graph) {
            for (Map.Entry<Integer, long[]> e : traces.entrySet()) {
                long[] frameIds = e.getValue();
                long[] methodNameIds = new long[frameIds.length];
                for (int i = 0; i < frameIds.length; i++) {
                    methodNameIds[i] = frames.getIfAbsent(frameIds[i], 0L);
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
