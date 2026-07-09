/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.*;

/**
 * Computes all derived data needed for the HTML report from a built HeapGraph.
 * No HTML or JSON is produced here -- only plain Java records.
 */
final class HtmlReportData {

    private HtmlReportData() {}

    // -- Record types --

    record HeapSummary(
        String fileName, String hprofFormat, long fileSize,
        long objectCount, long totalShallowBytes,
        int gcRootCount, int classCount, String generatedAt
    ) {}

    record ClassHistogramEntry(
        int rank, String className, long instanceCount,
        long shallowBytes, long groupRetainedBytes
    ) {}

    record BiggestObject(
        int rank, String hexAddress, String className,
        long shallowBytes, long retainedBytes, double retainedPct
    ) {}

    record BiggestClass(int rank, String className, long topLevelCount, long retainedBytes) {}

    record BiggestPackage(int rank, String packageName, long classCount, long retainedBytes) {}

    record BiggestClassLoader(int rank, String loaderName, long classCount, long retainedBytes) {}

    record ThreadEntry(
        String name, String hexAddress, long shallowBytes, long retainedBytes,
        boolean daemon, int priority, String state, String contextClassLoader
    ) {}

    record TopConsumer(String className, long instanceCount, long totalShallowBytes) {}

    record StackFrameEntry(
        String methodName, String methodSig, String sourceFile, int lineNumber,
        String localHexAddress, String localClassName, long localRetainedBytes
    ) {}

    record PathStep(int depth, String hexAddress, String className, long retainedBytes) {}

    record LeakSuspect(
        String className, long instanceCount, long retainedBytes,
        long shallowBytes, double retainedPct, boolean isSingle,
        List<PathStep> accumulationPath,
        List<TopConsumer> topConsumers,
        String narrative,
        List<StackFrameEntry> stackFrames   // non-empty only if graph.stackTraces != null
    ) {}

    record SysProp(String key, String value) {}

    record PieSlice(String label, long bytes, double pct) {}

    record ReportData(
        HeapSummary summary,
        List<ClassHistogramEntry> histogram,
        List<BiggestObject> biggestObjects,
        List<BiggestClass> biggestClasses,
        List<BiggestPackage> biggestPackages,
        List<BiggestClassLoader> biggestClassLoaders,
        List<ThreadEntry> threads,
        List<LeakSuspect> leakSuspects,
        List<SysProp> systemProperties,
        List<PieSlice> objectPieSlices,
        List<PieSlice> classPieSlices
    ) {}

    // -- Entry point --

