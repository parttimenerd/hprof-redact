/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.io.IOException;
import java.util.*;

import static me.bechberger.hprof.HprofConstants.*;

/**
 * Third-pass HPROF reader: parses HPROF_FRAME, HPROF_TRACE, HPROF_START_THREAD records
 * and correlates JAVA_FRAME/JNI_LOCAL roots to their owning thread objects.
 * Only instantiated when --stack-traces is passed.
 */
public final class StackTraceReader {

    private StackTraceReader() {}

    /**
     * Performs a third pass over the HPROF file at graph.sourcePath.
     * Populates graph.stackTraces.
     * graph.idMap must already be sorted (post-build).
     */
    public static void read(HeapGraph graph) throws IOException {
        Map<Long, String>         utf8Map     = new LinkedHashMap<>();
        Map<Long, StackTraceData.Frame>   frames  = new LinkedHashMap<>();
        Map<Integer, StackTraceData.Trace> traces  = new LinkedHashMap<>();
        Map<Integer, StackTraceData.ThreadInfo> threads = new LinkedHashMap<>();

        try (HeapGraphBuilder.Parser p = new HeapGraphBuilder.Parser(graph.sourcePath)) {
            int ids = p.idSize();
            while (true) {
                int tag = p.readTag();
                if (tag < 0) break;
                p.readU4(); // timestamp
                long length = p.readU4();

                switch (tag) {
                    case HPROF_UTF8 -> {
                        long nameId = p.readId();
                        byte[] bytes = p.readBytes((int)(length - ids));
                        utf8Map.put(nameId, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
                    }
                    case HPROF_FRAME -> {
                        long frameId        = p.readId();
                        long methodNameId   = p.readId();
                        long methodSigId    = p.readId();
                        long sourceFileId   = p.readId();
                        p.readU4(); // classSerial (not needed)
                        int lineNumber = (int) p.readU4();
                        frames.put(frameId, new StackTraceData.Frame(
                            frameId,
                            utf8Map.getOrDefault(methodNameId, "?"),
                            utf8Map.getOrDefault(methodSigId, "?"),
                            utf8Map.getOrDefault(sourceFileId, "?"),
                            lineNumber
                        ));
                    }
                    case HPROF_TRACE -> {
                        int traceSerial  = (int) p.readU4();
                        int threadSerial = (int) p.readU4();
                        int frameCount   = (int) p.readU4();
                        List<Long> frameIds = new ArrayList<>(frameCount);
                        for (int i = 0; i < frameCount; i++) frameIds.add(p.readId());
                        traces.put(traceSerial, new StackTraceData.Trace(traceSerial, threadSerial, frameIds));
                    }
                    case HPROF_START_THREAD -> {
                        int threadSerial   = (int) p.readU4();
                        long threadObjId   = p.readId();
                        p.readU4(); // stackTraceSerial
                        long threadNameId  = p.readId();
                        p.readId(); // threadGroupNameId
                        p.readId(); // threadGroupParentNameId
                        threads.put(threadSerial, new StackTraceData.ThreadInfo(
                            threadSerial, threadObjId,
                            utf8Map.getOrDefault(threadNameId, "Thread-" + threadSerial)
                        ));
                    }
                    default -> p.skipFully(length);
                }
            }
        }

        // Build threadObjectId -> local object indices map from graph's syntheticSrc/Dst
        // (syntheticSrc[i] = thread object index, syntheticDst[i] = local object index)
        // After build(), syntheticSrc/Dst are null -- we reconstruct from GC root data.
        // We use threads map (threadObjectId) to index into idMap.
        Map<Long, List<Integer>> threadLocalIndices = new LinkedHashMap<>();
        for (var ti : threads.values()) {
            long threadObjId = ti.threadObjectId();
            int threadIdx = graph.idMap.indexOf(threadObjId);
            if (threadIdx < 1) continue;
            // find all nodes whose idom == threadIdx (direct thread-dominated locals)
            List<Integer> locals = new ArrayList<>();
            for (int i = 1; i < graph.N; i++) {
                if (graph.idom[i] == threadIdx) locals.add(i);
            }
            threadLocalIndices.put(threadObjId, locals);
        }

        graph.stackTraces = new StackTraceData(frames, traces, threads, threadLocalIndices);
    }
}
