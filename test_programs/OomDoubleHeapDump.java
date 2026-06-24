import com.sun.management.HotSpotDiagnosticMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reproduces the "file 2× larger than MAT heap" anomaly.
 *
 * The JVM's -XX:+HeapDumpOnOutOfMemoryError handler appends to an existing
 * HPROF file rather than truncating it. If the same path was already written
 * by a previous run (or a manual jmap dump), the file becomes a concatenation
 * of two complete HPROF streams. Eclipse MAT parses only the first stream and
 * reports ~half the on-disk size as "heap size".
 *
 * This program demonstrates the mechanism in two ways:
 *   Mode 1 (default): uses HotSpotDiagnosticMXBean to write the first dump,
 *     then deliberately triggers OOM so the JVM's handler appends a second dump.
 *   Mode 2 (--manual): uses HotSpotDiagnosticMXBean twice, deleting the file
 *     between dumps and manually concatenating them to simulate the effect.
 *
 * Usage:
 *   javac OomDoubleHeapDump.java
 *   java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError \
 *        -XX:HeapDumpPath=/tmp/double.hprof \
 *        OomDoubleHeapDump /tmp/double.hprof
 *
 * Observe that /tmp/double.hprof is ~2× the size of a single dump.
 *
 * Then run:
 *   hprof-redact diagnose /tmp/double.hprof
 */
public class OomDoubleHeapDump {

    private static final List<byte[]> HEAP = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        String dumpPath = args.length > 0 ? args[0] : "/tmp/double_heap.hprof";
        boolean manualMode = args.length > 1 && "--manual".equals(args[1]);

        if (manualMode) {
            runManualMode(dumpPath);
        } else {
            runOomMode(dumpPath);
        }
    }

    /**
     * Manual mode: uses HotSpotDiagnosticMXBean to write two separate dumps,
     * then concatenates them to simulate the append behavior.
     * Useful on JVMs that refuse to dump to an existing file.
     */
    private static void runManualMode(String dumpPath) throws Exception {
        Path path = Path.of(dumpPath);
        Path dump1 = Path.of(dumpPath + "-1.hprof");
        Path dump2 = Path.of(dumpPath + "-2.hprof");

        Files.deleteIfExists(path);
        Files.deleteIfExists(dump1);
        Files.deleteIfExists(dump2);

        // Fill heap with ~20 MB of retained objects
        System.out.println("Filling heap with ~20 MB of retained objects...");
        for (int i = 0; i < 200; i++) {
            HEAP.add(new byte[100_000]);
        }

        HotSpotDiagnosticMXBean diag = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);

        System.out.println("Writing first dump to " + dump1);
        diag.dumpHeap(dump1.toString(), false);
        long size1 = Files.size(dump1);
        System.out.printf("  First dump: %.2f MB%n", size1 / 1_048_576.0);

        System.out.println("Writing second dump to " + dump2);
        diag.dumpHeap(dump2.toString(), false);
        long size2 = Files.size(dump2);
        System.out.printf("  Second dump: %.2f MB%n", size2 / 1_048_576.0);

        // Concatenate via stream copy — avoids loading both dumps into heap at once
        System.out.println("Concatenating dumps to " + dumpPath + " (simulating OOM-handler append)...");
        try (var out = java.nio.file.Files.newOutputStream(path);
             var in1 = java.nio.file.Files.newInputStream(dump1);
             var in2 = java.nio.file.Files.newInputStream(dump2)) {
            in1.transferTo(out);
            in2.transferTo(out);
        }

        Files.deleteIfExists(dump1);
        Files.deleteIfExists(dump2);

        long totalSize = Files.size(path);
        System.out.printf("%nResult: %s%n", dumpPath);
        System.out.printf("  On-disk size:        %.2f MB%n", totalSize / 1_048_576.0);
        System.out.printf("  MAT heap size (est): %.2f MB  (first dump only)%n", size1 / 1_048_576.0);
        System.out.printf("  Ratio:               %.2f×%n", (double) totalSize / size1);
        printInstructions(dumpPath, size1, totalSize);
    }

    /**
     * OOM mode: triggers real OutOfMemoryError so the JVM's OOM handler appends
     * a second dump to the file that was already written by a previous run.
     *
     * Prerequisites: run the program once to create the initial file, then run
     * again with the SAME -XX:HeapDumpPath pointing to that file. The JVM will
     * append on the second OOM.
     *
     * The program writes an initial dump via the MXBean, then triggers OOM
     * so the handler appends.
     */
    private static void runOomMode(String dumpPath) throws Exception {
        Path path = Path.of(dumpPath);

        // Fill heap with ~20 MB of retained objects
        System.out.println("Filling heap with ~20 MB of retained objects...");
        for (int i = 0; i < 200; i++) {
            HEAP.add(new byte[100_000]);
        }

        // If the file doesn't exist yet, create the first dump and exit.
        // The user then re-runs the program to trigger OOM and append.
        if (!path.toFile().exists()) {
            HotSpotDiagnosticMXBean diag = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            System.out.println("Creating initial dump at " + dumpPath);
            diag.dumpHeap(dumpPath, false);
            long size = Files.size(path);
            System.out.printf("Initial dump written: %.2f MB%n", size / 1_048_576.0);
            System.out.println();
            System.out.println("Now re-run with the SAME -XX:HeapDumpPath to trigger OOM and append:");
            System.out.println("  java -Xmx64m -XX:+HeapDumpOnOutOfMemoryError \\");
            System.out.println("       -XX:HeapDumpPath=" + dumpPath + " \\");
            System.out.println("       OomDoubleHeapDump " + dumpPath);
            return;
        }

        long sizeBeforeOom = Files.size(path);
        System.out.printf("Existing dump found: %.2f MB%n", sizeBeforeOom / 1_048_576.0);
        System.out.println("Triggering OutOfMemoryError so JVM appends second dump...");

        // Exhaust remaining heap to trigger OOM
        try {
            List<byte[]> extra = new ArrayList<>();
            while (true) {
                extra.add(new byte[10_000_000]);
            }
        } catch (OutOfMemoryError e) {
            // JVM's -XX:+HeapDumpOnOutOfMemoryError handler has appended the second dump now.
            long sizeAfterOom = Files.size(path);
            System.out.printf("%nResult: %s%n", dumpPath);
            System.out.printf("  Size before OOM: %.2f MB%n", sizeBeforeOom / 1_048_576.0);
            System.out.printf("  Size after OOM:  %.2f MB%n", sizeAfterOom / 1_048_576.0);
            System.out.printf("  Ratio:           %.2f×%n", (double) sizeAfterOom / sizeBeforeOom);
            printInstructions(dumpPath, sizeBeforeOom, sizeAfterOom);
        }
    }

    private static void printInstructions(String dumpPath, long singleDumpBytes, long totalBytes) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("  The file contains two concatenated HPROF streams.");
        System.out.printf("  MAT reports: ~%.0f MB heap%n", singleDumpBytes / 1_048_576.0);
        System.out.printf("  On disk:     ~%.0f MB%n", totalBytes / 1_048_576.0);
        System.out.println("  MAT silently ignores the second stream.");
        System.out.println();
        System.out.println("  Run hprof-redact diagnose to see the anomaly flagged:");
        System.out.println("    hprof-redact diagnose " + dumpPath);
        System.out.println("═══════════════════════════════════════════════════════");
    }
}
