/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Computes the Reverse-Post-Order (RPO) traversal of the heap graph,
 * starting from the virtual root (index 0) via GC root edges, using an
 * explicit stack to avoid JVM stack overflow on deep heap paths.
 *
 * After completion:
 * <ul>
 *   <li>{@code graph.rpoOrder[i]} = node at RPO position i</li>
 *   <li>{@code graph.dfsPos[v]} = DFS pre-order visit number (0 = virtual root)</li>
 *   <li>{@code graph.dfsOrder[i]} = node at DFS pre-order position i</li>
 *   <li>{@code graph.dfsParent[v]} = DFS spanning-tree parent of v (-1 = virtual root has no parent)</li>
 * </ul>
 *
 * Frees {@code graph.fwdOffsets} and {@code graph.fwdTargets} after the DFS.
 * For large heaps, {@code fwdTargets} chunks are freed progressively as the DFS
 * finishes with each chunk (refcount-based), reducing peak RSS during this phase.
 *
 * Memory note: visited tracking uses a {@code BitSet(N)} (~64 MB for 514M nodes) instead of
 * {@code int[N]} (~2 GB) during the DFS traversal. {@code dfsPos[]} is reconstructed from
 * {@code dfsOrder[]} in O(N) after the DFS completes, when {@code fwdTargets} has been freed.
 *
 * Note: rpoPos[] is NOT produced here. Reachability is determined via dfsPos[v] >= 0.
 */
final class RpoDfs {

    private RpoDfs() {}

