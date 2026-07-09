/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Cooper-Harvey-Kennedy (2001) dominator-tree algorithm.
 *
 * Computes {@code graph.idom[]} in RPO order over the inbound CSR.
 * {@code idom[VIRTUAL_ROOT] = VIRTUAL_ROOT} (self-loop sentinel for the root).
 * All other nodes start as {@code UNDEFINED} and are filled during iteration.
 *
 * After completion, {@code graph.rpoPos[]} is freed.
 */
final class DominatorTree {

    private DominatorTree() {}

    static void compute(HeapGraph graph) {
        int N = graph.N;
        int[] rpoPos   = graph.rpoPos;   // RPO position of each node
        int[] rpoOrder = graph.rpoOrder; // node at each RPO position

        int[] idom = new int[N];
        Arrays.fill(idom, HeapGraph.UNDEFINED);
        idom[HeapGraph.VIRTUAL_ROOT] = HeapGraph.VIRTUAL_ROOT;

        // Virtual-root-adjacent set: nodes with an implicit VIRTUAL_ROOT predecessor (GC roots).
        // These must never be overwritten to a non-root dominator by CHK — the implicit edge
        // from VIRTUAL_ROOT forces intersect() to resolve to VIRTUAL_ROOT when any other
        // predecessor is present.
        BitSet vrAdjacent = new BitSet(N);
        for (int i = 0; i < graph.gcRootCount; i++) {
            int idx = graph.gcRootIds[i];
            vrAdjacent.set(idx);
            idom[idx] = HeapGraph.VIRTUAL_ROOT;
        }

        boolean changed = true;
        int iter = 0;
        while (changed) {
            changed = false;
            iter++;
            if (iter > N + 10) {
                // CHK must converge in at most N passes for a correct RPO ordering
                System.err.println("  [DOM] WARNING: no convergence after " + iter + " iterations!");
                break;
            }
            // Iterate in RPO order, skipping virtual root (index 0 = rpoOrder[0])
            for (int rpoIdx = 1; rpoIdx < N; rpoIdx++) {
                int b = rpoOrder[rpoIdx];
                // Seed with VIRTUAL_ROOT for GC roots (implicit predecessor)
                int newIdom = vrAdjacent.get(b) ? HeapGraph.VIRTUAL_ROOT : HeapGraph.UNDEFINED;

                // Iterate predecessors of b from inbound CSR
                newIdom = computeNewIdom(b, graph, idom, rpoPos, newIdom);

                if (newIdom != HeapGraph.UNDEFINED && idom[b] != newIdom) {
                    idom[b] = newIdom;
                    changed = true;
                }
            }
        }

        graph.idom = idom;
        graph.freeRpoPos(); // rpoPos no longer needed after CHK
    }

    /** Walk inbound predecessors of b and compute the new immediate dominator. */
    private static int computeNewIdom(int b, HeapGraph graph, int[] idom,
                                       int[] rpoPos, int newIdom) {
        // Virtual root's predecessors are treated separately — none in CSR
        if (b == HeapGraph.VIRTUAL_ROOT) return HeapGraph.VIRTUAL_ROOT;

        // Decode inbound VByte stream for node b
        int start = graph.inboundOffsets[b];
        int end   = graph.inboundOffsets[b + 1];
        if (start == end) return newIdom; // no stored predecessors (GC root with no references)

        byte[] stream = graph.inboundStream;
        int pos = start;
        int prev = 0;
        int[] tmp = new int[1];
        while (pos < end) {
            pos = VByte.decode(stream, pos, tmp);
            int pred = prev + tmp[0];
            prev = pred;

            // Only process if pred has been processed (idom != UNDEFINED) and reachable (rpoPos >= 0 and not in-progress)
            if (idom[pred] == HeapGraph.UNDEFINED) continue;
            if (rpoPos[pred] < 0 || rpoPos[pred] == Integer.MAX_VALUE) continue;

            if (newIdom == HeapGraph.UNDEFINED) {
                newIdom = pred;
            } else {
                newIdom = intersect(pred, newIdom, idom, rpoPos);
            }
        }
        return newIdom;
    }

    /**
     * CHK finger-walk: find the common dominator of b1 and b2.
     * Both must already have idom defined (not UNDEFINED) and rpoPos >= 0.
     * Guards against idom cycles by bounding walk depth.
     */
    private static int intersect(int b1, int b2, int[] idom, int[] rpoPos) {
        int finger1 = b1;
        int finger2 = b2;
        // Each finger can walk at most N steps upward — if it cycles, we bail to virtual root.
        int maxSteps = idom.length;
        while (finger1 != finger2) {
            int steps1 = 0;
            while (rpoPos[finger1] > rpoPos[finger2]) {
                if (++steps1 > maxSteps) { finger1 = HeapGraph.VIRTUAL_ROOT; break; }
                int next = idom[finger1];
                if (next < 0 || next == finger1) { finger1 = HeapGraph.VIRTUAL_ROOT; break; }
                finger1 = next;
            }
            int steps2 = 0;
            while (rpoPos[finger2] > rpoPos[finger1]) {
                if (++steps2 > maxSteps) { finger2 = HeapGraph.VIRTUAL_ROOT; break; }
                int next = idom[finger2];
                if (next < 0 || next == finger2) { finger2 = HeapGraph.VIRTUAL_ROOT; break; }
                finger2 = next;
            }
            // If both fingers are at virtual root, they're equal — loop exits
            if (finger1 == finger2) break;
            // If neither finger moved (equal positions but different nodes), bail
            if (rpoPos[finger1] == rpoPos[finger2] && finger1 != finger2) {
                return HeapGraph.VIRTUAL_ROOT;
            }
        }
        return finger1;
    }
}
