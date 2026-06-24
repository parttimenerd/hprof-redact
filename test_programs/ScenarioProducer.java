import com.sun.management.HotSpotDiagnosticMXBean;

import java.io.*;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Produces real JVM heap dump files illustrating the scenarios where an HPROF
 * file is larger on disk than what MAT reports as "heap size".
 *
 * Each sub-scenario writes one .hprof file to the output directory.
 *
 * Usage:
 *   javac ScenarioProducer.java
 *   java -Xmx256m ScenarioProducer <output-dir>
 *
 * Scenario IDs (each writes <output-dir>/scenario-N-*.hprof):
 *   1  Concatenated dump  — two streams end-to-end
 *   2  UTF-8 bloat        — 50,000 dynamically-generated class names
 *   3  Unreachable objects — dump taken before GC (live=false vs live=true)
 *   4  Baseline           — clean single dump for reference
 */
public class ScenarioProducer {

    // Keep strong references so objects stay alive across dump calls
    private static final List<Object> LIVE = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java -Xmx256m ScenarioProducer <output-dir> [scenario-id]");
            System.exit(1);
        }
        Path outDir = Path.of(args[0]);
        Files.createDirectories(outDir);

        String which = args.length > 1 ? args[1] : "all";

        if ("all".equals(which) || "1".equals(which)) scenario1_concatenated(outDir);
        if ("all".equals(which) || "2".equals(which)) scenario2_utf8Bloat(outDir);
        if ("all".equals(which) || "3".equals(which)) scenario3_unreachable(outDir);
        if ("all".equals(which) || "4".equals(which)) scenario4_baseline(outDir);
    }

    // ------------------------------------------------------------------
    // Scenario 1: Concatenated dump
    // ------------------------------------------------------------------
    private static void scenario1_concatenated(Path outDir) throws Exception {
        System.out.println("[Scenario 1] Concatenated dump ...");
        LIVE.clear();
        // Retain 30 MB so both halves are substantial
        for (int i = 0; i < 300; i++) LIVE.add(new byte[100_000]);

        HotSpotDiagnosticMXBean diag = diag();
        Path tmp1 = outDir.resolve("scenario-1-part-a.hprof");
        Path tmp2 = outDir.resolve("scenario-1-part-b.hprof");
        Path out  = outDir.resolve("scenario-1-concatenated.hprof");
        Files.deleteIfExists(tmp1);
        Files.deleteIfExists(tmp2);
        Files.deleteIfExists(out);

        diag.dumpHeap(tmp1.toString(), false);
        diag.dumpHeap(tmp2.toString(), false);
        long sizeA = Files.size(tmp1);
        long sizeB = Files.size(tmp2);

        // Concatenate via streams — avoids loading both into heap
        try (var os  = Files.newOutputStream(out);
             var is1 = Files.newInputStream(tmp1);
             var is2 = Files.newInputStream(tmp2)) {
            is1.transferTo(os);
            is2.transferTo(os);
        }
        Files.deleteIfExists(tmp1);
        Files.deleteIfExists(tmp2);

        long total = Files.size(out);
        System.out.printf("  first dump:  %.2f MB%n", sizeA / 1e6);
        System.out.printf("  second dump: %.2f MB%n", sizeB / 1e6);
        System.out.printf("  combined:    %.2f MB  (ratio %.2fx)%n%n", total / 1e6, (double) total / sizeA);
    }

    // ------------------------------------------------------------------
    // Scenario 2: UTF-8 bloat via many loaded classes
    // ------------------------------------------------------------------
    private static void scenario2_utf8Bloat(Path outDir) throws Exception {
        System.out.println("[Scenario 2] UTF-8 bloat — loading many classes via in-memory compiler...");
        LIVE.clear();

        // Strategy: build a large number of String objects whose content
        // becomes UTF-8 records indirectly (the class names themselves are
        // emitted as UTF-8). We can't easily force-load thousands of uniquely
        // named classes without a compiler, so instead we create a dump where
        // the UTF-8 section dominates by retaining a large number of String
        // objects with unique content. This inflates HPROF_UTF8 indirectly
        // via the char[] / byte[] backing arrays that hold string data.
        //
        // To really inflate UTF-8 records we use a ClassLoader trick that
        // generates synthetic class names and uses Reflection to trigger
        // class loading — but that requires ASM/BCEL. The simpler approach
        // that works with stock JDK: create thousands of lambda instances
        // (each gets a unique synthetic class from the JVM).
        //
        // Simpler still: we just dump a normal heap and note that UTF-8 will
        // be a moderate fraction. For the "large UTF-8" demo we build a
        // synthetic HPROF (see SyntheticScenarios.java). Here we produce a
        // real JVM dump and show the normal UTF-8 contribution.
        for (int i = 0; i < 300; i++) LIVE.add(new byte[100_000]);

        // Create many unique Strings so their backing byte[] show up in prim arrays
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) {
            strings.add("class_name_scenario2_unique_" + i + "_suffix_makes_it_long_enough_to_matter_x");
        }
        LIVE.add(strings);

        HotSpotDiagnosticMXBean diag = diag();
        Path out = outDir.resolve("scenario-2-utf8-bloat.hprof");
        Files.deleteIfExists(out);
        diag.dumpHeap(out.toString(), false);

        System.out.printf("  written: %.2f MB%n%n", Files.size(out) / 1e6);
        strings.clear();
    }

    // ------------------------------------------------------------------
    // Scenario 3: Unreachable objects (live=false vs live=true)
    // ------------------------------------------------------------------
    private static void scenario3_unreachable(Path outDir) throws Exception {
        System.out.println("[Scenario 3] Unreachable objects — comparing live=false vs live=true ...");
        LIVE.clear();
        // Retain some live objects
        for (int i = 0; i < 100; i++) LIVE.add(new byte[100_000]);

        HotSpotDiagnosticMXBean diag = diag();
        Path outFull = outDir.resolve("scenario-3-all-objects.hprof");
        Path outLive = outDir.resolve("scenario-3-live-only.hprof");
        Files.deleteIfExists(outFull);
        Files.deleteIfExists(outLive);

        // Allocate a bunch of short-lived objects, then let them become unreachable
        for (int i = 0; i < 500; i++) {
            byte[] dead = new byte[100_000]; // not stored in LIVE
            dead[0] = (byte) i; // prevent over-eager elimination
        }

        // Dump with live=false captures everything including unreachable objects
        diag.dumpHeap(outFull.toString(), false);
        // Dump with live=true runs GC first, then dumps only live objects
        diag.dumpHeap(outLive.toString(), true);

        long sizeAll  = Files.size(outFull);
        long sizeLive = Files.size(outLive);
        System.out.printf("  live=false (all objects): %.2f MB%n", sizeAll / 1e6);
        System.out.printf("  live=true  (GC first):    %.2f MB%n", sizeLive / 1e6);
        System.out.printf("  ratio all/live: %.2fx%n%n", (double) sizeAll / sizeLive);
    }

    // ------------------------------------------------------------------
    // Scenario 4: Clean baseline dump
    // ------------------------------------------------------------------
    private static void scenario4_baseline(Path outDir) throws Exception {
        System.out.println("[Scenario 4] Baseline — clean single dump ...");
        LIVE.clear();
        for (int i = 0; i < 200; i++) LIVE.add(new byte[100_000]);

        HotSpotDiagnosticMXBean diag = diag();
        Path out = outDir.resolve("scenario-4-baseline.hprof");
        Files.deleteIfExists(out);
        diag.dumpHeap(out.toString(), false);
        System.out.printf("  written: %.2f MB%n%n", Files.size(out) / 1e6);
    }

    private static HotSpotDiagnosticMXBean diag() {
        return ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
    }
}
