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
        for (int i = 1; i < graph.N; i++) totalShallow += graph.shallowSizeOf(i);

        out.println("### Heap Summary");
        out.println();
        out.printf("| Property | Value |%n");
        out.printf("|---|---|%n");
        out.printf("| HPROF format | %s |%n", graph.hprofFormat);
        out.printf("| File size | %s |%n", formatBytes(graph.fileSize));
        out.printf("| Total objects | %,d |%n", graph.N - 1);
        out.printf("| Total shallow heap | %s |%n", formatBytes(totalShallow));
        out.printf("| GC roots | %,d |%n", graph.gcRootCount);
        out.printf("| Classes loaded | %,d |%n", graph.classList.size());
        if (graph.unreachableCount > 0) {
            out.printf("| Unreachable objects (excluded) | %,d (%s) |%n",
                    graph.unreachableCount, formatBytes(graph.unreachableShallowBytes));
        }
    }

    private void writeHistogram(PrintWriter out) {
        int classCount = graph.classList.size();
        long[] instanceCount = new long[classCount];
        long[] shallowTotal  = new long[classCount];
        long[] retainedTotal = new long[classCount];

        int N = graph.N;
        int[] idom = graph.idom;

        // Count instances and shallow sizes per class
        for (int i = 1; i < N; i++) {
            if (idom[i] == HeapGraph.UNDEFINED) continue; // skip unreachable objects
            short ci = graph.classIndex[i];
            if (ci < 0 || ci >= classCount) continue;
            instanceCount[ci]++;
            shallowTotal[ci]  += graph.shallowSizeOf(i);
        }

        // Group-retained: for each reachable node v, attribute shallowSize[v] to
        // classOf(idom[v]) if idom[v] is a real object, else to v's own class.
        // This counts each byte exactly once, avoiding the O(N^2) double-counting
        // that occurs when summing per-object retained for linked-list classes.
        for (int i = 1; i < N; i++) {
            if (idom[i] == HeapGraph.UNDEFINED) continue; // unreachable
            int parent = idom[i];
            long shallow = graph.shallowSizeOf(i);
            if (parent == HeapGraph.VIRTUAL_ROOT || parent == HeapGraph.UNDEFINED) {
                // Attribute to own class (directly reachable from roots)
                short ci = graph.classIndex[i];
                if (ci >= 0 && ci < classCount) retainedTotal[ci] += shallow;
            } else {
                // Attribute to parent's class
                short parentCi = graph.classIndex[parent];
                int parentClass = parentCi & 0xFFFF;
                if (parentClass < classCount) retainedTotal[parentClass] += shallow;
            }
        }

        // sort by retained desc
        List<Integer> indices = new ArrayList<>(classCount);
        for (int i = 0; i < classCount; i++) {
            if (instanceCount[i] > 0) indices.add(i);
        }
        indices.sort(Comparator.comparingLong((Integer i) -> retainedTotal[i]).reversed());

        out.println("### Class Histogram (by Retained Heap)");
        out.println();
        out.printf("| # | Class | Instances | Shallow Heap | Retained Heap |%n");
        out.printf("|---|---|---:|---:|---:|%n");

        int rank = 1;
        for (int ci : indices) {
            if (rank > 50) { out.println("| ... | *(top 50 shown)* | | | |"); break; }
            String name = graph.classList.get(ci).name().replace("/", ".");
            out.printf("| %d | `%s` | %,d | %s | %s |%n",
                    rank++, name, instanceCount[ci],
                    formatBytes(shallowTotal[ci]),
                    formatBytes(retainedTotal[ci]));
        }
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
