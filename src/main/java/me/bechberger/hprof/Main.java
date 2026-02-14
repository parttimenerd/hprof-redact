/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "heap-dump-filter",
        mixinStandardHelpOptions = true,
        description = "Stream and redact HPROF heap dumps."
)
public class Main implements Callable<Integer> {

    @Option(names = {"-i", "--input"}, required = true, description = "Input HPROF path or '-' for stdin.")
    private String input;

    @Option(names = {"-o", "--output"}, required = true, description = "Output HPROF path or '-' for stdout.")
    private String output;

    @Option(names = {"-t", "--transformer"}, defaultValue = "zero",
            description = "Transformer to apply (default: ${DEFAULT-VALUE}). Options: " +
                    "zero (zero primitives + string contents), " +
                    "zero-strings (zero string contents only), " +
                    "drop-strings (empty string contents).")
    private String transformer;

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new Main(), args));
    }

    @Override
    public Integer call() throws IOException {
        HprofTransformer transformerImpl = resolveTransformer(transformer);

        if ("-".equals(input)) {
            throw new IllegalArgumentException("stdin is not supported; input must be a file path");
        }

        if ("-".equals(output)) {
            HprofFilter.filter(Path.of(input), System.out, transformerImpl);
            return 0;
        }

        try (OutputStream out = HprofIo.openOutputStream(Path.of(output))) {
            HprofFilter.filter(Path.of(input), out, transformerImpl);
        }

        return 0;
    }

    private static HprofTransformer resolveTransformer(String name) {
        return TransformerOption.fromOption(name).create();
    }
}