/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Lengauer-Tarjan "simple" dominator-tree algorithm — O(N α(N)).
 *
 * Uses the DFS spanning-tree (pre-order) produced by RpoDfs plus a path-compressed
 * union-find to compute semi-dominators, then derives immediate dominators.
 * Handles arbitrarily deep dominator chains (e.g. Scala cons-lists of depth 10,000+)
 * without the O(depth) per-step pathology of the CHK finger-walk.
 *
 * Reference: Lengauer & Tarjan (1979); "simple" variant (path compression without
 * link-cut trees) — see also Aho, Hopcroft & Ullman (1986).
 *
 * Input:  graph.dfsPos, graph.dfsOrder, graph.dfsParent, graph.inbound* CSR,
 *         graph.gcRootIds. Unreachable nodes are those with dfsPos[v] < 0.
 * Output: graph.idom[] where idom[VIRTUAL_ROOT] = VIRTUAL_ROOT (self-loop sentinel).
 *         Unreachable nodes keep idom[v] = UNDEFINED.
 *
 * After completion, graph.dfsPos[], graph.dfsOrder[],
 * graph.dfsParent[] are all freed via graph.freeRpoPos().
 */
final class DominatorTree {

    private DominatorTree() {}

    static void compute(HeapGraph graph) {
        int N         = graph.N;
        int[] dfsPos  = graph.dfsPos;   // DFS pre-order number of each node (-1 = unreachable)
        int[] dfsOrd  = graph.dfsOrder; // node at each DFS pre-order position
        int[] dfsPar  = graph.dfsParent;// DFS spanning-tree parent (node idx; -1 for VRoot)

        // Count reachable nodes (those with valid DFS pre-order position).
        int reachable = 0;
        for (int i = 0; i < N; i++) {
            if (dfsPos[i] >= 0) reachable++;
        }

        // ----------------------------------------------------------------
        // All arrays are indexed by DFS pre-order position d (0 = VIRTUAL_ROOT).
        // dfsOrd[d] = node index for DFS position d.
        // dfsPos[v] = DFS position of node v (-1 = unreachable).
        // dfsPar[v] = DFS spanning-tree parent node (-1 = no parent = virtual root).
        //
        // In LT, dfsPar gives the DFS tree structure.  parent[d] = dfsPos[dfsPar[dfsOrd[d]]].
        // ----------------------------------------------------------------

        // Lengauer-Tarjan arrays indexed by DFS pre-order position d (0..reachable-1).
        // parent[] is eliminated: computed inline as parentOf(d) = dfsPos[dfsPar[dfsOrd[d]]].
        // dfsPar and dfsOrd are already live in graph.dfsParent and graph.dfsOrder during DOM,
        // so the inline computation reuses existing arrays and saves one int[reachable] ≈ 43 MB.
        int[] sdom     = new int[reachable]; // semi-dominator DFS-position
        int[] idomD    = new int[reachable]; // immediate dominator DFS-position (output); -1 = unset
        int[] label    = new int[reachable]; // union-find: min-sdom node on path to forest root
        int[] ancestor = new int[reachable]; // union-find parent DFS-position; -1 = forest root
        int[] bucket   = new int[reachable]; // head of bucket list for each semi-dom; -1 = empty
        int[] next     = new int[reachable]; // linked list next in bucket; -1 = end

        Arrays.fill(idomD,    -1);
        Arrays.fill(ancestor, -1);
        Arrays.fill(bucket,   -1);
        Arrays.fill(next,     -1);
        for (int d = 0; d < reachable; d++) {
            label[d] = d;
            sdom[d]  = d; // initial: sdom is itself (identity)
        }

        // Nodes with implicit VIRTUAL_ROOT predecessor (GC roots)
        BitSet vrAdjacent = new BitSet(N);
        for (int i = 0; i < graph.gcRootCount;  i++) vrAdjacent.set(graph.gcRootIds[i]);

        // ----------------------------------------------------------------
        // Main LT loop: process in REVERSE DFS pre-order (d = reachable-1 down to 1).
        // parentOf(d) = dfsPos[dfsPar[dfsOrd[d]]] (inline; avoids a separate int[reachable] array)
        // ----------------------------------------------------------------
        int[] tmp = new int[1]; // reused across iterations for VByte decode
        for (int d = reachable - 1; d >= 1; d--) {
            int v = dfsOrd[d];

            // Inline parent computation: DFS-tree parent of node at DFS-pos d.
            int par;
            {
                int parNode = dfsPar[v]; // DFS spanning-tree parent node index; -1 = no parent
                par = (parNode < 0) ? 0 : dfsPos[parNode];
            }

            // --- (a) Compute sdom[d] ---
            // Start from DFS-tree parent as upper bound
            int minSdom = par;

            // Implicit VRoot predecessor for GC roots
            if (vrAdjacent.get(v) && minSdom > 0) minSdom = 0;

            // Scan actual inbound predecessors
            long start = graph.inboundOffsets[v];
            long end   = graph.inboundOffsets[v + 1];
            byte[][] stream = graph.inboundStream;
            long pos = start;
            int prev = 0;
            while (pos < end) {
                pos = VByte.decode(stream, pos, tmp);
                int pred  = prev + tmp[0];
                prev = pred;
                int predD = dfsPos[pred];
                if (predD < 0) continue; // unreachable predecessor

                int cand;
                if (predD < d) {
                    // pred is a proper ancestor in DFS (tree edge, back edge, or forward edge
                    // from ancestor-side): semi-dominator candidate is predD directly.
                    cand = predD;
                } else {
                    // pred has higher DFS# than v: pred is a descendant or cross-node.
                    // Use eval(predD) to get the min-sdom representative on the forest path.
                    cand = sdom[eval(predD, ancestor, label, sdom)];
                }
                if (cand < minSdom) minSdom = cand;
            }
            sdom[d] = minSdom;

            // --- (b) Link d into spanning forest: ancestor[d] = par ---
            ancestor[d] = par;

            // --- (c) Add d to bucket of its semi-dominator ---
            next[d]         = bucket[sdom[d]];
            bucket[sdom[d]] = d;

            // --- (d) Process bucket of par ---
            // For each w in bucket[par]:
            //   u = eval(w); idomD[w] = (sdom[u] < sdom[w]) ? u : par
            int w = bucket[par];
            while (w != -1) {
                int wNext = next[w];
                int u = eval(w, ancestor, label, sdom);
                idomD[w] = (sdom[u] < sdom[w]) ? u : par;
                w = wNext;
            }
            bucket[par] = -1; // clear processed bucket
        }

        // label[], ancestor[], bucket[], next[] are all dead after the main loop.
        // Null them explicitly to release ~4×43 MB before step 4 and the idom translation.
        label    = null;
        ancestor = null;
        bucket   = null;
        next     = null;

        // ----------------------------------------------------------------
        // Step 4: deferred idom adjustment in forward DFS order.
        // idomD[0] = 0 (virtual root is its own idom sentinel).
        // For d=1..reachable-1: if idomD[d] != sdom[d], then idomD[d] = idomD[idomD[d]].
        // ----------------------------------------------------------------
        idomD[0] = 0;
        for (int d = 1; d < reachable; d++) {
            if (idomD[d] == -1) {
                // Bucket of sdom[d] was never processed (sdom[d] is a DFS leaf with no
                // children whose parent = sdom[d]).  This happens when the semi-dominator
                // itself was processed after all its children but its bucket was never
                // drained.  Fall back: idomD[d] = sdom[d], which will be adjusted in step 4
                // if needed.
                idomD[d] = sdom[d];
            }
            if (idomD[d] != sdom[d]) {
                idomD[d] = idomD[idomD[d]];
            }
        }

        // ----------------------------------------------------------------
        // Translate DFS-position idom back to node-index space.
        // Reuse dfsPos (no longer needed after this point) as the idom array,
        // saving one N-element int[] allocation. Requires full fill to UNDEFINED first.
        int[] idom = dfsPos; // dfsPos is int[N], same size needed for idom
        dfsPos = null;       // drop our local reference so it's clear we've handed it over
        Arrays.fill(idom, HeapGraph.UNDEFINED);
        idom[HeapGraph.VIRTUAL_ROOT] = HeapGraph.VIRTUAL_ROOT;

        for (int d = 1; d < reachable; d++) {
            int v    = dfsOrd[d];
            int domD = idomD[d];
            idom[v]  = (domD >= 0 && domD < reachable) ? dfsOrd[domD] : HeapGraph.VIRTUAL_ROOT;
        }

        graph.idom = idom;
        graph.dfsPos = null; // already repurposed as idom — freeRpoPos must not re-stash it

        // sdom[] and idomD[] are dead after translation — release before depth pass.
        sdom  = null;
        idomD = null;

        // Take dfsParent directly as depth[] before freeRpoPos nulls it.
        // dfsParent is int[N], same size — reuse avoids a fresh allocation.
        int[] depth = graph.dfsParent;
        graph.dfsParent = null;         // prevent freeRpoPos from nulling it after we've taken it
        java.util.Arrays.fill(depth, 0); // zero former DFS parent indices

        graph.freeRpoPos(); // donates dfsOrder to phaseArrays; nulls dfsPos (null), dfsOrder, dfsParent (null)

        // Measure max dominator-tree depth in O(N) by propagating depth through idom.
        // Process nodes in RPO order — rpoOrder is still valid here (only dfsPos/dfsOrder/dfsParent freed).
        // Since idom[v] always has a lower RPO position than v, RPO is a valid topological order.
        int maxDepth = 0;
        if (graph.rpoOrder != null) {
            // depth[] already declared and zeroed above
            // rpoOrder[0] = VIRTUAL_ROOT, already depth 0
            int rpoReachable = 0;
            for (int i = 0; i < N; i++) if (idom[i] != HeapGraph.UNDEFINED || i == HeapGraph.VIRTUAL_ROOT) rpoReachable++;
            for (int ri = 1; ri < graph.rpoOrder.length; ri++) {
                int v = graph.rpoOrder[ri];
                if (v <= 0 || v >= N) continue;
                int par = idom[v];
                if (par == HeapGraph.UNDEFINED) continue;
                int d = (par >= 0 && par < N ? depth[par] : 0) + 1;
                depth[v] = d;
                if (d > maxDepth) maxDepth = d;
            }
        }
        // Donate depth[] for reuse — RetainedSizes will take it as retainedSize backing.
        if (graph.phaseArrays != null) graph.phaseArrays.donate(depth);
    }

