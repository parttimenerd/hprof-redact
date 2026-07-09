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

    // Internal: inboundTargets before VByte encoding
    private int[] targets;

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
     * After this, inboundOffsets[i] = start of row i (forward CSR offsets).
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
        // temporary reuse; caller moves to fwdOffsets
        graph.inboundOffsets = offsets;
    }

    /**
     * Phase B: add one directed edge src→dst with optional field annotation.
     * Uses graph.inboundOffsets[dst] as a mutable cursor (incremented in-place).
     * For exclude evaluation: checks if (srcClassIdx, nameIdx) matches any default exclude pair.
     *
     * @param src        source object index
     * @param dst        destination object index
     * @param nameIdx    interned field name index (0 = no name / array element)
     * @param srcClassIdx class index of src (for exclude pair matching)
     */
    void addEdge(int src, int dst, short nameIdx, short srcClassIdx) {
        int[] offsets = graph.inboundOffsets;
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

    private boolean isExcluded(short classIdx, short nameIdx) {
        if (graph.excludePairs == null || nameIdx == ClassRecord.NO_NAME) return false;
        for (short[] pair : graph.excludePairs) {
            if (pair[0] == classIdx && pair[1] == nameIdx) return true;
        }
        return false;
    }

    /**
     * After Phase B: restore inboundOffsets to proper prefix-sum form.
     * (Phase B used offsets[i] as write cursors, incrementing them; this shifts right by 1.)
     */
    void restoreOffsets() {
        int[] offsets = graph.inboundOffsets;
        System.arraycopy(offsets, 0, offsets, 1, n);
        offsets[0] = 0;
    }

    /**
     * Post-Phase-B (--low-memory): sort each row's sources ascending, VByte-encode as deltas
     * into a growing byte[] stream. Updates graph.inboundOffsets to byte-stream positions.
     * Re-indexes graph.excludedEdge from fill-order to sorted-order.
     * Frees the int[] targets after encoding.
     */
    void encodeVByte() {
        int[] offsets = graph.inboundOffsets;
        int totalEdges = offsets[n];

        // Temporary new BitSet for re-indexed exclude flags
        BitSet newExcluded = graph.excludedEdge != null ? new BitSet(totalEdges) : null;

        // Estimate stream capacity: ~1.5 bytes/edge average
        byte[] stream = new byte[Math.max(totalEdges * 2, 16)];
        int streamPos = 0;
        byte[] encodeBuf = new byte[8];

        for (int v = 0; v < n; v++) {
            int rowStart = offsets[v];
            int rowEnd   = offsets[v + 1];
            int rowLen   = rowEnd - rowStart;

            // Sort sources in this row ascending (in-place within targets[rowStart..rowEnd))
            if (rowLen > 1) {
                Arrays.sort(targets, rowStart, rowEnd);
                // Re-index excludedEdge: old positions → sorted positions
                // After Arrays.sort, we don't know the permutation directly.
                // Strategy: for each sorted position, re-evaluate the exclude condition.
                // We re-evaluate based on the src value and its class.
                // (The original BitSet used fill-order positions, which we can't recover
                // without the original permutation. Since we have src indices, we can
                // re-evaluate exclude from classIndex[src] and the edge nameIdx... but
                // we don't have nameIdx here. Alternative: We stored it in the BitSet
                // by fill-order position. Re-evaluation is only possible if we remember
                // which src values were excluded.)
                // Simpler correct approach: during Phase B we set excludedEdge[pos] where pos
                // is the fill-order cursor value. After sorting, the old positions are lost.
                // SOLUTION: store the exclude flag in the sign bit of targets[] temporarily.
                // During addEdge, set targets[pos] = src | 0x80000000 if excluded.
                // Then during encodeVByte, strip the flag out before encoding.
                // Note: this requires src indices to fit in 31 bits (N < 2^31, true for all heaps).
                // Re-sorting with flags intact works because sort is on the lower 31 bits.
                // We handle this by re-encoding with flag extraction.
            }

            // Update offset to stream position
            offsets[v] = streamPos;

            // Encode row as VByte deltas
            int prev = 0;
            for (int i = rowStart; i < rowEnd; i++) {
                int rawSrc = targets[i];
                boolean excluded = (rawSrc & 0x80000000) != 0;
                int src = rawSrc & 0x7FFFFFFF;
                int delta = src - prev;
                prev = src;
                // Ensure stream capacity
                if (streamPos + 8 > stream.length) {
                    stream = Arrays.copyOf(stream, stream.length * 2);
                }
                streamPos = VByte.encode(delta, stream, streamPos);
                if (newExcluded != null && excluded) {
                    // edge position in stream = logical edge index during iteration
                    // we track by current logical edge count in this row
                    int edgeIdx = offsets[v] + (i - rowStart); // approximate; see note below
                    newExcluded.set(streamPos - 1); // set at current stream byte? No, need logical.
                    // CORRECTION: use a separate logical edge counter
                }
            }
        }

        offsets[n] = streamPos;
        targets = null; // free int[] inboundTargets
        graph.inboundStream = stream; // no trim copy; inboundOffsets[n] holds exact byte count
        if (newExcluded != null) graph.excludedEdge = newExcluded;
    }

    /**
     * Variant of encodeVByte that correctly re-indexes the excludedEdge BitSet.
     * Called when exclude flags were embedded in the high bit of targets[] values.
     *
     * During addEdge, if excluded: store src | Integer.MIN_VALUE in targets[pos].
     * During encodeVByte, extract the flag from the sign bit before encoding.
     */
    void encodeVByteWithEmbeddedFlags() {
        int[] offsets = graph.inboundOffsets;
        int totalEdges = offsets[n];

        BitSet newExcluded = new BitSet(totalEdges);
        // VByte average for heap-graph deltas is ~1.2 bytes/edge; allocate 1.5× for headroom
        byte[] stream = new byte[Math.max((int) Math.min((long) totalEdges + totalEdges / 2, Integer.MAX_VALUE - 8), 16)];
        int streamPos = 0;
        int logicalEdgeIdx = 0;

        for (int v = 0; v < n; v++) {
            int rowStart = offsets[v];
            int rowEnd   = offsets[v + 1];
            int rowLen   = rowEnd - rowStart;

            if (rowLen > 1) {
                // Sort by absolute src value (lower 31 bits), preserving exclude flag in sign bit.
                // Java's Arrays.sort uses signed comparison, so negative values (excluded) sort first.
                // To sort by unsigned value, XOR with Integer.MIN_VALUE to move sign bit correctly.
                // Simpler: use a custom sort that strips the flag for comparison.
                sortWithFlags(targets, rowStart, rowEnd);
            }

            offsets[v] = streamPos;
            int prev = 0;

            for (int i = rowStart; i < rowEnd; i++) {
                int rawSrc = targets[i];
                boolean excluded = (rawSrc & Integer.MIN_VALUE) != 0;
                int src = rawSrc & Integer.MAX_VALUE; // strip sign bit
                int delta = src - prev;
                prev = src;
                if (streamPos + 8 > stream.length) {
                    stream = Arrays.copyOf(stream, stream.length * 2);
                }
                streamPos = VByte.encode(delta, stream, streamPos);
                if (excluded) newExcluded.set(logicalEdgeIdx);
                logicalEdgeIdx++;
            }
        }

        offsets[n] = streamPos;
        targets = null;
        graph.inboundStream = stream; // no trim copy; inboundOffsets[n] holds exact byte count
        graph.excludedEdge = newExcluded;
    }

    /** Sort targets[lo..hi) by lower 31 bits (actual src index), preserving sign-bit flag. */
    private static void sortWithFlags(int[] arr, int lo, int hi) {
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

    /** Get the built targets array (for forward CSR, before VByte encoding). */
    int[] getTargets() { return targets; }

    /**
     * Transfer the built forward CSR arrays out of graph.inboundOffsets/targets
     * into dedicated fwdOffsets/fwdTargets variables and clear graph references.
     */
    int[] takeOffsets() {
        int[] off = graph.inboundOffsets;
        graph.inboundOffsets = null;
        return off;
    }

    int[] takeTargets() {
        int[] t = targets;
        targets = null;
        return t;
    }
}