    static void compute(HeapGraph graph) {
        int N = graph.N;
        int[] fwdOffsets = graph.fwdOffsets;
        int[][] fwdTargets = graph.fwdTargets;

        int[] rpoOrder = graph.phaseArrays.takeRaw(); // donated by A2; avoids fresh int[N]

        // Use a BitSet for visited tracking during DFS (~64 MB for 514M nodes) instead of
        // int[N] (~2 GB). dfsPos[N] is reconstructed after freeFwdCsr() drops fwdTargets.
        BitSet visited = new BitSet(N);
        int[] dfsOrder  = new int[N];
        int[] dfsParent = new int[N];
        Arrays.fill(dfsParent, -1);

        // Explicit DFS stack: parallel arrays for node and cursor
        int stackCap = Math.min(N, 1 << 16);
        int[] nodeStack   = new int[stackCap];
        int[] cursorStack = new int[stackCap];
        int top = -1;

        int rpoIdx   = N;
        int dfsCount = 0;

        visited.set(HeapGraph.VIRTUAL_ROOT);
        dfsOrder[dfsCount++]              = HeapGraph.VIRTUAL_ROOT;
        dfsParent[HeapGraph.VIRTUAL_ROOT] = -1;
        top++;
        nodeStack[top]   = HeapGraph.VIRTUAL_ROOT;
        cursorStack[top] = 0;

        // Per-chunk refcount: how many nodes have at least one forward edge in each chunk.
        // When a node is popped (post-order), its chunk refcounts are decremented.
        // A chunk is freed when its refcount reaches zero.
        // Only used for large heaps where fwdTargets has multiple chunks.
        int[] chunkRefs = null;
        if (fwdTargets != null && fwdTargets.length > 1) {
            chunkRefs = new int[fwdTargets.length];
            for (int v = 1; v < N; v++) { // skip VIRTUAL_ROOT (its edges are gcRootIds)
                int lo = fwdOffsets[v];
                int hi = fwdOffsets[v + 1];
                if (lo >= hi) continue;
                int loChunk = lo  >>> HeapGraph.TARGETS_CHUNK_BITS;
                int hiChunk = (hi - 1) >>> HeapGraph.TARGETS_CHUNK_BITS;
                // Increment refcount for each chunk this node's edge range touches.
                // Most nodes touch exactly one chunk; spanning two is rare.
                for (int c = loChunk; c <= hiChunk; c++) chunkRefs[c]++;
            }
        }

        while (top >= 0) {
            int node   = nodeStack[top];
            int cursor = cursorStack[top];

            boolean pushed = false;
            int childCount = childCount(node, graph, fwdOffsets);
            while (cursor < childCount) {
                int child = getChild(node, cursor, graph, fwdOffsets, fwdTargets);
                cursor++;
                if (child < 0 || child >= N) continue;
                if (!visited.get(child)) { // not yet visited
                    visited.set(child);
                    dfsOrder[dfsCount++] = child;
                    dfsParent[child]     = node;
                    cursorStack[top] = cursor;
                    top++;
                    if (top == nodeStack.length) {
                        nodeStack   = Arrays.copyOf(nodeStack, top * 2);
                        cursorStack = Arrays.copyOf(cursorStack, top * 2);
                    }
                    nodeStack[top]   = child;
                    cursorStack[top] = 0;
                    pushed = true;
                    break;
                }
            }
            if (!pushed) {
                cursorStack[top] = cursor;
                rpoOrder[--rpoIdx] = node;
                top--;
                // Decrement chunk refcounts for the popped node's edge range.
                // Free any chunk whose refcount just hit zero.
                if (chunkRefs != null && node != HeapGraph.VIRTUAL_ROOT) {
                    int lo = fwdOffsets[node];
                    int hi = fwdOffsets[node + 1];
                    if (lo < hi) {
                        int loChunk = lo  >>> HeapGraph.TARGETS_CHUNK_BITS;
                        int hiChunk = (hi - 1) >>> HeapGraph.TARGETS_CHUNK_BITS;
                        for (int c = loChunk; c <= hiChunk; c++) {
                            if (--chunkRefs[c] == 0) fwdTargets[c] = null;
                        }
                    }
                }
            }
        }

        // RPO entries are in rpoOrder[rpoIdx..N-1]; shift to rpoOrder[0..postCount-1].
        int postCount = N - rpoIdx;
        System.arraycopy(rpoOrder, rpoIdx, rpoOrder, 0, postCount);

        // Free fwdTargets and fwdOffsets before allocating dfsPos[N] to reduce peak RSS.
        // Null the local references too so G1 can collect the arrays immediately.
        graph.freeFwdCsr();
        fwdOffsets = null;
        fwdTargets = null;

        // Reconstruct dfsPos[N] (node → DFS pre-order position) from dfsOrder[].
        // dfsPos[v] = -1 for unreachable nodes (defaults to -1 from Arrays.fill).
        int[] dfsPos = new int[N];
        Arrays.fill(dfsPos, -1);
        for (int d = 0; d < dfsCount; d++) {
            dfsPos[dfsOrder[d]] = d;
        }

        graph.rpoOrder     = rpoOrder;
        graph.rpoReachable = postCount;
        graph.dfsPos    = dfsPos;
        graph.dfsOrder  = dfsOrder;
        graph.dfsParent = dfsParent;
    }

    private static int childCount(int node, HeapGraph graph, int[] fwdOffsets) {
        if (node == HeapGraph.VIRTUAL_ROOT) return graph.gcRootCount;
        if (fwdOffsets == null || node >= fwdOffsets.length - 1) return 0;
        return fwdOffsets[node + 1] - fwdOffsets[node];
    }

    private static int getChild(int node, int cursor, HeapGraph graph,
                                 int[] fwdOffsets, int[][] fwdTargets) {
        if (node == HeapGraph.VIRTUAL_ROOT) {
            return cursor < graph.gcRootCount ? graph.gcRootIds[cursor] : -1;
        }
        if (fwdOffsets == null || fwdTargets == null) return -1;
        int start = fwdOffsets[node];
        int idx   = start + cursor;
        if (idx < fwdOffsets[node + 1]) {
            int[] chunk = fwdTargets[idx >>> HeapGraph.TARGETS_CHUNK_BITS];
            return chunk != null ? chunk[idx & HeapGraph.TARGETS_CHUNK_MASK] : -1;
        }
        return -1;
    }
}

