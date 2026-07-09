/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;

/**
 * Container for all in-memory structures built from an HPROF file.
 *
 * Index 0 is always the "virtual root" node — a synthetic entry that has edges to all GC roots.
 * All object indices are 1-based after the virtual root, but stored in 0-based arrays where
 * the virtual root occupies slot 0.
 *
 * Memory design target: ~21 GB peak for a 48 GB HPROF with 800M objects / 1.6B edges,
 * using VByte-encoded inbound CSR and BitSet exclude flags.
 */
public final class HeapGraph {

    /** Sentinel value for "not yet computed" or "no dominator" in idom[]. */
    public static final int UNDEFINED = -1;
    /** Index of the virtual root node. */
    public static final int VIRTUAL_ROOT = 0;

    // ---- Identity / file info ----
    final Path sourcePath;
    final int idSize;                // 4 or 8
    /** Object header pointer size. Same as idSize. */
    int pointerSize;
    /** Object reference size. Equals idSize unless compressed OOPS is detected (then 4). */
    int refSize;
    /** Object alignment in bytes (always 8 on 64-bit JVMs). */
    int objectAlign = 8;
    final long heapTotalBytes;       // sum of all object shallow sizes
    final String hprofFormat;        // e.g. "JAVA PROFILE 1.0.2"
    final long fileSize;

    // ---- Object count ----
    /** Total objects including virtual root at index 0. */
    int N;

    // ---- Address mapping ----
    final IdMap idMap;

    // ---- Per-object metadata ----
    byte[]  shallowSizeDiv8;         // 1 byte/obj; value×8 = shallow bytes; 0 = overflowSizes
    LongLongMap overflowSizes;       // objectId(int key) → long shallow size (for objects > 2040 B)
    short[] classIndex;              // index into classList; -1 = class object itself
    final BitSet isGCRoot;
    int[] gcRootIds;                 // dense list of root object indices
    byte[] gcRootTypes;              // GC root type code per actual root
    int gcRootCount;
    /** Roots added synthetically by addSystemClassRootsIfMissing — not from HPROF records. */
    int syntheticRootCount;

    // ---- Inbound reference graph (VByte stream, --low-memory) ----
    int[]  inboundOffsets;           // (N+1) elements; inboundOffsets[i] = byte start of row i in stream
    byte[] inboundStream;            // VByte delta-encoded predecessor lists
    BitSet excludedEdge;             // 1 bit per logical edge position in sorted VByte order

    // ---- Dominator tree ----
    int[] idom;                      // immediate dominator; UNDEFINED=-1; 0=virtual root

    // ---- RPO traversal arrays (freed after use) ----
    int[] rpoPos;                    // RPO position of each node (freed after CHK)
    int[] rpoOrder;                  // node at each RPO position (freed after retained sizes)

    // ---- Retained heap sizes ----
    int[] retainedSize;              // unsigned int per obj; query via retainedSizeOf()
    LongLongMap retainedSizeOverflow;// objectId → long for objects whose retained > 4.29 GB

    /** For each object v (idx > 0): true iff some strict ancestor in the dominator tree has
     *  the same classIndex as v, OR the class-object for class(v) is a strict ancestor.
     *  Populated by RetainedSizes.compute. Consumed by the class histogram to identify
     *  MAT-style "top ancestors" for each class. */
    BitSet hasSameClassAncestor;

    /** For each node index v: the classList index of the class that this node IS the class-object
     *  for, or -1 if v is not a class-object. Built by HeapGraphBuilder after classList is final.
     *  Used by RetainedSizes to detect "classObject(C) is ancestor of v" in O(N). */
    short[] classObjClassIdx;

    // ---- Class object indices (populated in Phase A.1, used in RPO/DomTree) ----
    /** All object indices that appear in HPROF_GC_CLASS_DUMP records (1-based).
     *  Treated as virtual-root-adjacent (like GC roots) for reachability, but
     *  NOT counted in gcRootCount (display only shows "real" GC roots). */
    int[] classDumpIndices;
    int classDumpCount;

    // ---- Unreachable object stats (computed after domtree) ----
    int unreachableCount;
    long unreachableShallowBytes;

    // ---- Class table ----
    final List<ClassRecord> classList;
    final LongIntHashMap classIdToIndex;         // classId → index in classList
    final Map<Integer, Integer> classSerialToIndex; // classSerial → index in classList (keep boxed, small)

    // ---- Interned field names ----
    /** Maps HPROF nameId → short intern index (0 = no-name sentinel; real names start at 1). */
    final Map<Long, Short> fieldNameIntern;
    final List<String> fieldNames;   // index = intern index; fieldNames.get(0) = "" (sentinel)

