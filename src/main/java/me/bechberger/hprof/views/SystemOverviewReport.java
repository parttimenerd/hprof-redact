/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.BitSet;
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
        int[] classIndex = graph.classIndex;

        // Count instances and shallow sizes (instances only, not class objects).
        for (int i = 1; i < N; i++) {
            int ci = classIndex[i];
            if (ci < 0 || ci >= classCount) continue;
            if (idom[i] == HeapGraph.UNDEFINED) continue;
            instanceCount[ci]++;
            shallowTotal[ci] += graph.shallowSizeOf(i);
        }

        // MAT parity: for class C, retained = getMinRetainedSize({classObject(C)} ∪ instances(C)).
        // Top ancestors = nodes with no strict dom-tree ancestor also in the set.
        //
        // hasSameClassAncestor (from RetainedSizes) already encodes this for instance-class membership:
        //   !hasSameClassAncestor.get(v) → v is a top ancestor for its classIndex-based set.
        // For classObj nodes we additionally need to check the classObjNodeCiPairs set.

        // Instance contribution: use pre-computed hasSameClassAncestor bit.
        // ClassObj contribution: walk idom chain for classObj nodes only (at most classCount nodes ≈ thousands).
        BitSet hasAncestorAsClassObj = (graph.classObjNodeCiPairs != null) ? new BitSet(N) : null;

        if (hasAncestorAsClassObj != null) {
            for (int v = 1; v < N; v++) {
                if (idom[v] == HeapGraph.UNDEFINED) continue;
                int ciObj = graph.classObjCiForNode(v);
                if (ciObj < 0 || ciObj >= classCount) continue;
                // v is the class-object for class ciObj. Check if any ancestor is in ciObj's set.
                int cur = idom[v];
                while (cur != HeapGraph.VIRTUAL_ROOT) {
                    int curInst = classIndex[cur];
                    int curObj = graph.classObjCiForNode(cur);
                    if (curInst == ciObj || curObj == ciObj) { hasAncestorAsClassObj.set(v); break; }
                    cur = idom[cur];
                }
            }
        }

        // Sum retained for top ancestors of each class set.
        // Instance contribution: use hasSameClassAncestor (computed by RetainedSizes in O(N)).
        // ClassObj contribution: use hasAncestorAsClassObj (walked above for classObj nodes only).
        for (int i = 1; i < N; i++) {
            if (idom[i] == HeapGraph.UNDEFINED) continue;
            int ciInst = classIndex[i];
            if (ciInst >= 0 && ciInst < classCount && !graph.hasSameClassAncestor.get(i)) {
                classRetained[ciInst] += graph.retainedSizeOf(i);
            }
            if (hasAncestorAsClassObj != null) {
                int ciObj = graph.classObjCiForNode(i);
                if (ciObj >= 0 && ciObj < classCount && !hasAncestorAsClassObj.get(i)) {
                    classRetained[ciObj] += graph.retainedSizeOf(i);
                }
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
