/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.List;
import java.util.Map;

/**
 * Stack frame and trace data parsed from HPROF_FRAME, HPROF_TRACE, HPROF_START_THREAD records.
 * Only populated when --stack-traces is passed (third HPROF pass).
 */
public final class StackTraceData {

    /** A single stack frame from HPROF_FRAME. */
    public record Frame(
        long frameId,
        String methodName,    // from HPROF_UTF8 lookup
        String methodSig,     // from HPROF_UTF8 lookup
        String sourceFile,    // from HPROF_UTF8 lookup
        int lineNumber        // negative = special (e.g. -1 = unknown, -2 = compiled, -3 = native)
    ) {}

    /** A stack trace from HPROF_TRACE: ordered list of frame IDs, associated with a thread serial. */
    public record Trace(int traceSerial, int threadSerial, List<Long> frameIds) {}

    /** A thread descriptor from HPROF_START_THREAD. */
    public record ThreadInfo(int threadSerial, long threadObjectId, String threadName) {}

    /** frameId -> Frame */
    public final Map<Long, Frame> frames;
    /** traceSerial -> Trace */
    public final Map<Integer, Trace> traces;
    /** threadSerial -> ThreadInfo */
    public final Map<Integer, ThreadInfo> threads;
    /** threadObjectId -> list of local object indices (from JAVA_FRAME/JNI_LOCAL roots) */
    public final Map<Long, List<Integer>> threadLocalIndices;

    public StackTraceData(
            Map<Long, Frame> frames,
            Map<Integer, Trace> traces,
            Map<Integer, ThreadInfo> threads,
            Map<Long, List<Integer>> threadLocalIndices) {
        this.frames = frames;
        this.traces = traces;
        this.threads = threads;
        this.threadLocalIndices = threadLocalIndices;
    }

    /** Ordered frames for the given thread object ID, most recent first. Returns empty list if not found. */
    public List<Frame> framesForThread(long threadObjectId) {
        ThreadInfo ti = threads.values().stream()
            .filter(t -> t.threadObjectId() == threadObjectId)
            .findFirst().orElse(null);
        if (ti == null) return List.of();
        Trace trace = traces.values().stream()
            .filter(t -> t.threadSerial() == ti.threadSerial())
            .findFirst().orElse(null);
        if (trace == null) return List.of();
        return trace.frameIds().stream()
            .map(frames::get)
            .filter(f -> f != null)
            .toList();
    }

    /** Thread name for the given thread object ID, or null. */
    public String threadName(long threadObjectId) {
        return threads.values().stream()
            .filter(t -> t.threadObjectId() == threadObjectId)
            .map(ThreadInfo::threadName)
            .findFirst().orElse(null);
    }
}