    // ---- UTF-8 strings from HPROF ----
    final Map<Long, String> utf8Strings;         // nameId → decoded string

    // ---- Thread / frame info (for thread_overview) ----
    /** threadSerial → list of frame method nameIds */
    final Map<Integer, long[]> traceFrames;
    /** threadSerial → Thread object id */
    final IntLongHashMap threadSerialToObjectId;

    // ---- Exclude pairs (resolved at build time) ----
    /** 3 default exclude (classIndex, fieldNameInternIdx) pairs. Filled by HeapGraphBuilder. */
    short[][] excludePairs;          // [3][2]: {classIdx, fieldNameIdx}

    // ---- Stack trace data (populated only when --stack-traces is passed) ----
    StackTraceData stackTraces;   // null unless StackTraceReader.read() was called

    // ---- Synthetic thread→local edges (built in phase A.1, consumed in A.2, then freed) ----
    /** threadIdx (1-based) → int[] of localIdx values (synthetic edges from thread→local for frame/stack roots).
     *  Nulled after forward CSR is built. */
    Map<Integer, int[]> syntheticThreadEdges;  // null after edge CSR is built

    HeapGraph(Path sourcePath, int idSize, long fileSize, String hprofFormat, IdMap idMap) {
        this.sourcePath = sourcePath;
        this.idSize = idSize;
        this.pointerSize = idSize;
        this.refSize = idSize; // may be lowered to 4 on compressed-OOPS detection
        this.fileSize = fileSize;
        this.hprofFormat = hprofFormat;
        this.idMap = idMap;
        this.heapTotalBytes = 0; // set by builder after all shallow sizes collected
        this.isGCRoot = new BitSet();
        this.classList = new ArrayList<>();
        this.classIdToIndex = new LongIntHashMap();
        this.classSerialToIndex = new HashMap<>();
        this.fieldNameIntern = new HashMap<>();
        this.fieldNames = new ArrayList<>();
        this.fieldNames.add(""); // index 0 = no-name sentinel
        this.utf8Strings = new HashMap<>();
        this.traceFrames = new HashMap<>();
        this.threadSerialToObjectId = new IntLongHashMap();
        // allocate a small initial capacity for GC roots (grow on demand)
        this.gcRootIds = new int[1024];
        this.gcRootTypes = new byte[1024];
        this.gcRootCount = 0;
        this.classDumpIndices = new int[4096];
        this.classDumpCount = 0;
    }

    /** Intern a field nameId, returning a short index (0 = no-name). */
    short internFieldName(long nameId) {
        if (nameId == 0) return ClassRecord.NO_NAME;
        Short existing = fieldNameIntern.get(nameId);
        if (existing != null) return existing;
        if (fieldNames.size() > Short.MAX_VALUE) {
            // Overflow: reuse 0 sentinel (field name filtering won't work for this name)
            return ClassRecord.NO_NAME;
        }
        short idx = (short) fieldNames.size();
        fieldNames.add(utf8Strings.getOrDefault(nameId, "?"));
        fieldNameIntern.put(nameId, idx);
        return idx;
    }

    String fieldNameFor(short internIdx) {
        if (internIdx <= 0 || internIdx >= fieldNames.size()) return "";
        return fieldNames.get(internIdx);
    }

    /** Add a GC root object index and type code.
     *  MAT parity: gcRoots is deduplicated by objIdx — an object appearing as a root
     *  multiple times (e.g., SYSTEM_CLASS + JNI_GLOBAL) counts once. */
    void addGCRoot(int objIdx, byte rootType) {
        if (isGCRoot.get(objIdx)) return; // already a root
        isGCRoot.set(objIdx);
        if (gcRootCount == gcRootIds.length) {
            gcRootIds  = Arrays.copyOf(gcRootIds, gcRootCount * 2);
            gcRootTypes = Arrays.copyOf(gcRootTypes, gcRootCount * 2);
        }
        gcRootIds[gcRootCount] = objIdx;
        gcRootTypes[gcRootCount] = rootType;
        gcRootCount++;
    }

    /** Trim root arrays to actual count. */
    void trimRoots() {
        gcRootIds  = Arrays.copyOf(gcRootIds, gcRootCount);
        gcRootTypes = Arrays.copyOf(gcRootTypes, gcRootCount);
    }

    /** Record a class object index as being in a CLASS_DUMP record (for implicit reachability). */
    void addClassDumpIndex(int objIdx) {
        if (classDumpCount == classDumpIndices.length) {
            classDumpIndices = Arrays.copyOf(classDumpIndices, classDumpCount * 2);
        }
        classDumpIndices[classDumpCount++] = objIdx;
    }

