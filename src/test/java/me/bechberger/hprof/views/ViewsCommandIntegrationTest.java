/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static me.bechberger.hprof.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class ViewsCommandIntegrationTest {

    @TempDir
    Path tmpDir;

    /**
     * End-to-end: synthetic HPROF → build HeapGraph → write Markdown → verify sections present.
     */
    @Test
    void syntheticHprofProducesReport() throws Exception {
        // Build a small but realistic graph: A→B→C (chain), separate D (GC root)
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Node")
                .addUtf8(10L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)   // A.next=B
                .addInstanceObject(0x200L, 0x10L, 0x300L)   // B.next=C
                .addInstanceObject(0x300L, 0x10L, 0L)        // C
                .addInstanceObject(0x400L, 0x10L, 0L)        // D (isolated root)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .addGCRoot(0x400L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).build();

        // Write markdown
        Path mdPath = tmpDir.resolve("report.md");
        StringWriter sw = new StringWriter();
        new MarkdownWriter(graph).write(new PrintWriter(sw));
        String md = sw.toString();
        Files.writeString(mdPath, md);

        // Verify structure
        assertTrue(md.contains("## System Overview"), "should have System Overview section");
        assertTrue(md.contains("## Leak Suspects"),   "should have Leak Suspects section");
        assertTrue(md.contains("## Top Consumers"),   "should have Top Consumers section");
        assertTrue(md.contains("com.example.Node"),   "should mention the test class");

        // Verify histogram has data
        assertTrue(md.contains("### Class Histogram"), "should have class histogram");

        // Verify retained sizes are non-zero
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;
        int D = graph.idMap.indexOf(0x400L) + 1;

        long retainedA = graph.retainedSizeOf(A);
        long retainedB = graph.retainedSizeOf(B);
        long retainedC = graph.retainedSizeOf(C);
        long retainedD = graph.retainedSizeOf(D);

        assertTrue(retainedA > retainedB, "A retains more than B (A retains B+C)");
        assertTrue(retainedB > retainedC, "B retains more than C (B retains C)");
        assertEquals(retainedC, graph.shallowSizeOf(C), "C is a leaf; retained == shallow");
        assertEquals(retainedD, graph.shallowSizeOf(D), "D is isolated; retained == shallow");
    }

    /**
     * Verify the Markdown file can be written to disk and is non-empty.
     */
    @Test
    void reportWrittenToDisk() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "java/lang/Object")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 32)
                .addInstanceObject(0x100L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).build();
        Path mdPath = tmpDir.resolve("out.md");
        new MarkdownWriter(graph).writeTo(mdPath);

        assertTrue(Files.exists(mdPath), "report file should exist");
        assertTrue(Files.size(mdPath) > 100, "report should be non-trivial");
        String content = Files.readString(mdPath);
        assertTrue(content.startsWith("# Heap Dump Analysis:"), "should start with title");
    }
}
