/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.cli;

import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.hprof.core.HprofIO;
import me.bechberger.hprof.redact.HprofRedact;
import me.bechberger.hprof.redact.TransformerOption;
import me.bechberger.hprof.redact.transformer.HprofTransformer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "redact",
        mixinStandardHelpOptions = true,
        description = "Stream and redact HPROF heap dumps.",
        version = "0.4.0"
)
public class RedactCommand implements Callable<Integer> {

    @Parameters(description = "Input HPROF path.")
    private String input;

    @Parameters(description = "Output HPROF path or '-' for stdout.")
    private String output;

    @Option(names = {"-t", "--transformer"}, defaultValue = "zero",
            description = "Transformer to apply (default: ${DEFAULT-VALUE}). Options: " +
                    "zero (zero primitives + string contents), " +
                    "zero-strings (zero string contents only), " +
                    "drop-strings (empty string contents).")
    private String transformer;

    @Option(names = {"-v", "--verbose"},
        description = "Log changed field values.")
    private boolean verbose;

    @Option(names = {"--compress"},
            description = "Enable compression format (write only lengths for array/string data, not entries, custom format for testing only).")
    private boolean compress;

    @Option(names = {"--dry-run"},
            description = "Process the file without writing output (useful for testing compression ratio).")
    private boolean dryRun;

    @Override
    public Integer call() throws IOException {
        HprofTransformer transformerImpl = TransformerOption.fromOption(transformer).create();

        if ("-".equals(input)) {
            throw new IllegalArgumentException("stdin is not supported; input must be a file path");
        }

        Path inputPath = Path.of(input);
        long inputSize = Files.size(inputPath);

        HprofRedact filter = new HprofRedact(transformerImpl, verbose ? System.out : null, compress);

        if (dryRun) {
            filter.process(inputPath, new NullOutputStream());
            if (compress) {
                System.err.printf("Dry run: processed %d bytes (compression would reduce to estimate)%n", inputSize);
            }
            return 0;
        }

        if ("-".equals(output)) {
            filter.process(inputPath, System.out);
            return 0;
        }

        Path outputPath = Path.of(output);
        try (OutputStream out = HprofIO.openOutputStream(outputPath)) {
            filter.process(inputPath, out);
        }

        if (compress) {
            long outputSize = Files.size(outputPath);
            double ratio = (double) outputSize / inputSize;
            System.err.printf("Compression ratio: %.2f%% (%d → %d bytes)%n",
                ratio * 100, inputSize, outputSize);
        }

        return 0;
    }

    private static class NullOutputStream extends OutputStream {
        @Override public void write(int b) {}
        @Override public void write(byte[] b) {}
        @Override public void write(byte[] b, int off, int len) {}
    }
}
