/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.io.PrintWriter;
import java.util.*;

/**
 * Produces the Leak Suspects section.
 *
 * A "suspect" is a top-level dominator (idom[v] = VIRTUAL_ROOT) whose retained heap
 * exceeds {@link #THRESHOLD_PCT}% of total heap. If no single object qualifies, we
 * look for accumulation points: nodes where a single class's instances collectively
 * dominate a large fraction.
 */
final class LeakSuspectsReport {

    /** Default threshold: retained >= 10% of total heap → suspect. */
    static final double THRESHOLD_PCT = 10.0;

    private final HeapGraph graph;
    private final double thresholdPct;

    LeakSuspectsReport(HeapGraph graph, double thresholdPct) {
        this.graph = graph;
        this.thresholdPct = thresholdPct;
    }

    LeakSuspectsReport(HeapGraph graph) {
        this(graph, THRESHOLD_PCT);
    }

    void write(PrintWriter out) {
        out.println("## Leak Suspects");
        out.println();

        long totalShallow = 0;
        for (int i = 1; i < graph.N; i++) totalShallow += graph.shallowSizeOf(i);
        long threshold = (long) (totalShallow * thresholdPct / 100.0);

        List<Suspect> suspects = findSuspects(threshold, totalShallow);
        if (suspects.isEmpty()) {
            out.println("No single object or class group exceeds the " + thresholdPct + "% threshold.");
            out.println();
            return;
        }

        int suspectNum = 1;
        for (Suspect s : suspects) {
            out.printf("### Suspect %d: `%s`%n%n", suspectNum++, s.className);
            out.printf("- **Type**: %s%n", s.isSingle ? "Single large object" : "Group of instances");
            out.printf("- **Instances**: %,d%n", s.instanceCount);
            out.printf("- **Retained heap**: %s (%.1f%% of total)%n",
                    SystemOverviewReport.formatBytes(s.retainedBytes),
                    100.0 * s.retainedBytes / totalShallow);
            out.printf("- **Shallow heap**: %s%n",
                    SystemOverviewReport.formatBytes(s.shallowBytes));
            out.println();

            // Show accumulation path: walk down the dominator tree to find the
            // biggest retained child, up to 5 levels
            if (s.isSingle) {
                out.println("**Accumulation point path** (largest retained child at each step):");
                out.println();
                writeAccumulationPath(out, s.objectIndex, 5);
                out.println();
            }
        }
    }

    private List<Suspect> findSuspects(long threshold, long totalShallow) {
        List<Suspect> suspects = new ArrayList<>();

        // Phase 1: single top-level dominators
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.VIRTUAL_ROOT) continue;
            long retained = graph.retainedSizeOf(i);
            if (retained < threshold) continue;
            String className = classNameOf(i);
            suspects.add(new Suspect(className, i, 1, retained, graph.shallowSizeOf(i), true));
        }

        // Phase 2: class groups using group-retained semantics (avoid double-counting linked lists)
        if (suspects.isEmpty()) {
            int classCount = graph.classList.size();
            long[] retainedByClass = new long[classCount];
            long[] shallowByClass  = new long[classCount];
            long[] countByClass    = new long[classCount];
            int[]  firstByClass    = new int[classCount]; // first object index per class
            Arrays.fill(firstByClass, -1);
            int N = graph.N;
            int[] idom = graph.idom;

            for (int i = 1; i < N; i++) {
                short ci = graph.classIndex[i];
                if (ci < 0 || ci >= classCount) continue;
                shallowByClass[ci]  += graph.shallowSizeOf(i);
                countByClass[ci]++;
                if (firstByClass[ci] < 0) firstByClass[ci] = i;
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

            for (int ci = 0; ci < classCount; ci++) {
                if (retainedByClass[ci] < threshold) continue;
                String name = ClassNames.pretty(graph.classList.get(ci).name());
                suspects.add(new Suspect(name, firstByClass[ci], countByClass[ci],
                        retainedByClass[ci], shallowByClass[ci], false));
            }
        }

        suspects.sort(Comparator.comparingLong((Suspect s) -> s.retainedBytes).reversed());
        return suspects;
    }

    /** Walk down dominator tree: at each node, find child with largest retained heap. */
    private void writeAccumulationPath(PrintWriter out, int startNode, int maxDepth) {
        out.printf("| Depth | Object | Class | Retained |%n");
        out.printf("|---|---|---|---:|%n");

        int current = startNode;
        for (int depth = 0; depth < maxDepth; depth++) {
            String className = classNameOf(current);
            out.printf("| %d | %d | `%s` | %s |%n",
                    depth, current, className,
                    SystemOverviewReport.formatBytes(graph.retainedSizeOf(current)));

            // Find child with largest retained (linear scan of all nodes — cheap for short paths)
            int bestChild = -1;
            long bestRetained = 0;
            for (int i = 1; i < graph.N; i++) {
                if (graph.idom[i] == current) {
                    long r = graph.retainedSizeOf(i);
                    if (r > bestRetained) { bestRetained = r; bestChild = i; }
                }
            }
            if (bestChild < 0) break;
            current = bestChild;
        }
    }

    private String classNameOf(int idx) {
        short ci = graph.classIndex[idx];
        if (ci < 0 || ci >= graph.classList.size()) return "(class object)";
        return ClassNames.pretty(graph.classList.get(ci).name());
    }

    private record Suspect(
            String className,
            int objectIndex,
            long instanceCount,
            long retainedBytes,
            long shallowBytes,
            boolean isSingle
    ) {}
}
