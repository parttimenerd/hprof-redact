/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.util.ArrayDeque;

/**
 * Verbosity-aware phase logger that prints wall-clock time and RSS to stderr.
 *
 * <p>Verbosity levels:
 * <ul>
 *   <li>0 — only essential progress messages (always printed)</li>
 *   <li>1 — per-phase wall-clock time and RSS at end of each phase</li>
 *   <li>2 — sub-phase detail and additional debug info</li>
 * </ul>
 */
public final class PhaseLogger {

    private final int verbosity;
    private final long sessionStart = System.currentTimeMillis();

    // Stack of (phaseName, startMillis, startRssBytes)
    private final ArrayDeque<long[]> phaseStack = new ArrayDeque<>();
    // Store phase names separately
    private final ArrayDeque<String> phaseNames = new ArrayDeque<>();

    private static final com.sun.management.OperatingSystemMXBean osBean;

    static {
        com.sun.management.OperatingSystemMXBean bean = null;
        try {
            OperatingSystemMXBean raw = ManagementFactory.getOperatingSystemMXBean();
            if (raw instanceof com.sun.management.OperatingSystemMXBean) {
                bean = (com.sun.management.OperatingSystemMXBean) raw;
            }
        } catch (Exception ignored) {}
        osBean = bean;
    }

    public PhaseLogger(int verbosity) {
        this.verbosity = verbosity;
    }

    public int verbosity() { return verbosity; }

    /** Always printed (verbosity >= 0). */
    public void info(String fmt, Object... args) {
        System.err.printf(fmt + "%n", args);
    }

    /** Printed only at verbosity >= 1. */
    public void verbose(String fmt, Object... args) {
        if (verbosity >= 1) System.err.printf(fmt + "%n", args);
    }

    /** Printed only at verbosity >= 2. */
    public void debug(String fmt, Object... args) {
        if (verbosity >= 2) System.err.printf(fmt + "%n", args);
    }

    /** Begin a named phase (records start time and RSS). */
    public void startPhase(String name) {
        phaseStack.push(new long[]{System.currentTimeMillis(), currentRssBytes()});
        phaseNames.push(name);
    }

    /**
     * End the most recently started phase and print a line at verbosity >= 1.
     * {@code messageFmt} is formatted with {@code args} and printed after timing/RSS.
     */
    public void endPhase(String name, String messageFmt, Object... args) {
        if (phaseStack.isEmpty()) return;
        long[] entry = phaseStack.pop();
        phaseNames.pop();
        long wallMs = System.currentTimeMillis() - entry[0];
        long rssBytes = currentRssBytes();

        String message = args.length == 0 ? messageFmt : String.format(messageFmt, args);
        // Always print the summary line (e.g. "Heap graph built (N objects, M GC roots)")
        if (verbosity >= 1) {
            System.err.printf("[%s] %s  (%.1fs, RSS %s)%n",
                    name, message, wallMs / 1000.0, formatBytes(rssBytes));
        } else {
            System.err.printf("%s%n", message);
        }
    }

    /** Print total elapsed time at the end. Always printed. */
    public void summary() {
        long elapsed = System.currentTimeMillis() - sessionStart;
        if (verbosity >= 1) {
            System.err.printf("Total elapsed: %.1fs, RSS %s%n",
                    elapsed / 1000.0, formatBytes(currentRssBytes()));
        } else {
            System.err.printf("Total elapsed: %.1fs%n", elapsed / 1000.0);
        }
    }

    public static long currentRssBytes() {
        if (osBean != null) {
            try {
                long v = osBean.getProcessCpuTime(); // warm up the bean
                return osBean.getCommittedVirtualMemorySize();
            } catch (Exception ignored) {}
        }
        // Fallback: use /proc/self/status on Linux
        try {
            String status = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("/proc/self/status")));
            for (String line : status.split("\n")) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 2) return Long.parseLong(parts[1]) * 1024;
                }
            }
        } catch (Exception ignored) {}
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "?";
        if (bytes < 1024L * 1024) return String.format("%.0f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
