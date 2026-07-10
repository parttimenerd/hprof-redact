/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;

import static me.bechberger.hprof.core.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class DominatorTreeTest {

    /**
     * Build a HeapGraph for dominator tree tests without running RetainedSizes.
     * Returns graph after phaseB + RPO + DominatorTree.
     */
    private HeapGraph buildGraph(HprofTestBuilder builder) throws Exception {
        var path = builder.buildToPath();
        HeapGraph graph = new HeapGraphBuilder(path).buildForTesting();
        return graph;
    }

    /**
     * A → B → C → D, A is GC root.
     * idom: A=VRoot, B=A, C=B, D=C (linear chain).
     */
    @Test
    void linearChain() throws Exception {
        HprofTestBuilder b = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)  // A.next=B
                .addInstanceObject(0x200L, 0x10L, 0x300L)  // B.next=C
                .addInstanceObject(0x300L, 0x10L, 0x400L)  // C.next=D
                .addInstanceObject(0x400L, 0x10L, 0L)       // D.next=null
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(b);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;
        int D = graph.idMap.indexOf(0x400L) + 1;

        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[A], "idom[A] = VRoot");
        assertEquals(A, graph.idom[B], "idom[B] = A");
        assertEquals(B, graph.idom[C], "idom[C] = B");
        assertEquals(C, graph.idom[D], "idom[D] = C");
    }

    /**
     * Diamond: VRoot→A, VRoot→B (both GC roots), A→C, B→C.
     * idom[C] = VRoot (both A and B dominate C, but only VRoot dominates both).
     */
    @Test
    void diamondShape() throws Exception {
        HprofTestBuilder builder = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "ref")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x300L)  // A.ref=C
                .addInstanceObject(0x200L, 0x10L, 0x300L)  // B.ref=C
                .addInstanceObject(0x300L, 0x10L, 0L)       // C
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .addGCRoot(0x200L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(builder);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;

        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[A]);
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[B]);
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[C],
                "C has two parents (A,B); idom[C] = VRoot");
    }

    /**
     * Single object with no incoming refs other than GC root.
     * idom[A] = VRoot.
     */
    @Test
    void singleGCRoot() throws Exception {
        HprofTestBuilder builder = new HprofTestBuilder(4)
                .addUtf8(1L, "java/lang/Object")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16)
                .addInstanceObject(0x100L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(builder);
        int A = graph.idMap.indexOf(0x100L) + 1;
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[A]);
    }

    /**
     * VRoot → A → B, and VRoot → C → B. idom[B] = VRoot.
     */
    @Test
    void sharedObject() throws Exception {
        HprofTestBuilder builder = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "ref")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x300L)  // A.ref=B
                .addInstanceObject(0x200L, 0x10L, 0x300L)  // C.ref=B
                .addInstanceObject(0x300L, 0x10L, 0L)       // B
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .addGCRoot(0x200L, HPROF_GC_ROOT_STICKY_CLASS);

        HeapGraph graph = buildGraph(builder);
        int B = graph.idMap.indexOf(0x300L) + 1;
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[B]);
    }

    /**
     * GC roots ignore real inbound edges — MAT parity.
     *
     * Graph: VRoot → A (GC root), B → A (B also points to A, but B is not a GC root).
     * VRoot → B (GC root), B → C, A → C.
     *
     * Because A is a GC root, its inbound edge from B is NOT considered for sdom.
     * A's only predecessor for LT purposes is VRoot → sdom[A]=0, idom[A]=VRoot.
     * C has predecessors B and A, both GC roots → idom[C]=VRoot.
     */
    @Test
    void gcRootIgnoresRealInbound() throws Exception {
        HprofTestBuilder builder = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "ref1")
                .addUtf8(11L, "ref2")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16,
                        HprofTestBuilder.FieldDef.object(10L),
                        HprofTestBuilder.FieldDef.object(11L))
                .addInstanceObject(0x100L, 0x10L, 0x300L, 0L)    // A → C, null
                .addInstanceObject(0x200L, 0x10L, 0x100L, 0x300L) // B → A, C
                .addInstanceObject(0x300L, 0x10L, 0L, 0L)          // C
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)     // A is GC root
                .addGCRoot(0x200L, HPROF_GC_ROOT_STICKY_CLASS);    // B is GC root

        HeapGraph graph = buildGraph(builder);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int B = graph.idMap.indexOf(0x200L) + 1;
        int C = graph.idMap.indexOf(0x300L) + 1;

        // A is a GC root → idom = VRoot (B→A edge is invisible for dominator purposes)
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[A], "idom[A]=VRoot (GC root ignores B→A)");
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[B], "idom[B]=VRoot");
        // C reachable from both A and B (both GC roots) → idom[C]=VRoot
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[C], "idom[C]=VRoot (A,B both GC roots)");
    }

    /**
     * GC root in the middle of a chain: R is GC root, B's only predecessor is R.
     *
     * Chain: VRoot → A → R (GC root) → B → C.
     * R is also pointed to by A (its only real inbound predecessor).
     * Because R is a GC root, its predecessor A is ignored → idom[R]=VRoot.
     * B's only predecessor is R (its DFS parent); sdom[B]=dfsPos[R] → idom[B]=R.
     * C's only predecessor is B → idom[C]=B.
     */
    @Test
    void gcRootInChainNoSdomPollution() throws Exception {
        HprofTestBuilder builder = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/N")
                .addUtf8(10L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(10L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)  // A → R
                .addInstanceObject(0x200L, 0x10L, 0x300L)  // R → B  (R is GC root)
                .addInstanceObject(0x300L, 0x10L, 0x400L)  // B → C
                .addInstanceObject(0x400L, 0x10L, 0L)       // C
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)  // A is GC root
                .addGCRoot(0x200L, HPROF_GC_ROOT_STICKY_CLASS); // R is GC root

        HeapGraph graph = buildGraph(builder);
        int A = graph.idMap.indexOf(0x100L) + 1;
        int R = graph.idMap.indexOf(0x200L) + 1;
        int B = graph.idMap.indexOf(0x300L) + 1;
        int C = graph.idMap.indexOf(0x400L) + 1;

        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[A], "idom[A]=VRoot");
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[R], "idom[R]=VRoot (GC root; A→R edge ignored)");
        // B's only predecessor is R (its DFS parent) → idom[B]=R
        assertEquals(R, graph.idom[B], "idom[B]=R (B's only pred is R, its DFS parent)");
        assertEquals(B, graph.idom[C], "idom[C]=B");
    }
}
