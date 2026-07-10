/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Two-pass Compressed Sparse Row builder for the inbound reference graph.
 *
 * <p>Usage for two-pass inbound CSR:
 * <pre>
 *   // Phase A.2: count inbound degree
 *   for each edge (src→dst): builder.countEdge(dst);
 *   builder.finishCounting();
 *
 *   // Phase B: fill inbound targets (uses inboundOffsets[] as in-place cursor)
 *   for each edge (src→dst, nameIdx, srcClassIdx):
 *       builder.addEdge(src, dst, nameIdx, srcClassIdx);
 *   builder.restoreOffsets();
 *
 *   // Post Phase B (--low-memory): sort each row + VByte encode
 *   builder.encodeVByte();
 *   // After this: graph.inboundStream is set, graph.inboundOffsets updated,
 *   // and the int[] inboundTargets is freed.
 * </pre>
 *
 * <p>Also used to build the temporary forward CSR (no annotation, no VByte encoding).
 */
final class CsrBuilder {

    private final int n;
    private int[] inDegree;

    // Set on the HeapGraph directly after finishCounting
    private final HeapGraph graph;
    private final boolean isInbound; // true = inbound CSR; false = forward CSR
    private final boolean lowMemory; // if true, build BitSet excludedEdge instead of fieldName[]

    // Internal: edge-count offsets used as cursor during fill; owned by CsrBuilder not HeapGraph
    private int[] offsetsCursor;

    // Internal: inboundTargets before VByte encoding
    private int[] targets;

    // Reusable scratch buffer for sortWithFlags (rows > 16 elements)
    private long[] sortScratch = new long[0];

    /** For inbound CSR construction with VByte + BitSet. */
    CsrBuilder(HeapGraph graph, int n, int estimatedEdges) {
        this.graph = graph;
        this.n = n;
        this.isInbound = true;
        this.lowMemory = true;
        this.inDegree = new int[n];
    }

    private CsrBuilder(HeapGraph graph, int n, int estimatedEdges, boolean isInbound, boolean lowMemory) {
        this.graph = graph;
        this.n = n;
        this.isInbound = isInbound;
        this.lowMemory = lowMemory;
        this.inDegree = new int[n];
    }

    /** Phase A.2 counting pass: increment inbound degree of dst. */
    void countEdge(int dst) {
        inDegree[dst]++;
    }

    /**
     * End of counting: prefix-sum inDegree → offsets; allocate targets.
     * After this, offsetsCursor[i] = start of row i (forward CSR offsets).
     */
    void finishCounting() {
        int[] offsets = new int[n + 1];
        int total = 0;
        for (int i = 0; i < n; i++) {
            offsets[i] = total;
            total += inDegree[i];
        }
        offsets[n] = total;
        inDegree = null; // free counting array

        targets = new int[total];
        offsetsCursor = offsets;
    }

    /**
     * Phase B: add one directed edge src→dst with optional field annotation.
     * Uses offsetsCursor[dst] as a mutable cursor (incremented in-place).
     * For exclude evaluation: checks if (srcClassIdx, nameIdx) matches any default exclude pair.
     *
     * @param src        source object index
     * @param dst        destination object index
     * @param nameIdx    interned field name index (0 = no name / array element)
     * @param srcClassIdx class index of src (for exclude pair matching)
     */
    void addEdge(int src, int dst, short nameIdx, int srcClassIdx) {
        int[] offsets = offsetsCursor;
        int pos = offsets[dst];
        if (isInbound && lowMemory && isExcluded(srcClassIdx, nameIdx)) {
            // Embed exclude flag in sign bit of src value; stripped during VByte encode
            targets[pos] = src | Integer.MIN_VALUE;
        } else {
            targets[pos] = src;
        }
        offsets[dst]++;
    }

    /** For forward CSR: add edge without annotation. */
    void addForwardEdge(int[] fwdOffsets, int[] fwdTargets, int src, int dst, int cursor) {
        fwdTargets[fwdOffsets[src]++] = dst;
    }

    private boolean isExcluded(int classIdx, short nameIdx) {
        // Class meta edges (instance→classObj, classObj→superClass/classLoader) are pseudo
        // references that MAT skips during dominator tree computation.
        if (nameIdx == Short.MIN_VALUE) return true;
        if (graph.excludePairs == null || nameIdx == ClassRecord.NO_NAME) return false;
        for (int[] pair : graph.excludePairs) {
            if (pair[0] == classIdx && pair[1] == nameIdx) return true;
        }
        return false;
    }

    /**
     * After Phase B: restore offsetsCursor to proper prefix-sum form.
     * (Phase B used offsets[i] as write cursors, incrementing them; this shifts right by 1.)
     */
    void restoreOffsets() {
        int[] offsets = offsetsCursor;
        System.arraycopy(offsets, 0, offsets, 1, n);
        offsets[0] = 0;
    }