    static ReportData compute(HeapGraph graph) {
        long totalShallow = 0;
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.UNDEFINED) totalShallow += graph.shallowSizeOf(i);
        }
        final long ts = totalShallow;

        List<BiggestObject> biggestObjects = buildBiggestObjects(graph, ts);
        List<BiggestClass>  biggestClasses = buildBiggestClasses(graph, ts);

        return new ReportData(
            buildSummary(graph, ts),
            buildHistogram(graph, ts),
            biggestObjects,
            biggestClasses,
            buildBiggestPackages(graph),
            buildBiggestClassLoaders(graph),
            buildThreads(graph),
            buildLeakSuspects(graph, ts),
            List.of(),   // system properties: requires instance data not retained post-build
            biggestObjects.stream().limit(10)
                .map(o -> new PieSlice(o.className(), o.retainedBytes(), o.retainedPct())).toList(),
            biggestClasses.stream().limit(10)
                .map(c -> new PieSlice(c.className(), c.retainedBytes(),
                    ts > 0 ? 100.0 * c.retainedBytes() / ts : 0.0)).toList()
        );
    }

    // -- Summary --

    private static HeapSummary buildSummary(HeapGraph graph, long totalShallow) {
        return new HeapSummary(
            graph.sourcePath.getFileName().toString(),
            graph.hprofFormat, graph.fileSize,
            graph.N - 1L - graph.unreachableCount, totalShallow,
            graph.gcRootCount, graph.classList.size(),
            java.time.Instant.now().toString()
        );
    }

    // -- Class-retained histogram (MAT semantics) --
    // For each class C, retained = sum of retainedSize(v) over all v of class C
    // that are "top ancestors" — i.e., no strict ancestor in the dominator tree
    // is of class C. This mirrors MAT's getMinRetainedSize(all-instances-of-C).
    // The hasSameClassAncestor bit is precomputed in RetainedSizes.

    private static List<ClassHistogramEntry> buildHistogram(HeapGraph graph, long totalShallow) {
        int classCount = graph.classList.size();
        long[] instanceCount = new long[classCount];
        long[] shallowTotal  = new long[classCount];
        long[] classRetained = new long[classCount];

        for (int i = 1; i < graph.N; i++) {
            short ci = graph.classIndex[i];
            if (ci < 0 || ci >= classCount) continue;
            if (graph.idom[i] == HeapGraph.UNDEFINED) continue; // unreachable: exclude from histogram
            instanceCount[ci]++;
            shallowTotal[ci] += graph.shallowSizeOf(i);

            if (!graph.hasSameClassAncestor.get(i)) {
                classRetained[ci] += graph.retainedSizeOf(i);
            }
        }

        List<Integer> indices = new ArrayList<>(classCount);
        for (int i = 0; i < classCount; i++) if (instanceCount[i] > 0) indices.add(i);
        indices.sort(Comparator.comparingLong((Integer i) -> classRetained[i]).reversed());

        List<ClassHistogramEntry> result = new ArrayList<>(indices.size());
        int rank = 1;
        for (int ci : indices) {
            result.add(new ClassHistogramEntry(rank++,
                ClassNames.pretty(graph.classList.get(ci).name()),
                instanceCount[ci], shallowTotal[ci], classRetained[ci]));
        }
        return result;
    }

    // -- Biggest objects (top-level dominators, top 20) --

    private static List<BiggestObject> buildBiggestObjects(HeapGraph graph, long totalShallow) {
        List<int[]> tops = new ArrayList<>();
        for (int i = 1; i < graph.N; i++)
            if (graph.idom[i] == HeapGraph.VIRTUAL_ROOT) tops.add(new int[]{i});
        tops.sort(Comparator.comparingLong((int[] a) -> graph.retainedSizeOf(a[0])).reversed());

        List<BiggestObject> result = new ArrayList<>(Math.min(tops.size(), 20));
        int rank = 1;
        for (int[] t : tops) {
            if (rank > 20) break;
            int idx = t[0];
            long retained = graph.retainedSizeOf(idx);
            result.add(new BiggestObject(rank++, hexAddr(graph, idx), className(graph, idx),
                graph.shallowSizeOf(idx), retained,
                totalShallow > 0 ? 100.0 * retained / totalShallow : 0.0));
        }
        return result;
    }

    // -- Biggest by class (top-level dominators only) --

    private static List<BiggestClass> buildBiggestClasses(HeapGraph graph, long totalShallow) {
        int classCount = graph.classList.size();
        long[] retainedByClass = new long[classCount];
        long[] countByClass    = new long[classCount];
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.VIRTUAL_ROOT) continue;
            short ci = graph.classIndex[i];
            if (ci >= 0 && ci < classCount) {
                retainedByClass[ci] += graph.retainedSizeOf(i);
                countByClass[ci]++;
            }
        }
        List<Integer> sorted = new ArrayList<>(classCount);
        for (int i = 0; i < classCount; i++) if (countByClass[i] > 0) sorted.add(i);
        sorted.sort(Comparator.comparingLong((Integer i) -> retainedByClass[i]).reversed());

        List<BiggestClass> result = new ArrayList<>(Math.min(sorted.size(), 20));
        int rank = 1;
        for (int ci : sorted) {
            if (rank > 20) break;
            result.add(new BiggestClass(rank++,
                ClassNames.pretty(graph.classList.get(ci).name()),
                countByClass[ci], retainedByClass[ci]));
        }
        return result;
    }

    // -- Biggest by package --

    private static List<BiggestPackage> buildBiggestPackages(HeapGraph graph) {
        int classCount = graph.classList.size();
        long[] retainedByClass = new long[classCount];
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.VIRTUAL_ROOT) continue;
            short ci = graph.classIndex[i];
            if (ci >= 0 && ci < classCount) retainedByClass[ci] += graph.retainedSizeOf(i);
        }
        Map<String, long[]> pkgMap = new LinkedHashMap<>();
        for (int ci = 0; ci < classCount; ci++) {
            if (retainedByClass[ci] == 0) continue;
            String pkg = topPackage(graph.classList.get(ci).name());
            long[] acc = pkgMap.computeIfAbsent(pkg, k -> new long[2]);
            acc[0] += retainedByClass[ci]; acc[1]++;
        }
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(pkgMap.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
        List<BiggestPackage> result = new ArrayList<>(Math.min(entries.size(), 20));
        int rank = 1;
        for (var e : entries) {
            if (rank > 20) break;
            result.add(new BiggestPackage(rank++, e.getKey(), e.getValue()[1], e.getValue()[0]));
        }
        return result;
    }

    // -- Biggest by class loader --

    private static List<BiggestClassLoader> buildBiggestClassLoaders(HeapGraph graph) {
        int classCount = graph.classList.size();
        long[] retainedByClass = new long[classCount];
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.VIRTUAL_ROOT) continue;
            short ci = graph.classIndex[i];
            if (ci >= 0 && ci < classCount) retainedByClass[ci] += graph.retainedSizeOf(i);
        }
        Map<String, long[]> loaderMap = new LinkedHashMap<>();
        for (int ci = 0; ci < classCount; ci++) {
            if (retainedByClass[ci] == 0) continue;
            String loaderName = loaderName(graph, ci);
            long[] acc = loaderMap.computeIfAbsent(loaderName, k -> new long[2]);
            acc[0] += retainedByClass[ci]; acc[1]++;
        }
        List<Map.Entry<String, long[]>> entries = new ArrayList<>(loaderMap.entrySet());
        entries.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed());
        List<BiggestClassLoader> result = new ArrayList<>(Math.min(entries.size(), 20));
        int rank = 1;
        for (var e : entries) {
            if (rank > 20) break;
            result.add(new BiggestClassLoader(rank++, e.getKey(), e.getValue()[1], e.getValue()[0]));
        }
        return result;
    }

    private static String loaderName(HeapGraph graph, int classIdx) {
        ClassRecord cr = graph.classList.get(classIdx);
        if (cr.classLoaderId() == 0) return "<bootstrap>";
        int loaderIdx = graph.idMap.indexOf(cr.classLoaderId());
        if (loaderIdx >= 1 && loaderIdx < graph.N) return className(graph, loaderIdx);
        return "<bootstrap>";
    }

    // -- Thread entries --

    private static List<ThreadEntry> buildThreads(HeapGraph graph) {
        int threadClassIdx = -1;
        for (int ci = 0; ci < graph.classList.size(); ci++) {
            if ("java/lang/Thread".equals(graph.classList.get(ci).name())) {
                threadClassIdx = ci;
                break;
            }
        }
        if (threadClassIdx < 0) return List.of();

        List<ThreadEntry> result = new ArrayList<>();
        int n = 1;
        for (int i = 1; i < graph.N; i++) {
            if (graph.classIndex[i] != threadClassIdx) continue;
            String name = (graph.stackTraces != null)
                ? graph.stackTraces.threadName(graph.idMap.addressAt(i)) : null;
            if (name == null) name = "<thread-" + (n++) + ">";
            result.add(new ThreadEntry(
                name, hexAddr(graph, i),
                graph.shallowSizeOf(i), graph.retainedSizeOf(i),
                false, 5, "UNKNOWN", "<unknown>"
            ));
        }
        result.sort(Comparator.comparingLong(ThreadEntry::retainedBytes).reversed());
        return result;
    }

    // -- Leak suspects --

    private static List<LeakSuspect> buildLeakSuspects(HeapGraph graph, long totalShallow) {
        long threshold = (long) (totalShallow * 10.0 / 100.0);
        List<LeakSuspect> suspects = new ArrayList<>();

        // Phase 1: single top-level dominators
        for (int i = 1; i < graph.N; i++) {
            if (graph.idom[i] != HeapGraph.VIRTUAL_ROOT) continue;
            long retained = graph.retainedSizeOf(i);
            if (retained < threshold) continue;
            List<TopConsumer> topC = buildTopConsumers(graph, i);
            suspects.add(new LeakSuspect(
                className(graph, i), 1, retained, graph.shallowSizeOf(i),
                totalShallow > 0 ? 100.0 * retained / totalShallow : 0.0,
                true, buildPath(graph, i, 5), topC,
                buildNarrative(graph, i, retained, totalShallow, topC),
                buildStackFrames(graph, i)
            ));
        }

        // Phase 2: class groups — always run (MAT reports both individual and class-group suspects)
        // Group-retained = sum of retainedSize for top-level dominators (idom == VIRTUAL_ROOT) per class.
        {
            int classCount = graph.classList.size();
            long[] retainedByClass = new long[classCount];
            long[] shallowByClass  = new long[classCount];
            long[] countByClass    = new long[classCount];
            for (int i = 1; i < graph.N; i++) {
                short ci = graph.classIndex[i];
                if (ci < 0 || ci >= classCount) continue;
                if (graph.idom[i] == HeapGraph.UNDEFINED) continue;
                shallowByClass[ci]  += graph.shallowSizeOf(i);
                countByClass[ci]++;
                if (graph.idom[i] == HeapGraph.VIRTUAL_ROOT) {
                    retainedByClass[ci] += graph.retainedSizeOf(i);
                }
            }
            java.util.Set<String> phase1Classes = suspects.stream()
                .map(LeakSuspect::className).collect(java.util.stream.Collectors.toSet());
            for (int ci = 0; ci < classCount; ci++) {
                if (retainedByClass[ci] < threshold) continue;
                String name = ClassNames.pretty(graph.classList.get(ci).name());
                if (phase1Classes.contains(name)) continue;
                String narrative = String.format("%,d instances of %s occupy %s (%.2f%%) bytes.",
                    countByClass[ci], name,
                    SystemOverviewReport.formatBytes(retainedByClass[ci]),
                    100.0 * retainedByClass[ci] / totalShallow);
                suspects.add(new LeakSuspect(
                    name, countByClass[ci], retainedByClass[ci], shallowByClass[ci],
                    totalShallow > 0 ? 100.0 * retainedByClass[ci] / totalShallow : 0.0,
                    false, List.of(), List.of(), narrative, List.of()
                ));
            }
        }
        suspects.sort(Comparator.comparingLong(LeakSuspect::retainedBytes).reversed());
        return suspects;
    }

    /** Walk retained subtree of rootNode (single O(N) forward pass). */
    private static List<TopConsumer> buildTopConsumers(HeapGraph graph, int rootNode) {
        java.util.BitSet inSubtree = new java.util.BitSet(graph.N);
        inSubtree.set(rootNode);
        for (int v = 1; v < graph.N; v++) {
            int d = graph.idom[v];
            if (d >= 0 && inSubtree.get(d)) inSubtree.set(v);
        }
        int classCount = graph.classList.size();
        long[] shallowByClass = new long[classCount];
        long[] countByClass   = new long[classCount];
        for (int v = inSubtree.nextSetBit(1); v >= 0; v = inSubtree.nextSetBit(v + 1)) {
            short ci = graph.classIndex[v];
            if (ci >= 0 && ci < classCount) {
                shallowByClass[ci] += graph.shallowSizeOf(v);
                countByClass[ci]++;
            }
        }
        List<Integer> sorted = new ArrayList<>();
        for (int ci = 0; ci < classCount; ci++) if (countByClass[ci] > 0) sorted.add(ci);
        sorted.sort(Comparator.comparingLong((Integer ci) -> shallowByClass[ci]).reversed());

        List<TopConsumer> result = new ArrayList<>(3);
        for (int ci : sorted) {
            if (result.size() >= 3) break;
            result.add(new TopConsumer(
                ClassNames.pretty(graph.classList.get(ci).name()),
                countByClass[ci], shallowByClass[ci]));
        }
        return result;
    }

    private static String buildNarrative(HeapGraph graph, int rootNode, long retained,
                                          long totalShallow, List<TopConsumer> topC) {
        String cn = className(graph, rootNode);
        String addr = hexAddr(graph, rootNode);
        double pct = totalShallow > 0 ? 100.0 * retained / totalShallow : 0;
        StringBuilder sb = new StringBuilder();
        boolean isThread = cn.equals("java.lang.Thread") || cn.endsWith(".Thread");
        if (isThread) {
            sb.append("The thread ").append(cn).append(" @ ").append(addr)
              .append(" keeps local variables with total size ")
              .append(String.format("%,d (%.2f%%)", retained, pct)).append(" bytes.");
        } else {
            sb.append("One instance of ").append(cn)
              .append(", loaded by ").append(loaderName(graph, graph.classIndex[rootNode]))
              .append(", occupies ")
              .append(String.format("%,d (%.2f%%)", retained, pct)).append(" bytes.");
        }
        if (!topC.isEmpty()) {
            sb.append(" The top consumers of its minimum retained heap are ");
            for (int i = 0; i < topC.size(); i++) {
                TopConsumer tc = topC.get(i);
                if (i > 0) sb.append(i == topC.size() - 1 ? ", and " : ", ");
                sb.append(tc.className())
                  .append(" (").append(String.format("%,d", tc.instanceCount()))
                  .append(" instances totaling ").append(String.format("%,d", tc.totalShallowBytes()))
                  .append(")");
            }
            sb.append(".");
        }
        return sb.toString();
    }

    private static List<StackFrameEntry> buildStackFrames(HeapGraph graph, int rootNode) {
        if (graph.stackTraces == null) return List.of();
        long addr = graph.idMap.addressAt(rootNode);
        List<StackTraceData.Frame> frames = graph.stackTraces.framesForThread(addr);
        List<Integer> locals = graph.stackTraces.threadLocalIndices.getOrDefault(addr, List.of());

        List<StackFrameEntry> result = new ArrayList<>();
        int maxFrames = Math.min(frames.size(), 10);
        for (int fi = 0; fi < maxFrames; fi++) {
            StackTraceData.Frame f = frames.get(fi);
            String localAddr = "";
            String localClass = "";
            long localRetained = 0;
            if (fi < locals.size()) {
                int localIdx = locals.get(fi);
                localAddr = hexAddr(graph, localIdx);
                localClass = className(graph, localIdx);
                localRetained = graph.retainedSizeOf(localIdx);
            }
            result.add(new StackFrameEntry(
                f.methodName(), f.methodSig(), f.sourceFile(), f.lineNumber(),
                localAddr, localClass, localRetained
            ));
        }
        return result;
    }

    private static List<PathStep> buildPath(HeapGraph graph, int startNode, int maxDepth) {
        List<PathStep> path = new ArrayList<>();
        int current = startNode;
        for (int depth = 0; depth < maxDepth; depth++) {
            path.add(new PathStep(depth, hexAddr(graph, current),
                className(graph, current), graph.retainedSizeOf(current)));
            int bestChild = -1; long bestRetained = 0;
            for (int i = 1; i < graph.N; i++) {
                if (graph.idom[i] == current) {
                    long r = graph.retainedSizeOf(i);
                    if (r > bestRetained) { bestRetained = r; bestChild = i; }
                }
            }
            if (bestChild < 0) break;
            current = bestChild;
        }
        return path;
    }

    // -- Helpers --

    static String hexAddr(HeapGraph graph, int idx) {
        if (idx <= 0 || idx >= graph.N) return "0x0";
        return String.format("0x%016x", graph.idMap.addressAt(idx));
    }

    static String className(HeapGraph graph, int idx) {
        if (idx <= 0 || idx >= graph.N) return "(unknown)";
        short ci = graph.classIndex[idx];
        if (ci < 0 || ci >= graph.classList.size()) return "(class object)";
        return ClassNames.pretty(graph.classList.get(ci).name());
    }

    private static String loaderName(HeapGraph graph, short classIdx) {
        if (classIdx < 0 || classIdx >= graph.classList.size()) return "<bootstrap>";
        return loaderName(graph, (int) classIdx);
    }

    private static String topPackage(String name) {
        while (name.startsWith("[")) name = name.substring(1);
        if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
        name = name.replace("/", ".");
        int dot = name.indexOf('.');
        if (dot < 0) return name;
        int dot2 = name.indexOf('.', dot + 1);
        return dot2 < 0 ? name : name.substring(0, dot2);
    }
}
