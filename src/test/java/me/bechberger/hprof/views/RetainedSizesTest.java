/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;

import static me.bechberger.hprof.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class RetainedSizesTest {

    private HeapGraph buildGraph(HprofTestBuilder builder) throws Exception {
        var path = builder.buildToPath();
        return new HeapGraphBuilder(path).build();
    }

    /**
     * Single leaf object. retained(A) == shallow(A).
     */
    @Test
    void singleLeafRetainedEqualsShallow() throws Exception {
        HprofTestBuilder b = new HprofTestBuilder(4)
                .addUtf8(1L, "java/lang/Object")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 32)
                .addInstanceObject(0x100L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(b);
        int A = graph.idMap.indexOf(0x100L) + 1;
        assertTrue(A > 0);

        long retainedA = graph.retainedSizeOf(A);
        long shallowA  = graph.shallowSizeOf(A);
        assertEquals(shallowA, retainedA, "leaf: retained == shallow");
    }

    /**
     * Linear chain: A → B → C.  A is GC root.
     * idom: B=A, C=B.
     * retained(C) = shallow(C)
     * retained(B) = shallow(B) + shallow(C)
     * retained(A) = shallow(A) + shallow(B) + shallow(C)
     */
    @Test
    void linearChainRetainedBubblesUp() throws Exception {
        HprofTestBuilder b = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)  // A.next=B
                .addInstanceObject(0x200L, 0x10L, 0x300L)  // B.next=C
                .addInstanceObject(0x300L, 0x10L, 0L)       // C.next=null
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(b);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;

        long sA = graph.shallowSizeOf(A);
        long sB = graph.shallowSizeOf(B);
        long sC = graph.shallowSizeOf(C);

        assertEquals(sC,          graph.retainedSizeOf(C), "C: leaf");
        assertEquals(sB + sC,     graph.retainedSizeOf(B), "B retains C");
        assertEquals(sA + sB + sC, graph.retainedSizeOf(A), "A retains B+C");
    }

    /**
     * Diamond: VRoot→A (root), VRoot→B (root), A→C, B→C.
     * idom[C] = VRoot, so C's size is NOT counted in either A or B.
     * retained(A) = shallow(A)
     * retained(B) = shallow(B)
     * retained(C) = shallow(C)
     */
    @Test
    void diamondNoDoubleCount() throws Exception {
        HprofTestBuilder b = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "ref")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x300L)  // A.ref=C
                .addInstanceObject(0x200L, 0x10L, 0x300L)  // B.ref=C
                .addInstanceObject(0x300L, 0x10L, 0L)       // C
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .addGCRoot(0x200L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(b);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;

        assertEquals(graph.shallowSizeOf(A), graph.retainedSizeOf(A), "A not retaining C");
        assertEquals(graph.shallowSizeOf(B), graph.retainedSizeOf(B), "B not retaining C");
        assertEquals(graph.shallowSizeOf(C), graph.retainedSizeOf(C), "C: leaf");
    }

    /**
     * Two independent trees: VRoot→A→B and VRoot→C→D.
     * retained(A) = shallow(A)+shallow(B), retained(C) = shallow(C)+shallow(D)
     */
    @Test
    void twoIndependentTrees() throws Exception {
        HprofTestBuilder b = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "ref")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)  // A→B
                .addInstanceObject(0x200L, 0x10L, 0L)
                .addInstanceObject(0x300L, 0x10L, 0x400L)  // C→D
                .addInstanceObject(0x400L, 0x10L, 0L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .addGCRoot(0x300L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(b);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;
        int D = graph.idMap.indexOf(0x400L) + 1;

        assertEquals(graph.shallowSizeOf(A) + graph.shallowSizeOf(B), graph.retainedSizeOf(A));
        assertEquals(graph.shallowSizeOf(B), graph.retainedSizeOf(B));
        assertEquals(graph.shallowSizeOf(C) + graph.shallowSizeOf(D), graph.retainedSizeOf(C));
        assertEquals(graph.shallowSizeOf(D), graph.retainedSizeOf(D));
    }
}
