/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.BitSet;

/**
 * Computes retained heap sizes using reverse-RPO accumulation.
 *
 * For each node v (in reverse RPO order), adds v's retained size to idom[v]'s
 * retained size. Since rpoPos[idom[v]] < rpoPos[v], the accumulation is correct
 * without sorting.
 *
 * Also populates {@code graph.hasSameClassAncestor} — a BitSet marking objects
 * that have a strict ancestor in the dominator tree of the same class. Used by
 * the class-histogram to identify MAT-style "top ancestors" for each class.
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
            if (v == 0) continue; // zero-tail guard: skip virtual root if it appears
            if (idom[v] == HeapGraph.UNDEFINED) continue; // unreachable node
            int parent = idom[v];
            if (parent == v) continue; // virtual root self-loop

            long childRetained = graph.retainedSizeOf(v);
            long parentRetained = graph.retainedSizeOf(parent);
            graph.setRetainedSize(parent, parentRetained + childRetained);
        }

        // Forward RPO pass: populate hasSameClassAncestor.
        // Semantics: hasSameClassAncestor.get(v) == true iff some strict ancestor
        // of v in the dominator tree has the same classIndex as v.
        //
        // We cannot compute this with a single-bit recurrence — the query is
        // per-class ("does v have an ancestor of class C=classIndex[v]?"), and
        // p's answer is about class classIndex[p], not classIndex[v].
        //
        // Correct O(N) approach: iterative DFS of dominator tree, maintaining
        // a `classToLastDepth` map that records the depth of the most-recent
        // ancestor of each class. On enter(v): if classToLastDepth[cls] > 0,
        // set the bit; save the previous value, overwrite with depth[v].
        // On leave(v): restore the previous value.
        //
        // To DFS the dominator tree we build a children-CSR: for each node v,
        // enumerate {u : idom[u] == v}. Two-pass: count degrees, then fill.
        BitSet hasSameClassAncestor = new BitSet(N);
        short[] classIndex = graph.classIndex;

        int[] childDeg = new int[N];
        for (int u = 1; u < N; u++) {
            int p = idom[u];
            if (p == HeapGraph.UNDEFINED) continue;
            if (p == u) continue; // virtual root self-loop
            childDeg[p]++;
        }
        int[] childOff = new int[N + 1];
        for (int i = 0; i < N; i++) childOff[i + 1] = childOff[i] + childDeg[i];
        int[] childTargets = new int[childOff[N]];
        int[] cursor = new int[N]; // reuse childDeg after copying
        System.arraycopy(childOff, 0, cursor, 0, N);
        for (int u = 1; u < N; u++) {
            int p = idom[u];
            if (p == HeapGraph.UNDEFINED) continue;
            if (p == u) continue;
            childTargets[cursor[p]++] = u;
        }
        // cursor no longer needed
        cursor = null;
        childDeg = null;

        // Iterative DFS from virtual root. Stack entries are (node, childIter,
        // savedDepthForClass). We use parallel int stacks.
        // classToLastDepth[c] = depth of most-recent ancestor of class c (0 = none).
        int classCount = graph.classList.size();
        int[] classToLastDepth = new int[classCount + 1]; // +1 for -1 sentinel handling

        int[] stackNode = new int[N + 1];
        int[] stackChildIdx = new int[N + 1];
        int[] stackSavedDepth = new int[N + 1]; // saved classToLastDepth value to restore on pop
        int sp = 0;

        stackNode[sp] = HeapGraph.VIRTUAL_ROOT;
        stackChildIdx[sp] = childOff[HeapGraph.VIRTUAL_ROOT];
        stackSavedDepth[sp] = 0; // virtual root: no class entry
        sp++;

        while (sp > 0) {
            int top = sp - 1;
            int v = stackNode[top];
            int nextChild = stackChildIdx[top];
            int endChild = childOff[v + 1];
            if (nextChild < endChild) {
                int child = childTargets[nextChild];
                stackChildIdx[top] = nextChild + 1;

                // Enter child: check + update classToLastDepth
                short cls = classIndex[child];
                int savedDepth = 0;
                if (cls >= 0 && cls < classCount) {
                    if (classToLastDepth[cls] > 0) {
                        hasSameClassAncestor.set(child);
                    }
                    savedDepth = classToLastDepth[cls];
                    classToLastDepth[cls] = sp; // depth is current stack depth
                }
                stackNode[sp] = child;
                stackChildIdx[sp] = childOff[child];
                stackSavedDepth[sp] = savedDepth;
                sp++;
            } else {
                // Leave v: restore classToLastDepth
                short cls = (v == HeapGraph.VIRTUAL_ROOT) ? -1 : classIndex[v];
                if (cls >= 0 && cls < classCount) {
                    classToLastDepth[cls] = stackSavedDepth[top];
                }
                sp--;
            }
        }
        graph.hasSameClassAncestor = hasSameClassAncestor;

        graph.freeRpoOrder(); // rpoOrder no longer needed
    }
}
