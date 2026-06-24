/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import java.io.PrintWriter;

/** JSON report output — not yet implemented. */
public final class JsonReportWriter {
    private JsonReportWriter() {}

    public static void write(DiagnosticReport report, PrintWriter out) {
        out.println("{\"error\": \"JSON output not yet implemented\"}");
    }
}
