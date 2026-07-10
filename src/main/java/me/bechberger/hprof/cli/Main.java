/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.cli;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Command(
        name = "hprof-tools",
        mixinStandardHelpOptions = true,
        description = "HPROF heap dump tools: redact sensitive data, diagnose structure, generate analysis reports.",
        version = "0.4.0",
        subcommands = {RedactCommand.class, DiagnoseCommand.class, ViewsCommand.class}
)
public class Main {

    /** JVM flags injected when re-executing for the views subcommand. */
    static final List<String> OPTIMAL_GC_FLAGS = List.of(
            "-XX:+UseG1GC",
            "-XX:G1PeriodicGCInterval=20",
            "-XX:+G1PeriodicGCInvokesConcurrent",
            "-XX:MinHeapFreeRatio=2",
            "-XX:MaxHeapFreeRatio=2",
            "-XX:G1HeapRegionSize=2m",
            "-XX:G1PeriodicGCSystemLoadThreshold=0.0",
            "-XX:SoftRefLRUPolicyMSPerMB=500"
    );

    /** Sentinel JVM flag added on re-exec so we don't restart again. */
    private static final String RESTARTED_FLAG = "-DhprofGcOptimized=1";

    public static void main(String[] args) throws Exception {
        // For the `views` subcommand, re-exec with GC flags if not already applied.
        // This must happen before any heap allocation so the flags take full effect.
        if (isViewsCommand(args) && !alreadyOptimized()) {
            System.exit(reexec(args));
        }
        System.exit(FemtoCli.run(new Main(), args));
    }

    private static boolean isViewsCommand(String[] args) {
        for (String a : args) {
            if (a.equals("views")) return true;
            // Stop at first non-flag argument that isn't "views"
            if (!a.startsWith("-")) return false;
        }
        return false;
    }

    private static boolean alreadyOptimized() {
        return "1".equals(System.getProperty("hprofGcOptimized"));
    }

    /**
     * Re-exec this JVM process with optimal GC flags prepended.
     * Reconstructs the command from ProcessHandle + RuntimeMXBean + sun.java.command.
     * Inherits stdin/stdout/stderr and returns the child's exit code.
     */
    private static int reexec(String[] appArgs) throws Exception {
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");

        // Existing JVM flags (e.g. -Xmx, -Xms set by the user) — exclude GC flags we're replacing
        List<String> existingJvmArgs = new ArrayList<>();
        for (String f : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (!isOverriddenByOptimalGc(f)) existingJvmArgs.add(f);
        }

        // sun.java.command = "<jar-or-class> [app-args...]"
        // We split off only the first token (jar/class path); appArgs already has the rest.
        String sunCmd = System.getProperty("sun.java.command", "");
        String jarOrClass = sunCmd.split(" ")[0];
        boolean isJar = jarOrClass.endsWith(".jar");

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.addAll(OPTIMAL_GC_FLAGS);
        cmd.add(RESTARTED_FLAG);
        cmd.addAll(existingJvmArgs);
        if (isJar) {
            cmd.add("-jar");
            cmd.add(jarOrClass);
        } else {
            // Running from classpath (e.g. tests / IDE)
            cmd.add("-cp");
            cmd.add(ManagementFactory.getRuntimeMXBean().getClassPath());
            cmd.add(jarOrClass);
        }
        cmd.addAll(Arrays.asList(appArgs));

        Process child = new ProcessBuilder(cmd)
                .inheritIO()
                .start();
        return child.waitFor();
    }

    private static boolean isOverriddenByOptimalGc(String flag) {
        // Drop any existing G1Periodic/MinHeapFree/MaxHeapFree/G1HeapRegion/SoftRefLRU/UseG1GC
        // flags that we're supplying ourselves, so we don't pass conflicting duplicates.
        return flag.startsWith("-XX:G1PeriodicGC")
                || flag.startsWith("-XX:MinHeapFreeRatio")
                || flag.startsWith("-XX:MaxHeapFreeRatio")
                || flag.startsWith("-XX:G1HeapRegionSize")
                || flag.startsWith("-XX:G1PeriodicGCSystemLoad")
                || flag.startsWith("-XX:SoftRefLRUPolicyMSPerMB")
                || flag.equals("-XX:+UseG1GC") || flag.equals("-XX:-UseG1GC");
    }
}
