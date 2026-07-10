/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.cli;

import me.bechberger.femtocli.FemtoCli;
import me.bechberger.femtocli.annotations.Command;

@Command(
        name = "hprof-tools",
        mixinStandardHelpOptions = true,
        description = "HPROF heap dump tools: redact sensitive data, diagnose structure, generate analysis reports.",
        version = "0.4.0",
        subcommands = {RedactCommand.class, DiagnoseCommand.class, ViewsCommand.class}
)
public class Main {

    public static void main(String[] args) {
        System.exit(FemtoCli.run(new Main(), args));
    }
}
