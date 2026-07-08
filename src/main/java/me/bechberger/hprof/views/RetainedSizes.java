/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/**
 * Computes retained heap sizes using reverse-RPO accumulation.
 *
 * For each node v (in reverse RPO order), adds v's retained size to idom[v]'s
 * retained size. Since rpoPos[idom[v]] < rpoPos[v], the accumulation is correct
 * without sorting.
 *
 * After completion, {@code graph.rpoOrder[]} is freed.
 */
final class RetainedSizes {

    private RetainedSizes() {}

    static void compute(HeapGraph graph) {
        int N = graph.N;
        int[] rpoOrder = graph.rpoOrder;
        int[] idom     = graph.idom;

        // Initialise retainedSize[v] = shallowSize[v] for all v
        graph.retainedSize = new int[N];
        for (int i = 1; i < N; i++) {
            long shallow = graph.shallowSizeOf(i);
            graph.setRetainedSize(i, shallow);
        }
        // Virtual root: retained = 0 (it's synthetic)
        graph.setRetainedSize(HeapGraph.VIRTUAL_ROOT, 0);

        // Accumulate in reverse RPO order: skip virtual root (rpoOrder[0])
        for (int rpoIdx = N - 1; rpoIdx >= 1; rpoIdx--) {
            int v = rpoOrder[rpoIdx];
            if (idom[v] == HeapGraph.UNDEFINED) continue; // unreachable node
            int parent = idom[v];
            if (parent == v) continue; // virtual root self-loop

            long childRetained = graph.retainedSizeOf(v);
            long parentRetained = graph.retainedSizeOf(parent);
            graph.setRetainedSize(parent, parentRetained + childRetained);
        }

        graph.freeRpoOrder(); // rpoOrder no longer needed
    }
}