    /** Trim classDumpIndices to actual count. */
    void trimClassDumpIndices() {
        classDumpIndices = Arrays.copyOf(classDumpIndices, classDumpCount);
    }

    /** Shallow size in bytes for object at index idx. */
    long shallowSizeOf(int idx) {
        int div8 = Byte.toUnsignedInt(shallowSizeDiv8[idx]);
        if (div8 != 0) return (long) div8 * 8;
        if (overflowSizes != null) {
            long v = overflowSizes.get(idx);
            if (v != LongLongMap.NOT_FOUND) return v;
        }
        return 0L;
    }

    /** Retained size for object at index idx (interprets retainedSize as unsigned). */
    long retainedSizeOf(int idx) {
        if (retainedSizeOverflow != null) {
            long v = retainedSizeOverflow.get(idx);
            if (v != LongLongMap.NOT_FOUND) return v;
        }
        return Integer.toUnsignedLong(retainedSize[idx]);
    }

    /** Set retained size; use overflow map if > unsigned int max. */
    void setRetainedSize(int idx, long value) {
        if (value > 0xFFFFFFFFL) {
            if (retainedSizeOverflow == null) retainedSizeOverflow = new LongLongMap(64);
            retainedSizeOverflow.put(idx, value);
            retainedSize[idx] = (int) 0xFFFFFFFFL; // sentinel "check overflow"
        } else {
            retainedSize[idx] = (int) value;
        }
    }

    /** Total heap bytes (sum of all shallow sizes, excluding virtual root). */
    long totalHeapBytes() {
        long total = 0;
        for (int i = 1; i < N; i++) total += shallowSizeOf(i);
        return total;
    }

    // ---- Transient forward CSR (built in Phase A.2, freed after RPO DFS) ----
    int[] fwdOffsets;
    int[] fwdTargets;
    int totalEdges; // total inbound edges (for Phase B allocation)

    void computeUnreachableStats() {
        unreachableCount = 0;
        unreachableShallowBytes = 0L;
        for (int i = 1; i < N; i++) {
            if (idom[i] == UNDEFINED) {
                unreachableCount++;
                unreachableShallowBytes += shallowSizeOf(i);
            }
        }
    }

    /** Count of reachable class-dump objects (matches MAT's "Number of classes"). */
    int reachableClassCount() {
        int count = 0;
        for (int k = 0; k < classDumpCount; k++) {
            if (idom[classDumpIndices[k]] != UNDEFINED) count++;
        }
        return count;
    }

    void freeRpoPos()   { rpoPos   = null; }
    void freeRpoOrder() { rpoOrder = null; }
    void freeFwdCsr()   { fwdOffsets = null; fwdTargets = null; }

    /** Total number of nodes including virtual root (index 0). */
    public int objectCount() { return N; }
    /** Number of GC root objects. */
    public int gcRootCount() { return gcRootCount; }

    /**
     * Simple open-addressing hash map from int key → long value.
     * Used only for small overflow maps (< 1M entries). No boxing.
     */
    static final class LongLongMap {
        static final long NOT_FOUND = Long.MIN_VALUE;
        private static final int EMPTY = Integer.MIN_VALUE;

        private int[] keys;
        private long[] values;
        private int size;
        private int capacity;

        LongLongMap(int initialCapacity) {
            capacity = nextPow2(initialCapacity * 2);
            keys   = new int[capacity];
            values = new long[capacity];
            Arrays.fill(keys, EMPTY);
        }

        private static int nextPow2(int n) {
            int p = 1;
            while (p < n) p <<= 1;
            return p;
        }

        void put(long intKey, long value) {
            int k = (int) intKey;
            int slot = hash(k);
            while (keys[slot] != EMPTY && keys[slot] != k) slot = (slot + 1) & (capacity - 1);
            if (keys[slot] == EMPTY) {
                size++;
                if (size * 2 > capacity) { grow(); put(intKey, value); return; }
            }
            keys[slot] = k;
            values[slot] = value;
        }

        long get(long intKey) {
            int k = (int) intKey;
            int slot = hash(k);
            while (keys[slot] != EMPTY) {
                if (keys[slot] == k) return values[slot];
                slot = (slot + 1) & (capacity - 1);
            }
            return NOT_FOUND;
        }

        int size() { return size; }

        private int hash(int k) { return (k * 0x9E3779B9) & (capacity - 1); }

        private void grow() {
            int[] oldKeys = keys;
            long[] oldVals = values;
            capacity *= 2;
            keys   = new int[capacity];
            values = new long[capacity];
            Arrays.fill(keys, EMPTY);
            size = 0;
            for (int i = 0; i < oldKeys.length; i++) {
                if (oldKeys[i] != EMPTY) put(oldKeys[i], oldVals[i]);
            }
        }
    }
}
