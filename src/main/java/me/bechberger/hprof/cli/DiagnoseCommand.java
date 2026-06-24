/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.hprof.diagnose.DiagnosticReport;
import me.bechberger.hprof.diagnose.HprofDiagnose;
import me.bechberger.hprof.diagnose.JsonReportWriter;
import me.bechberger.hprof.diagnose.TextReportWriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
    name = "diagnose",
    mixinStandardHelpOptions = true,
    description = "Analyze an HPROF heap dump and report size attribution, anomalies, and disk-vs-runtime size discrepancies."
)
public class DiagnoseCommand implements Callable<Integer> {

    @Parameters(description = "Input HPROF path (plain or .gz).")
    private String input;

    @Option(names = {"--output", "-o"}, description = "Write report to file instead of stdout.")
    private String output;

    @Option(names = {"--json"}, description = "Output report as JSON.")
    private boolean json;

    @Option(names = {"--detect-duplicate-ids"},
            description = "Track duplicate object IDs (uses ~16 bytes per object; may OOM on large dumps).")
    private boolean detectDuplicateIds;

    @Option(names = {"--top-n"}, defaultValue = "20",
            description = "Number of top classes/arrays to report (default: ${DEFAULT-VALUE}).")
    private int topN;

    @Option(names = {"--object-align"}, defaultValue = "8",
            description = "JVM object alignment in bytes for heap size estimation (default: ${DEFAULT-VALUE}).")
    private int objectAlign;

    @Option(names = {"--compact-headers"},
            description = "Use compact object header size (8 bytes, JDK 25+ JEP 519) for framing overhead calculation.")
    private boolean compactHeaders;

    @Option(names = {"--histogram"},
            description = "Emit full per-class histogram with framing overhead column (all classes, sorted by on-disk bytes).")
    private boolean histogram;

    @Override
    public Integer call() throws IOException {
        Path inputPath = Path.of(input);

        HprofDiagnose.Options opts = new HprofDiagnose.Options();
        opts.topN = topN;
        opts.objectAlign = objectAlign;
        opts.detectDuplicateIds = detectDuplicateIds;
        opts.histogram = histogram;
        opts.compactHeaders = compactHeaders;

        DiagnosticReport report = HprofDiagnose.diagnose(inputPath, opts);

        try (PrintWriter writer = output != null
                ? new PrintWriter(Files.newBufferedWriter(Path.of(output)))
                : new PrintWriter(System.out, true)) {
            if (json) {
                JsonReportWriter.write(report, writer);
            } else {
                TextReportWriter.write(report, writer);
            }
        }
        return 0;
    }
}
