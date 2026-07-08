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
import me.bechberger.hprof.views.MarkdownWriter;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "views",
        mixinStandardHelpOptions = true,
        description = "Analyze an HPROF heap dump and produce a MAT-equivalent Markdown report."
)
public class ViewsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Input HPROF path.")
    private String input;

    @Parameters(index = "1", arity = "0..1", description = "Output Markdown path (default: <input>.md).")
    private String output;

    @Option(names = {"--threshold-pct"}, defaultValue = "10.0",
            description = "Retained heap threshold %% to flag as leak suspect (default: ${DEFAULT-VALUE}).")
    private double thresholdPct;

    @Override
    public Integer call() throws IOException {
        Path inputPath = Path.of(input);
        Path outputPath = output != null ? Path.of(output)
                : Path.of(input.replaceAll("\\.(hprof|bin)(\\.gz)?$", "") + ".md");

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

        System.err.printf("Writing report to %s ...%n", outputPath.getFileName());
        new MarkdownWriter(graph, thresholdPct).writeTo(outputPath);

        long t2 = System.currentTimeMillis();
        System.err.printf("Report written in %.1fs%n", (t2 - t1) / 1000.0);
        System.err.printf("Total elapsed: %.1fs%n", (t2 - t0) / 1000.0);

        return 0;
    }
}
