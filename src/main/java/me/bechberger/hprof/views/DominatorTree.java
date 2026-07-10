/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Semi-NCA dominator-tree algorithm — O(N α(N)) semi-dominator phase + O(N·depth) NCA phase.
 *
 * Replaces the Lengauer-Tarjan "simple" algorithm to eliminate the {@code bucket} and
 * {@code next} arrays, saving 2 × int[reachable] ≈ 4 GB on large heaps.
 *
 * Arrays used: {@code sdom}, {@code label}, {@code ancestor}, {@code idomD} (4 total vs LT's 6).
 * {@code idomD} reuses {@code label}'s storage after Phase 1, so peak live count is 3 arrays
 * (sdom + label/idomD + ancestor), not 4.
 * The {@code bucket}/{@code next} linked-list structures from LT's bucket propagation step
 * are replaced by the NCA walk in Phase 2.
 *
 * Reference: Georgiadis, Tarjan, Werneck (2004) "Finding Dominators in Practice".
 *
 * Phase 1 (Semi-dominators) — same as LT:
 *   Process nodes in reverse DFS pre-order. For each node v at DFS position d,
 *   compute sdom[d] = min DFS-number reachable from predecessors via union-find eval().
 *   Then link d into the spanning forest: ancestor[d] = parent DFS position.
 *
 * Phase 2 (NCA idom) — replaces LT's bucket propagation:
 *   Process nodes in forward DFS pre-order. For each node v at DFS position d,
 *   start from its DFS-tree parent position and walk up the partial idom tree
 *   (already built for positions 0..d-1) until reaching a node with DFS position <= sdom[d].
 *   That node's DFS position is idomD[d].
 *
 * The NCA walk is O(depth of idom tree) per node. For well-structured heaps this is fast.
 * Worst case is O(N) per node (deep chains), dominated by O(N α(N)) semi-dominator phase.
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
        // Semi-NCA uses 4 arrays (vs LT's 6): sdom, label, ancestor, idomD.
        // Eliminated: bucket[], next[] (LT-specific bucket propagation lists).
        //
        // Memory notes:
        //   - idomD is not allocated until after Phase 1 completes, and is assigned the
        //     label[] array's backing storage (same size: int[reachable]). This avoids
        //     holding idomD live simultaneously with label+ancestor+inbound CSR.
        //   - sdom[] reuses graph.rpoOrder's backing (int[N] ≥ reachable for typical heaps),
        //     saving one fresh int[reachable] allocation and making rpoOrder immediately
        //     ineligible for GC (it is overwritten, so no stale data risk).
        //     Fallback: fresh allocation if rpoOrder is null or too small.
        //   - RetainedSizes uses children-CSR DFS instead of rpoOrder traversal, so
        //     rpoOrder is not needed after DominatorTree completes.
        // ----------------------------------------------------------------

        // Reuse rpoOrder as sdom[] backing to save ~2 GB: rpoOrder is no longer needed
        // (RetainedSizes uses children-CSR DFS), and it is exactly int[rpoReachable] ≈ int[N].
        int[] sdom;
        {
            int[] rpoArr = graph.rpoOrder;
            graph.rpoOrder = null; // sever graph reference; sdom now owns the array
            if (rpoArr != null && rpoArr.length >= reachable) {
                sdom = rpoArr; // reuse: overwrite with sdom values below
            } else {
                sdom = new int[reachable]; // fallback (partial-reachability or test heaps)
            }
        }
        int[] label    = new int[reachable]; // union-find: min-sdom node on path to forest root
        int[] ancestor = new int[reachable]; // union-find parent DFS-position; -1 = forest root

        Arrays.fill(ancestor, -1);
        for (int d = 0; d < reachable; d++) {
            label[d] = d;
            sdom[d]  = d; // initial: sdom is itself (identity)
        }

        // graph.isGCRoot is a pre-built BitSet of GC root node indices — reuse it
        // instead of allocating a separate vrAdjacent BitSet(N).
        BitSet vrAdjacent = graph.isGCRoot;

        // ----------------------------------------------------------------
        // Phase 1: Semi-dominator computation.
        // Process in REVERSE DFS pre-order (d = reachable-1 down to 1).
        // Same as LT's semi-dominator step but WITHOUT bucket/next processing.
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

            // --- Compute sdom[d] ---
            // Start from DFS-tree parent as upper bound
            int minSdom = par;

            // Implicit VRoot predecessor for GC roots
            if (vrAdjacent.get(v) && minSdom > 0) minSdom = 0;

            // Scan actual inbound predecessors
            long start = Integer.toUnsignedLong(graph.inboundOffsets[v]);
            long end   = Integer.toUnsignedLong(graph.inboundOffsets[v + 1]);
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

            // Link d into spanning forest: ancestor[d] = par
            ancestor[d] = par;
        }

        // label[] and ancestor[] are dead after Phase 1.
        // Repurpose label[] as idomD[] (same size: int[reachable]) to avoid a fresh
        // int[reachable] allocation while inbound CSR is still live.
        int[] idomD = label;
        label = null;
        Arrays.fill(idomD, -1);
        // Donate ancestor to phaseArrays: it is int[reachable] ≥ N for fully-reachable heaps.
        if (graph.phaseArrays != null && ancestor.length >= graph.N) graph.phaseArrays.donate(ancestor);
        ancestor = null;
        // inboundOffsets and inboundStream are only used in Phase 1; free them now
        // to reclaim ~2 GB int[N+1] + VByte stream before the NCA walk.
        // Skip when retainInboundCsrForTesting is set (unit-test mode: preserve for inspection).
        if (!graph.retainInboundCsrForTesting) {
            graph.phaseArrays.donate(graph.inboundOffsets); // int[N+1] ≥ N: accepted
            graph.inboundOffsets = null;
            graph.inboundStream  = null;
        }
        Log.debug("  [RSS] DOM after phase1+free: %,d KB", Log.rssKb());

        // ----------------------------------------------------------------
        // Phase 2: NCA idom computation.
        // Process in FORWARD DFS pre-order (d = 1 to reachable-1).
        // For each node at DFS pos d:
        //   1. Start from its DFS-tree parent position parentD.
        //   2. Walk up the partial idom tree (idomD[0..d-1] already finalized)
        //      until reaching a position x with x <= sdom[d].
        //   3. Set idomD[d] = x.
        //
        // The walk uses idomD[] (in DFS-position space) directly, avoiding
        // the need to look up dfsPos[] values during the walk.
        // ----------------------------------------------------------------
        idomD[0] = 0; // virtual root is its own idom sentinel
        for (int d = 1; d < reachable; d++) {
            int v = dfsOrd[d];

            // DFS-tree parent DFS position
            int parentD;
            {
                int parNode = dfsPar[v];
                parentD = (parNode < 0) ? 0 : dfsPos[parNode];
            }

            // NCA walk: from parentD, walk up the partial idom tree until
            // reaching a position x with x <= sdom[d]. That x is idomD[d].
            // All nodes on this walk have DFS positions < d, so idomD[] is
            // already finalized for them.
            int x = parentD;
            while (x > sdom[d]) {
                x = idomD[x]; // step up the partial idom tree (DFS-position space)
            }
            idomD[d] = x;
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

        // sdom[] and idomD[] are dead after translation — donate to phaseArrays for reuse.
        if (graph.phaseArrays != null && sdom.length >= graph.N) graph.phaseArrays.donate(sdom);
        if (graph.phaseArrays != null && idomD.length >= graph.N) graph.phaseArrays.donate(idomD);
        sdom  = null;
        idomD = null;
        Log.debug("  [RSS] DOM after phase2+translate: %,d KB", Log.rssKb());

        // Take dfsParent as a reusable int[N] buffer (already zeroed below) and donate it to
        // phaseArrays for RetainedSizes to use as retainedSize[] backing.
        int[] scratch = graph.dfsParent;
        graph.dfsParent = null;
        java.util.Arrays.fill(scratch, 0);

        graph.freeRpoPos(); // donates dfsOrder to phaseArrays; nulls dfsPos (null), dfsOrder, dfsParent (null)

        // Donate scratch (former dfsParent, zeroed) for reuse as retainedSize[] backing.
        if (graph.phaseArrays != null) graph.phaseArrays.donate(scratch);
        scratch = null;
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