    // Reusable path buffer — avoids per-call allocation in eval().
    // Not thread-safe, but DominatorTree.compute() is single-threaded.
    private static int[] evalPathBuf = new int[1024];

    /**
     * Union-find EVAL with two-pass iterative path compression.
     *
     * Returns the label of the node on the forest path from {@code v} to the root
     * that has the minimum {@code sdom} value.  Compresses the path so that
     * subsequent calls are O(α(N)) amortized.
     */
    private static int eval(int v, int[] ancestor, int[] label, int[] sdom) {
        if (ancestor[v] == -1) return v; // v is a forest root

        // Pass 1: collect path from v to root into reusable buffer
        int[] path = evalPathBuf;
        int pathLen = 0;
        int cur = v;
        while (ancestor[cur] != -1) {
            if (pathLen == path.length) {
                path = Arrays.copyOf(path, pathLen * 2);
                evalPathBuf = path;
            }
            path[pathLen++] = cur;
            cur = ancestor[cur];
        }
        int root = cur; // ancestor[root] == -1

        // Pass 2: walk from root toward v (path[] in reverse), propagating min label
        // and compressing all path nodes directly to root.
        int minLabel = root;
        for (int i = pathLen - 1; i >= 0; i--) {
            int node = path[i];
            ancestor[node] = root; // path compression
            if (sdom[label[node]] < sdom[minLabel]) {
                minLabel = label[node];
            } else {
                label[node] = minLabel;
            }
        }
        return label[v];
    }
}
