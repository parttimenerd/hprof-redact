/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.hprof.views.HeapGraph;
import me.bechberger.hprof.views.HeapGraphBuilder;
import me.bechberger.hprof.views.HtmlWriter;
import me.bechberger.hprof.views.MarkdownWriter;
import me.bechberger.hprof.views.StackTraceReader;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "views",
        mixinStandardHelpOptions = true,
        description = "Analyze an HPROF heap dump and produce a MAT-equivalent report (Markdown or HTML)."
)
public class ViewsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Input HPROF path.")
    private String input;

    @Parameters(index = "1", arity = "0..1",
            description = "Output path. Extension (.md or .html) determines format. Default: <input>.md or <input>.html.")
    private String output;

    @Option(names = {"--threshold-pct"}, defaultValue = "10.0",
            description = "Retained heap threshold %% to flag as leak suspect (default: ${DEFAULT-VALUE}).")
    private double thresholdPct;

    @Option(names = {"--print-phase-times"},
            description = "Print per-phase wall-clock time and RSS to stderr.")
    private boolean printPhaseTimes;

    @Option(names = {"--html"},
            description = "Force HTML output (overrides extension detection).")
    private boolean forceHtml;

    @Option(names = {"--stack-traces"},
            description = "Parse HPROF stack frames for richer leak suspect analysis (third pass, optional).")
    private boolean stackTraces;

    @Override
    public Integer call() throws IOException {
        Path inputPath = Path.of(input);
        boolean htmlMode = forceHtml || (output != null && output.endsWith(".html"));

        Path outputPath;
        if (output != null) {
            outputPath = Path.of(output);
        } else {
            String base = input.replaceAll("\\.(hprof|bin)(\\.gz)?$", "");
            outputPath = Path.of(base + (htmlMode ? ".html" : ".md"));
        }

        System.err.printf("Building heap graph from %s ...%n", inputPath.getFileName());
        long t0 = System.currentTimeMillis();

        HeapGraph graph;
        try {
            graph = new HeapGraphBuilder(inputPath).build();
        } catch (Exception e) {
            System.err.println("ERROR: failed to parse HPROF: " + e.getMessage());
            e.printStackTrace(System.err);
            return 1;
        }

        long t1 = System.currentTimeMillis();
        System.err.printf("Heap graph built in %.1fs (%,d objects, %,d GC roots)%n",
                (t1 - t0) / 1000.0, graph.objectCount() - 1, graph.gcRootCount());

        if (stackTraces && htmlMode) {
            System.err.println("Parsing stack traces (third pass)...");
            StackTraceReader.read(graph);
        }

        System.err.printf("Writing %s report to %s ...%n", htmlMode ? "HTML" : "Markdown", outputPath.getFileName());
        if (htmlMode) {
            new HtmlWriter(graph, thresholdPct).writeTo(outputPath);
        } else {
            new MarkdownWriter(graph, thresholdPct).writeTo(outputPath);
        }

        long t2 = System.currentTimeMillis();
        System.err.printf("Report written in %.1fs%n", (t2 - t1) / 1000.0);
        System.err.printf("Total elapsed: %.1fs%n", (t2 - t0) / 1000.0);
        return 0;
    }
}
