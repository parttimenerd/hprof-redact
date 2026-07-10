/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.List;
import static me.bechberger.hprof.core.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlReportDataTest {

    /**
     * Chain A->B->C, all same class.
     * idom: A=VRoot, B=A, C=B
     * MAT class-retained: sum retainedSize(v) for v of class C where idom[v] is NOT of class C.
     *   Only A qualifies (idom[A]=VRoot, class -1). B and C are dominated by same-class A.
     *   retainedSize(A) covers A+B+C = 3 * 8 = 24.
     * Per-instance size: Node has super=0, so calculateSizeRecursive returns
     *   pointerSize + refSize = 4 + 4 = 8 (with idSize=4 test setup); alignUp(8,8)=8.
     */
    @Test
    void groupRetainedChain() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Node")
                .addUtf8(10L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)   // A.next=B
                .addInstanceObject(0x200L, 0x10L, 0x300L)   // B.next=C
                .addInstanceObject(0x300L, 0x10L, 0L)        // C
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();
        HeapGraph graph = new HeapGraphBuilder(hprof).keepAddressIndex(true).build();

        HtmlReportData.ReportData data = HtmlReportData.compute(graph);

        HtmlReportData.ClassHistogramEntry entry = data.histogram().stream()
                .filter(e -> e.className().equals("com.example.Node"))
                .findFirst().orElseThrow();

        assertEquals(24L, entry.groupRetainedBytes(),
                "MAT class-retained: only top-ancestor A qualifies; retained(A)=3*8=24");
    }

    @Test
    void biggestObjectHexAddress() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Foo")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16)
                .addInstanceObject(0x100L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();
        HeapGraph graph = new HeapGraphBuilder(hprof).keepAddressIndex(true).build();

        HtmlReportData.ReportData data = HtmlReportData.compute(graph);

        assertFalse(data.biggestObjects().isEmpty());
        assertTrue(data.biggestObjects().get(0).hexAddress().startsWith("0x"));
    }

    @Test
    void leakSuspectNarrativeAndTopConsumers() throws Exception {
        // A->B, A is large top-level dominator (>10% of heap)
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Big")
                .addUtf8(2L, "com/example/Child")
                .addUtf8(10L, "child")
                .addLoadClass(1, 0x10L, 1L)
                .addLoadClass(2, 0x20L, 2L)
                .addClass(0x10L, 0L, 0L, 160, HprofTestBuilder.FieldDef.object(10L))
                .addClass(0x20L, 0L, 0L, 16)
                .addInstanceObject(0x100L, 0x10L, 0x200L)   // Big.child = Child
                .addInstanceObject(0x200L, 0x20L, 0L)        // Child
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();
        HeapGraph graph = new HeapGraphBuilder(hprof).keepAddressIndex(true).build();

        HtmlReportData.ReportData data = HtmlReportData.compute(graph);

        // Should have at least one suspect
        assertFalse(data.leakSuspects().isEmpty());
        HtmlReportData.LeakSuspect s = data.leakSuspects().get(0);
        assertNotNull(s.narrative());
        assertFalse(s.narrative().isEmpty());
        // Top consumers should list com.example.Child
        assertTrue(s.topConsumers().stream()
                .anyMatch(tc -> tc.className().contains("Child")));
    }
}