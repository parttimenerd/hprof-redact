/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Produces the System Overview section: heap summary, histogram by class.
 */
final class SystemOverviewReport {

    private final HeapGraph graph;

    SystemOverviewReport(HeapGraph graph) {
        this.graph = graph;
    }

    void write(PrintWriter out) {
        out.println("## System Overview");
        out.println();
        writeSummary(out);
        out.println();
        writeHistogram(out);
        out.println();
    }

    private void writeSummary(PrintWriter out) {
        long totalHeap = graph.totalHeapBytes();
        long totalRetained = graph.retainedSizeOf(HeapGraph.VIRTUAL_ROOT);
        // virtual root retained = sum of all top-level retained (can be less due to freeing)
        // better to sum all objects
        long totalShallow = 0;
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.UNDEFINED) totalShallow += graph.shallowSizeOf(i);
        }

        out.println("### Heap Summary");
        out.println();
        out.printf("| Property | Value |%n");
        out.printf("|---|---|%n");
        out.printf("| HPROF format | %s |%n", graph.hprofFormat);
        out.printf("| File size | %s |%n", formatBytes(graph.fileSize));
        out.printf("| Total objects | %,d |%n", graph.N - 1 - graph.unreachableCount);
        out.printf("| Total shallow heap | %s |%n", formatBytes(totalShallow));
        out.printf("| GC roots | %,d |%n", graph.gcRootCount - graph.syntheticRootCount);
        out.printf("| Classes loaded | %,d |%n", graph.reachableClassCount());
        if (graph.unreachableCount > 0) {
            out.printf("| Unreachable objects (excluded) | %,d (%s) |%n",
                    graph.unreachableCount, formatBytes(graph.unreachableShallowBytes));
        }
    }

    private void writeHistogram(PrintWriter out) {
        int classCount = graph.classList.size();
        long[] instanceCount = new long[classCount];
        long[] shallowTotal  = new long[classCount];
        long[] classRetained = new long[classCount];

        int N = graph.N;
        int[] idom = graph.idom;
        short[] classObjClassIdx = graph.classObjClassIdx;
        short[] classIndex = graph.classIndex;

        // Count instances and shallow sizes (instances only, not class objects).
        for (int i = 1; i < N; i++) {
            short ci = classIndex[i];
            if (ci < 0 || ci >= classCount) continue;
            if (idom[i] == HeapGraph.UNDEFINED) continue;
            instanceCount[ci]++;
            shallowTotal[ci] += graph.shallowSizeOf(i);
        }

        // MAT parity: for class C, retained = getMinRetainedSize({classObject(C)} ∪ instances(C)).
        // This finds "top ancestors" of that set: members for which no strict dom-tree ancestor
        // is also in the set. We sum retained sizes of only top ancestors.
        //
        // A node v contributes to class C's set if:
        //   classIndex[v] == C  (v is an instance of C), OR
        //   classObjClassIdx[v] == C  (v is the class object FOR C).
        //
        // Note: class objects are instances of java.lang.Class (classIndex = java.lang.Class ci),
        // AND also the "classObject" for their own class. Both roles are independent.
        //
        // hasSetAncestor[v][C] = true iff some strict dom-tree ancestor of v is in set C.
        // We compute this per-class by walking up idom chains. Dom trees are typically shallow
        // (O(log N)), so O(N·depth) total is fine.
        //
        // For each node v in set C: walk idom[v] upward; stop when we find another node in set C.
        boolean[] hasSetAncestor = new boolean[N];

        // We need two separate hasSetAncestor computations:
        //   - For instance-class membership (classIndex[v]==C): walk checking classIndex[cur]==C or classObjClassIdx[cur]==C
        //   - Same for classObj membership (classObjClassIdx[v]==C): same walk
        // Since both use the same "is in set C" predicate, we can do it in one loop.

        // For each node v that is in some class C's set, check if any dom-ancestor is also in that set.
        // "In set C" = classIndex[v]==C OR classObjClassIdx[v]==C.
        // A node may be in multiple sets (class object is in java.lang.Class set AND own-class set).
        // We need a separate hasSetAncestor flag per (node, class) pair — but that's O(N*classCount).
        //
        // Efficient approach: for each node v, its "primary class" for ancestor checking is:
        //   - If classObjClassIdx[v] >= 0: BOTH java.lang.Class-ci (via classIndex) AND classObjClassIdx[v]
        //   - Otherwise: classIndex[v]
        //
        // We compute two hasSetAncestor arrays:
        //   hasAncestorAsInstance[v]: true iff some ancestor has classIndex==classIndex[v] (or is classObj for classIndex[v])
        //   hasAncestorAsClassObj[v]: only relevant for classObj nodes: true iff some ancestor is in classObjClassIdx[v]'s set
        //
        // Then a node v is a top-ancestor for its "instance-class" set (classIndex[v]) if !hasAncestorAsInstance[v].
        // A classObj node v is a top-ancestor for its "classObj-class" set (classObjClassIdx[v]) if !hasAncestorAsClassObj[v].

        boolean[] hasAncestorAsInstance = new boolean[N]; // for classIndex[v] set
        boolean[] hasAncestorAsClassObj = (classObjClassIdx != null) ? new boolean[N] : null; // for classObjClassIdx[v] set

        for (int v = 1; v < N; v++) {
            if (idom[v] == HeapGraph.UNDEFINED) continue;
            short ciInst = classIndex[v]; // instance class
            short ciObj = (classObjClassIdx != null && v < classObjClassIdx.length) ? classObjClassIdx[v] : -1;

            // Check ancestor-as-instance for set ciInst
            if (ciInst >= 0 && ciInst < classCount) {
                int cur = idom[v];
                while (cur != HeapGraph.VIRTUAL_ROOT) {
                    short curInst = classIndex[cur];
                    short curObj = (classObjClassIdx != null && cur < classObjClassIdx.length) ? classObjClassIdx[cur] : -1;
                    if (curInst == ciInst || curObj == ciInst) { hasAncestorAsInstance[v] = true; break; }
                    cur = idom[cur];
                }
            }

            // Check ancestor-as-classObj for set ciObj (only for classObj nodes)
            if (ciObj >= 0 && ciObj < classCount && hasAncestorAsClassObj != null) {
                int cur = idom[v];
                while (cur != HeapGraph.VIRTUAL_ROOT) {
                    short curInst = classIndex[cur];
                    short curObj = (classObjClassIdx != null && cur < classObjClassIdx.length) ? classObjClassIdx[cur] : -1;
                    if (curInst == ciObj || curObj == ciObj) { hasAncestorAsClassObj[v] = true; break; }
                    cur = idom[cur];
                }
            }
        }

        // Sum retained for top ancestors of each class set.
        // Instance contribution: if !hasAncestorAsInstance[v], add retained to classIndex[v]'s row.
        // ClassObj contribution: if !hasAncestorAsClassObj[v], add retained to classObjClassIdx[v]'s row.
        for (int i = 1; i < N; i++) {
            if (idom[i] == HeapGraph.UNDEFINED) continue;
            short ciInst = classIndex[i];
            if (ciInst >= 0 && ciInst < classCount && !hasAncestorAsInstance[i]) {
                classRetained[ciInst] += graph.retainedSizeOf(i);
            }
            if (hasAncestorAsClassObj != null && i < classObjClassIdx.length) {
                short ciObj = classObjClassIdx[i];
                if (ciObj >= 0 && ciObj < classCount && !hasAncestorAsClassObj[i]) {
                    classRetained[ciObj] += graph.retainedSizeOf(i);
                }
            }

        // sort by retained desc
        List<Integer> indices = new ArrayList<>(classCount);
        for (int i = 0; i < classCount; i++) {
            if (instanceCount[i] > 0 || classRetained[i] > 0) indices.add(i);
        }
        indices.sort(Comparator.comparingLong((Integer i) -> classRetained[i]).reversed());

        out.println("### Class Histogram (by Retained Heap)");
        out.println();
        out.printf("| # | Class | Instances | Shallow Heap | Retained Heap |%n");
        out.printf("|---|---|---:|---:|---:|%n");

        int rank = 1;
        for (int ci : indices) {
            String name = ClassNames.pretty(graph.classList.get(ci).name());
            out.printf("| %d | `%s` | %,d | %s | %,d |%n",
                    rank++, name, instanceCount[ci],
                    formatBytes(shallowTotal[ci]),
                    classRetained[ci]);
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
