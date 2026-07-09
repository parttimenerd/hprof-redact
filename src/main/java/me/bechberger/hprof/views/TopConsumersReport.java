/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.io.PrintWriter;
import java.util.*;

/**
 * Produces the Top Consumers section: biggest objects by retained size,
 * grouped by class and by package.
 */
final class TopConsumersReport {

    static final int TOP_N = 20;

    private final HeapGraph graph;

    TopConsumersReport(HeapGraph graph) {
        this.graph = graph;
    }

    void write(PrintWriter out) {
        out.println("## Top Consumers");
        out.println();
        writeBiggestObjects(out);
        out.println();
        writeBiggestByClass(out);
        out.println();
        writeBiggestByPackage(out);
        out.println();
    }

    /** Top objects by retained size (direct children of virtual root = top-level dominators). */
    private void writeBiggestObjects(PrintWriter out) {
        // Collect all top-level dominators (idom[v] = VIRTUAL_ROOT)
        List<int[]> tops = new ArrayList<>();
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] == HeapGraph.VIRTUAL_ROOT) {
                tops.add(new int[]{i});
            }
        }
        tops.sort(Comparator.comparingLong((int[] a) -> graph.retainedSizeOf(a[0])).reversed());

        out.println("### Biggest Objects (Top-Level Dominators)");
        out.println();
        out.printf("| # | Object Index | Class | Shallow | Retained |%n");
        out.printf("|---|---|---|---:|---:|%n");

        long totalHeap = 0;
        for (int i = 1; i < graph.N; i++) totalHeap += graph.shallowSizeOf(i);

        int rank = 1;
        for (int[] t : tops) {
            if (rank > TOP_N) break;
            int idx = t[0];
            String className = classNameOf(idx);
            long retained = graph.retainedSizeOf(idx);
            double pct = totalHeap > 0 ? 100.0 * retained / totalHeap : 0;
            out.printf("| %d | %d | `%s` | %s | %s (%.1f%%) |%n",
                    rank++, idx, className,
                    SystemOverviewReport.formatBytes(graph.shallowSizeOf(idx)),
                    SystemOverviewReport.formatBytes(retained), pct);
        }
    }

    /** Retained heap grouped by class, using group-retained semantics to avoid double-counting. */
    private void writeBiggestByClass(PrintWriter out) {
        int classCount = graph.classList.size();
        long[] retainedByClass = new long[classCount];
        long[] countByClass    = new long[classCount];
        int N = graph.N;
        int[] idom = graph.idom;

        for (int i = 1; i < N; i++) {
            short ci = graph.classIndex[i];
            if (ci < 0 || ci >= classCount) continue;
            countByClass[ci]++;
        }
        // Group-retained: attribute shallowSize[v] to classOf(idom[v])
        for (int i = 1; i < N; i++) {
            if (idom[i] == HeapGraph.UNDEFINED) continue;
            int parent = idom[i];
            long shallow = graph.shallowSizeOf(i);
            if (parent == HeapGraph.VIRTUAL_ROOT || parent == HeapGraph.UNDEFINED) {
                short ci = graph.classIndex[i];
                if (ci >= 0 && ci < classCount) retainedByClass[ci] += shallow;
            } else {
                short parentCi = graph.classIndex[parent];
                int parentClass = parentCi & 0xFFFF;
                if (parentClass < classCount) retainedByClass[parentClass] += shallow;
            }
        }

        List<Integer> sorted = new ArrayList<>(classCount);
        for (int i = 0; i < classCount; i++) if (countByClass[i] > 0) sorted.add(i);
        sorted.sort(Comparator.comparingLong((Integer i) -> retainedByClass[i]).reversed());

        out.println("### Biggest Classes by Retained Heap");
        out.println();
        out.printf("| # | Class | Instances | Retained Heap |%n");
        out.printf("|---|---|---:|---:|%n");

        int rank = 1;
        for (int ci : sorted) {
            if (rank > TOP_N) break;
            String name = ClassNames.pretty(graph.classList.get(ci).name());
            out.printf("| %d | `%s` | %,d | %s |%n",
                    rank++, name, countByClass[ci],
                    SystemOverviewReport.formatBytes(retainedByClass[ci]));
        }
    }

    /** Retained heap grouped by top-level package. */
    private void writeBiggestByPackage(PrintWriter out) {
        Map<String, long[]> pkgMap = new LinkedHashMap<>(); // pkg → [retained, count]

        int classCount = graph.classList.size();
        long[] retainedByClass = new long[classCount];
        int N = graph.N;
        int[] idom = graph.idom;
        // Group-retained: attribute shallowSize[v] to classOf(idom[v])
        for (int i = 1; i < N; i++) {
            if (idom[i] == HeapGraph.UNDEFINED) continue;
            int parent = idom[i];
            long shallow = graph.shallowSizeOf(i);
            if (parent == HeapGraph.VIRTUAL_ROOT || parent == HeapGraph.UNDEFINED) {
                short ci = graph.classIndex[i];
                if (ci >= 0 && ci < classCount) retainedByClass[ci] += shallow;
            } else {
                short parentCi = graph.classIndex[parent];
                int parentClass = parentCi & 0xFFFF;
                if (parentClass < classCount) retainedByClass[parentClass] += shallow;
            }
        }

        for (int ci = 0; ci < classCount; ci++) {
            if (retainedByClass[ci] == 0) continue;
            String name = graph.classList.get(ci).name();
            String pkg  = topPackage(name);
            long[] acc  = pkgMap.computeIfAbsent(pkg, k -> new long[2]);
            acc[0] += retainedByClass[ci];
            acc[1]++;
        }

        List<Map.Entry<String, long[]>> entries = new ArrayList<>(pkgMap.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());

        out.println("### Biggest Packages by Retained Heap");
        out.println();
        out.printf("| # | Package | Classes | Retained Heap |%n");
        out.printf("|---|---|---:|---:|%n");

        int rank = 1;
        for (Map.Entry<String, long[]> e : entries) {
            if (rank > TOP_N) break;
            out.printf("| %d | `%s` | %,d | %s |%n",
                    rank++, e.getKey(), e.getValue()[1],
                    SystemOverviewReport.formatBytes(e.getValue()[0]));
        }
    }

    private String classNameOf(int idx) {
        short ci = graph.classIndex[idx];
        if (ci < 0 || ci >= graph.classList.size()) return "(class object)";
        return ClassNames.pretty(graph.classList.get(ci).name());
    }

    private static String topPackage(String name) {
        // strip array prefix [L...;
        while (name.startsWith("[")) name = name.substring(1);
        if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
        name = name.replace("/", ".");
        int slash = name.indexOf('.');
        if (slash < 0) return name;
        int second = name.indexOf('.', slash + 1);
        return second < 0 ? name : name.substring(0, second);
    }
}
