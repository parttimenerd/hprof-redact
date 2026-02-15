/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.cli;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;
import me.bechberger.femtocli.annotations.Option;
import me.bechberger.femtocli.annotations.Parameters;
import me.bechberger.hprof.HprofFilter;
import me.bechberger.hprof.HprofIO;
import me.bechberger.hprof.TransformerOption;
import me.bechberger.hprof.transformer.HprofTransformer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(
        name = "hprof-redact",
        mixinStandardHelpOptions = true,
        description = "Stream and redact HPROF heap dumps."
)
public class Main implements Callable<Integer> {

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

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new Main(), args));
    }

    @Override
    public Integer call() throws IOException {
        HprofTransformer transformerImpl = resolveTransformer(transformer);

        if ("-".equals(input)) {
            throw new IllegalArgumentException("stdin is not supported; input must be a file path");
        }

        HprofFilter filter = new HprofFilter(transformerImpl, verbose ? System.out : null);

        if ("-".equals(output)) {
            filter.filter(Path.of(input), System.out);
            return 0;
        }

        try (OutputStream out = HprofIO.openOutputStream(Path.of(output))) {
            filter.filter(Path.of(input), out);
        }

        return 0;
    }

    private static HprofTransformer resolveTransformer(String name) {
        return TransformerOption.fromOption(name).create();
    }
}