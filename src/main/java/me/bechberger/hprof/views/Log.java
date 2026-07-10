/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/**
 * Process-wide verbosity for the views pipeline. Thread-hostile (single-threaded pipeline only).
 *
 * Level 0 (default): phase summary line only.
 * Level 1 (-v):      + per-phase RSS and object/edge counts.
 * Level 2 (-vv):     + mid-phase sub-step RSS probes (A2b fill/encode, A2c alloc, DOM internals).
 */
public final class Log {
    private Log() {}

    private static int level = 0;

    public static void setLevel(int v) { level = v; }
    public static int  level()         { return level; }

    /** Always printed (level 0+). */
    public static void info(String fmt, Object... args) {
        System.err.printf(fmt + "%n", args);
    }

    /** Printed at level 1+ (-v). */
    public static void verbose(String fmt, Object... args) {
        if (level >= 1) System.err.printf(fmt + "%n", args);
    }

    /** Printed at level 2+ (-vv). */
    public static void debug(String fmt, Object... args) {
        if (level >= 2) System.err.printf(fmt + "%n", args);
    }

    /** Returns current process RSS in KB, or -1 if unavailable (non-Linux). */
    public static long rssKb() {
        if (level < 1) return -1;
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) return Long.parseLong(line.replaceAll("[^0-9]", ""));
            }
        } catch (Exception ignored) {}
        return -1;
    }
}