    /**
     * Variant of encodeVByte that correctly re-indexes the excludedEdge BitSet.
     * Called when exclude flags were embedded in the high bit of targets[] values.
     *
     * During addEdge, if excluded: store src | Integer.MIN_VALUE in targets[pos].
     * During encodeVByte, extract the flag from the sign bit before encoding.
     * Sets graph.inboundOffsets (int[]) and graph.inboundStream (byte[][]) directly.
     */
    void encodeVByteWithEmbeddedFlags() {
        int[] offsets = offsetsCursor;
        int totalEdges = offsets[n];

        BitSet newExcluded = new BitSet(totalEdges);
        int logicalEdgeIdx = 0;

        // Estimate stream size; small heaps use single byte[], large use chunked byte[][].
        long estimatedBytes = Math.max((long) totalEdges + totalEdges / 4L, 16L);
        int[] byteOffsets = new int[n + 1];
        long streamPos = 0;

        if (estimatedBytes < VByte.CHUNK_SIZE) {
            // Single-buffer path: allocate exactly estimatedBytes (no 512 MB chunk needed).
            byte[] singleBuf = new byte[(int) estimatedBytes];

            for (int v = 0; v < n; v++) {
                int rowStart = offsets[v];
                int rowEnd   = offsets[v + 1];
                int rowLen   = rowEnd - rowStart;
                if (rowLen > 1) {
                    if (rowLen > sortScratch.length) sortScratch = new long[rowLen];
                    sortWithFlags(targets, rowStart, rowEnd, sortScratch);
                }
                byteOffsets[v] = (int) streamPos;
                int prev = 0;
                for (int i = rowStart; i < rowEnd; i++) {
                    int rawSrc = targets[i];
                    boolean excluded = (rawSrc & Integer.MIN_VALUE) != 0;
                    if (excluded) { logicalEdgeIdx++; continue; } // skip excluded edges: don't affect dominator tree
                    int src = rawSrc & Integer.MAX_VALUE;
                    int delta = src - prev;
                    prev = src;
                    if ((int) streamPos + 8 > singleBuf.length) {
                        singleBuf = Arrays.copyOf(singleBuf, Math.min(singleBuf.length * 2, VByte.CHUNK_SIZE - 1));
                    }
                    streamPos = VByte.encode(delta, singleBuf, (int) streamPos);
                    logicalEdgeIdx++;
                }
            }
            byteOffsets[n] = (int) streamPos;
            targets = null; offsetsCursor = null;
            if ((int) streamPos < singleBuf.length) singleBuf = Arrays.copyOf(singleBuf, (int) streamPos);
            graph.inboundStream  = new byte[][] { singleBuf };
            graph.inboundOffsets = byteOffsets;
            graph.excludedEdge   = newExcluded;
            return;
        }

        // Chunked path: all chunks exactly CHUNK_SIZE for CHUNK_MASK addressing.
        int numChunks = (int) ((estimatedBytes + VByte.CHUNK_SIZE - 1) >>> VByte.CHUNK_BITS);
        if (numChunks < 1) numChunks = 1;
        byte[][] stream = new byte[numChunks][];
        for (int c = 0; c < numChunks; c++) stream[c] = new byte[VByte.CHUNK_SIZE];

        for (int v = 0; v < n; v++) {
            int rowStart = offsets[v];
            int rowEnd   = offsets[v + 1];
            int rowLen   = rowEnd - rowStart;

            if (rowLen > 1) {
                if (rowLen > sortScratch.length) sortScratch = new long[rowLen];
                sortWithFlags(targets, rowStart, rowEnd, sortScratch);
            }

            byteOffsets[v] = (int) streamPos;
            int prev = 0;

            for (int i = rowStart; i < rowEnd; i++) {
                int rawSrc = targets[i];
                boolean excluded = (rawSrc & Integer.MIN_VALUE) != 0;
                if (excluded) { logicalEdgeIdx++; continue; } // skip excluded edges
                int src = rawSrc & Integer.MAX_VALUE;
                int delta = src - prev;
                prev = src;
                int chunkIdx = (int) (streamPos >>> VByte.CHUNK_BITS);
                int chunkOff = (int) (streamPos & VByte.CHUNK_MASK);
                if (chunkOff + 8 > VByte.CHUNK_SIZE) {
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
        targets = null;
        offsetsCursor = null;

        // Trim last chunk
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

    /** Sort targets[lo..hi) by lower 31 bits (actual src index), preserving sign-bit flag.
     *  scratch must have length >= (hi - lo) for rows > 16; caller ensures this. */
    private static void sortWithFlags(int[] arr, int lo, int hi, long[] scratch) {
        int len = hi - lo;
        if (len <= 16) {
            // Insertion sort for small rows
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
            for (int i = 0; i < len; i++) {
                int raw = arr[lo + i];
                int src = raw & Integer.MAX_VALUE;
                int excl = (raw >>> 31) & 1;
                scratch[i] = ((long) src << 1) | excl;
            }
            Arrays.sort(scratch, 0, len);
            for (int i = 0; i < len; i++) {
                long p = scratch[i];
                int src  = (int) (p >>> 1);
                int excl = (int) (p & 1);
                arr[lo + i] = excl != 0 ? (src | Integer.MIN_VALUE) : src;
            }
        }
    }

    /** Get the built targets array (for forward CSR, before VByte encoding). */
    int[] getTargets() { return targets; }

    /**
     * Transfer the built forward CSR offsets out as long[], clearing local reference.
     * Converts the int[] offsetsCursor to long[] for storage in graph.inboundOffsets.
     */
    long[] takeOffsets() {
        int[] off = offsetsCursor;
        offsetsCursor = null;
        long[] longOff = new long[off.length];
        for (int i = 0; i < off.length; i++) longOff[i] = off[i];
        return longOff;
    }

    int[] takeTargets() {
        int[] t = targets;
        targets = null;
        return t;
    }
}
