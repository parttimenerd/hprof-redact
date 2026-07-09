/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
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

        // Initialise retainedSize[v] = shallowSize[v] for all v.
        // Reuse scratchIntN (donated by freeRpoPos — the old dfsPos array) if available,
        // avoiding a fresh N-element allocation.
        // Take donated int[N] from the phase donation chain (donated by DominatorTree as depth[]),
        // pre-zeroed by take(). Falls back to fresh allocation if nothing was donated.
        graph.retainedSize = graph.phaseArrays.take();
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
        // Semantics: hasSameClassAncestor.get(v) == true iff either:
        //   (a) some strict ancestor of v in the dominator tree has the same classIndex as v, OR
        //   (b) the class-object for class(v) is a strict ancestor of v.
        // This matches MAT's getTopAncestorsInDominatorTree semantics: for each class C,
        // getMinRetainedSize([classObject(C), allInstances(C)]) treats classObject(C) as dominating
        // any instance v of C that it strictly dominates in the dominator tree.
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
        // Additionally maintain `classObjDepth[cls]`: the DFS depth at which classObject(cls)
        // is currently on the stack. When entering v of class C, also check classObjDepth[C] > 0.
        //
        // To DFS the dominator tree we build a children-CSR: for each node v,
        // enumerate {u : idom[u] == v}. Two-pass: count degrees, then fill.
        BitSet hasSameClassAncestor = new BitSet(N);
        short[] classIndex = graph.classIndex;
        short[] classObjClassIdx = graph.classObjClassIdx; // node → classList index it represents; -1 if not class-obj

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
        // cursor no longer needed; donate for potential reuse, then childDeg
        if (graph.phaseArrays != null) graph.phaseArrays.donate(cursor);
        cursor = null;
        if (graph.phaseArrays != null) graph.phaseArrays.donate(childDeg);
        childDeg = null;

        // Iterative DFS from virtual root. Stack entries are (node, childIter,
        // savedDepthForClass, savedClassObjDepth). We use parallel int stacks.
        // classToLastDepth[c] = depth of most-recent ancestor of class c (0 = none).
        // classObjDepth[c] = depth at which classObject(c) was entered (0 = not on stack).
        int classCount = graph.classList.size();
        int[] classToLastDepth = new int[classCount + 1]; // +1 for -1 sentinel handling
        int[] classObjDepth    = new int[classCount + 1]; // depth of classObject(c) on stack (0 = none)

        // Stack arrays: start small and grow on demand. Dominator-tree depth is typically
        // much less than N (e.g., <1000 for most JVM heaps). Avoids allocating 4×N ints
        // up-front, which wastes tens of MB for large heaps.
        int stackCap = Math.min(N + 1, 4096);
        int[] stackNode        = new int[stackCap];
        int[] stackChildIdx    = new int[stackCap];
        int[] stackSavedDepth  = new int[stackCap];
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
                short cls = classIndex[child];
                int savedDepth = 0;
                int savedObjDepth = 0;
                if (cls >= 0 && cls < classCount) {
                    // Mark if same-class ancestor or classObject for this class is on path
                    if (classToLastDepth[cls] > 0 || classObjDepth[cls] > 0) {
                        hasSameClassAncestor.set(child);
                    }
                    savedDepth = classToLastDepth[cls];
                    classToLastDepth[cls] = sp;
                }
                // If this node is a class object for some class ci, record that in classObjDepth
                short ci = (classObjClassIdx != null && child < classObjClassIdx.length)
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
                    stackNode        = Arrays.copyOf(stackNode,        newCap);
                    stackChildIdx    = Arrays.copyOf(stackChildIdx,    newCap);
                    stackSavedDepth  = Arrays.copyOf(stackSavedDepth,  newCap);
                    stackSavedObjDepth = Arrays.copyOf(stackSavedObjDepth, newCap);
                }
            } else {
                // Leave v: restore classToLastDepth and classObjDepth
                short cls = (v == HeapGraph.VIRTUAL_ROOT) ? -1 : classIndex[v];
                if (cls >= 0 && cls < classCount) {
                    classToLastDepth[cls] = stackSavedDepth[top];
                }
                short ci = (classObjClassIdx != null && v < classObjClassIdx.length && v != HeapGraph.VIRTUAL_ROOT)
                        ? classObjClassIdx[v] : -1;
                if (ci >= 0 && ci < classCount) {
                    classObjDepth[ci] = stackSavedObjDepth[top];
                }
                sp--;
            }
        }
        graph.hasSameClassAncestor = hasSameClassAncestor;

        // childOff and childTargets are dead after DFS; donate childOff (length N+1 >= N, accepted)
        if (graph.phaseArrays != null) graph.phaseArrays.donate(childOff);
        childOff = null;

        // classObjClassIdx only used here; classIndex still needed by report writers
        graph.classObjClassIdx = null;
        // Donate rpoOrder to phaseArrays for reuse by any subsequent int[N] consumer.
        if (graph.phaseArrays != null) graph.phaseArrays.donate(graph.rpoOrder);
        graph.freeRpoOrder(); // null graph.rpoOrder
    }
}