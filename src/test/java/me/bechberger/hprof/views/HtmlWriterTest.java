/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static me.bechberger.hprof.core.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlWriterTest {

    @TempDir Path tmpDir;

    @Test
    void htmlOutputContainsAllTabsAndData() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Node")
                .addUtf8(10L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)
                .addInstanceObject(0x200L, 0x10L, 0x300L)
                .addInstanceObject(0x300L, 0x10L, 0L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();
        HeapGraph graph = new HeapGraphBuilder(hprof).build();
        Path out = tmpDir.resolve("report.html");
        new HtmlWriter(graph).writeTo(out);
        String html = Files.readString(out);

        assertTrue(html.startsWith("<!DOCTYPE html>"), "must be valid HTML");
        assertTrue(html.contains("window.__DATA__"), "must embed JSON data blob");
        assertTrue(html.contains("data-tab=\"overview\""), "must have overview tab");
        assertTrue(html.contains("data-tab=\"histogram\""), "must have histogram tab");
        assertTrue(html.contains("data-tab=\"suspects\""), "must have suspects tab");
        assertTrue(html.contains("data-tab=\"threads\""), "must have threads tab");
        assertTrue(html.contains("com.example.Node"), "class name must appear in output");

        // JSON summary block present and valid structure
        int dataStart = html.indexOf("window.__DATA__=") + "window.__DATA__=".length();
        int dataEnd   = html.indexOf(";", dataStart);
        String json   = html.substring(dataStart, dataEnd).trim();
        assertTrue(json.startsWith("{") && json.endsWith("}"), "DATA must be JSON object");
        assertTrue(json.contains("\"summary\""), "JSON must have summary key");
        assertTrue(json.contains("\"objectPieSlices\""), "JSON must have pie slice data");
    }
}
