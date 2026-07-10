/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Computes retained heap sizes and {@code hasSameClassAncestor} in a single
 * post-order DFS over the dominator tree.
 *
 * Both retained-size accumulation (child → parent) and hasSameClassAncestor
 * detection are handled in one iterative DFS pass over the children-CSR,
 * eliminating the need for {@code graph.rpoOrder} (which was freed before
 * DominatorTree Phase 1 to reduce peak RSS).
 *
 * Retained sizes: on leave(v), add v's retained size to idom[v]'s retained size.
 * Since DFS post-order guarantees all children are processed before their parent,
 * the accumulation is correct.
 *
 * hasSameClassAncestor: on enter(v), check and update classToLastDepth/classObjDepth;
 * on leave(v), restore saved values.
 */
final class RetainedSizes {

    private RetainedSizes() {}

    static void compute(HeapGraph graph) {
        int N = graph.N;
        int[] idom     = graph.idom;

        // Initialise retainedSize[v] = shallowSize[v] for all v.
        // Take donated int[N] from the phase donation chain (donated by DominatorTree as depth[]),
        // pre-zeroed by take(). Falls back to fresh allocation if nothing was donated.
        graph.retainedSize = graph.phaseArrays.take();
        for (int i = 1; i < N; i++) {
            graph.setRetainedSize(i, graph.shallowSizeOf(i));
        }
        // Virtual root: retained = 0 (it's synthetic)
        graph.setRetainedSize(HeapGraph.VIRTUAL_ROOT, 0);

        // Build children-CSR: childOff[v+1] - childOff[v] = number of domtree children of v.
        // childTargets[childOff[v] .. childOff[v+1]-1] = domtree children of v.
        // Two-pass construction: count degrees, then fill targets.
        BitSet hasSameClassAncestor = new BitSet(N);
        int[] classIndex = graph.classIndex;
        int[] classObjClassIdx = graph.classObjClassIdx;

        int[] childDeg = graph.phaseArrays != null ? graph.phaseArrays.take() : new int[N];
        for (int u = 1; u < N; u++) {
            int p = idom[u];
            if (p == HeapGraph.UNDEFINED) continue;
            if (p == u) continue; // virtual root self-loop
            childDeg[p]++;
        }
        int[] childOff = new int[N + 1];
        for (int i = 0; i < N; i++) childOff[i + 1] = childOff[i] + childDeg[i];
        int[] childTargets = new int[childOff[N]];
        if (graph.phaseArrays != null) graph.phaseArrays.donate(childDeg);
        childDeg = null;
        int[] cursor = graph.phaseArrays != null ? graph.phaseArrays.takeRaw() : new int[N];
        System.arraycopy(childOff, 0, cursor, 0, N);
        for (int u = 1; u < N; u++) {
            int p = idom[u];
            if (p == HeapGraph.UNDEFINED) continue;
            if (p == u) continue;
            childTargets[cursor[p]++] = u;
        }
        if (graph.phaseArrays != null) graph.phaseArrays.donate(cursor);
        cursor = null;

        // Single DFS pass: post-order retained-size accumulation + hasSameClassAncestor.
        // On enter(v): update classToLastDepth/classObjDepth, record saved values.
        // On leave(v): restore saved values; add v's retained size to idom[v].
        int classCount = graph.classList.size();
        int[] classToLastDepth = new int[classCount + 1];
        int[] classObjDepth    = new int[classCount + 1];

        int stackCap = Math.min(N + 1, 4096);
        int[] stackNode          = new int[stackCap];
        int[] stackChildIdx      = new int[stackCap];
        int[] stackSavedDepth    = new int[stackCap];
        int[] stackSavedObjDepth = new int[stackCap];
        int sp = 0;

        stackNode[sp] = HeapGraph.VIRTUAL_ROOT;
        stackChildIdx[sp] = childOff[HeapGraph.VIRTUAL_ROOT];
        stackSavedDepth[sp] = 0;
        stackSavedObjDepth[sp] = 0;
        sp++;

        while (sp > 0) {
            int top = sp - 1;
            int v = stackNode[top];
            int nextChild = stackChildIdx[top];
            int endChild = childOff[v + 1];
            if (nextChild < endChild) {
                int child = childTargets[nextChild];
                stackChildIdx[top] = nextChild + 1;

                // Enter child: check + update classToLastDepth and classObjDepth
                int cls = classIndex[child];
                int savedDepth = 0;
                int savedObjDepth = 0;
                if (cls >= 0 && cls < classCount) {
                    if (classToLastDepth[cls] > 0 || classObjDepth[cls] > 0) {
                        hasSameClassAncestor.set(child);
                    }
                    savedDepth = classToLastDepth[cls];
                    classToLastDepth[cls] = sp;
                }
                int ci = (classObjClassIdx != null && child < classObjClassIdx.length)
                        ? classObjClassIdx[child] : -1;
                if (ci >= 0 && ci < classCount) {
                    savedObjDepth = classObjDepth[ci];
                    classObjDepth[ci] = sp;
                }
                stackNode[sp] = child;
                stackChildIdx[sp] = childOff[child];
                stackSavedDepth[sp] = savedDepth;
                stackSavedObjDepth[sp] = savedObjDepth;
                sp++;
                if (sp == stackNode.length) {
                    int newCap = sp * 2;
                    stackNode          = Arrays.copyOf(stackNode,          newCap);
                    stackChildIdx      = Arrays.copyOf(stackChildIdx,      newCap);
                    stackSavedDepth    = Arrays.copyOf(stackSavedDepth,    newCap);
                    stackSavedObjDepth = Arrays.copyOf(stackSavedObjDepth, newCap);
                }
            } else {
                // Leave v: restore classToLastDepth/classObjDepth; accumulate retained size.
                int cls = (v == HeapGraph.VIRTUAL_ROOT) ? -1 : classIndex[v];
                if (cls >= 0 && cls < classCount) {
                    classToLastDepth[cls] = stackSavedDepth[top];
                }
                int ci = (classObjClassIdx != null && v < classObjClassIdx.length && v != HeapGraph.VIRTUAL_ROOT)
                        ? classObjClassIdx[v] : -1;
                if (ci >= 0 && ci < classCount) {
                    classObjDepth[ci] = stackSavedObjDepth[top];
                }
                // Accumulate retained size into parent (post-order → all children processed).
                if (v != HeapGraph.VIRTUAL_ROOT) {
                    int parent = idom[v];
                    if (parent != HeapGraph.UNDEFINED && parent != v) {
                        long childRetained = graph.retainedSizeOf(v);
                        long parentRetained = graph.retainedSizeOf(parent);
                        graph.setRetainedSize(parent, parentRetained + childRetained);
                    }
                }
                sp--;
            }
        }
        graph.hasSameClassAncestor = hasSameClassAncestor;

        childTargets = null;
        if (graph.phaseArrays != null) graph.phaseArrays.donate(childOff);
        childOff = null;
    }
}
