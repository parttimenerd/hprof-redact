/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static me.bechberger.hprof.core.HprofConstants.*;
import static org.junit.jupiter.api.Assertions.*;

class HeapGraphBuilderTest {

    /**
     * Minimal HPROF: one class, two instances, one ref (A→B), A is GC root.
     * Expected graph (virtual root = 0, A = 1, B = 2):
     *   VRoot→A (via gc root), A→B (via field)
     */
    @Test
    void phaseA1ClassTablePopulated() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Foo")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16)
                .addInstanceObject(0x100L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).build();

        assertEquals(3, graph.N); // virtual root + class object + 1 instance
        assertFalse(graph.classList.isEmpty(), "classList should not be empty");
        assertTrue(graph.classList.stream().anyMatch(c -> c.name().equals("com/example/Foo")),
                "classList should contain com/example/Foo");
    }

    @Test
    void inboundCsrCorrectForLinearChain() throws Exception {
        // Class with one object-type field (field nameId=99)
        // Objects: A(0x100) → B(0x200) via field
        // GC root: A
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Node")
                .addUtf8(99L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8,
                        HprofTestBuilder.FieldDef.object(99L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)  // A.next = B
                .addInstanceObject(0x200L, 0x10L, 0L)        // B.next = null
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).buildForTesting();

        assertEquals(4, graph.N); // virtual root + class object + A + B

        // Find indices for A and B
        IdMap idMap = graph.idMap;
        int aIdx = idMap.indexOf(0x100L) + 1;
        int bIdx = idMap.indexOf(0x200L) + 1;
        assertTrue(aIdx > 0, "A must be found");
        assertTrue(bIdx > 0, "B must be found");

        // B should have A as its predecessor in the inbound CSR
        List<Integer> predsB = decodePreds(graph, bIdx);
        assertEquals(1, predsB.size(), "B should have exactly 1 predecessor");
        assertEquals(aIdx, (int) predsB.get(0), "B's predecessor should be A");

        // A should have no inbound edges from regular objects (only from virtual root via GC root,
        // but virtual root edges are not stored in the inbound CSR — they are implicit)
        List<Integer> predsA = decodePreds(graph, aIdx);
        assertEquals(0, predsA.size(), "A should have no stored inbound edges (VRoot is implicit)");
    }

    @Test
    void gcRootsPopulated() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "java/lang/Object")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16)
                .addInstanceObject(0x100L, 0x10L)
                .addInstanceObject(0x200L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .addGCRoot(0x200L, HPROF_GC_ROOT_JNI_GLOBAL)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).buildForTesting();

        // class object 0x10L is also added as synthetic STICKY_CLASS root
        assertEquals(3, graph.gcRootCount);
        assertEquals(1, graph.syntheticRootCount);
        IdMap idMap = graph.idMap;
        int idx1 = idMap.indexOf(0x100L) + 1;
        int idx2 = idMap.indexOf(0x200L) + 1;
        // GC roots have idom == VIRTUAL_ROOT (index 0)
        assertTrue(graph.idom[idx1] == HeapGraph.VIRTUAL_ROOT, "0x100 should be a GC root (idom == 0)");
        assertTrue(graph.idom[idx2] == HeapGraph.VIRTUAL_ROOT, "0x200 should be a GC root (idom == 0)");
    }

    @Test
    void objectArrayEdgesRecorded() throws Exception {
        // Array A contains refs to B and C
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "java/lang/Object")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16)
                .addObjectArray(0x100L, 0x10L, 0x200L, 0x300L)  // array → B, C
                .addInstanceObject(0x200L, 0x10L)
                .addInstanceObject(0x300L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).buildForTesting();

        assertEquals(5, graph.N); // root + array + B + C + element class

        IdMap idMap = graph.idMap;
        int arrIdx = idMap.indexOf(0x100L) + 1;
        int bIdx   = idMap.indexOf(0x200L) + 1;
        int cIdx   = idMap.indexOf(0x300L) + 1;

        List<Integer> predsB = decodePreds(graph, bIdx);
        assertTrue(predsB.contains(arrIdx), "B's predecessor should be the array");

        List<Integer> predsC = decodePreds(graph, cIdx);
        assertTrue(predsC.contains(arrIdx), "C's predecessor should be the array");
    }

    @Test
    void rpoAndDomTreeComputedAfterBuild() throws Exception {
        // Simple graph: VRoot→A→B; RPO arrays are freed after dominator tree + retained sizes,
        // but idom[] proves RPO was computed correctly (CHK requires RPO order).
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "com/example/Node")
                .addUtf8(99L, "next")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 8, HprofTestBuilder.FieldDef.object(99L))
                .addInstanceObject(0x100L, 0x10L, 0x200L)
                .addInstanceObject(0x200L, 0x10L, 0L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).buildForTesting();

        // RPO/DFS arrays are freed after use (dfsPos/dfsOrder/dfsParent after DOM, rpoOrder after retained sizes)
        assertNull(graph.dfsPos,   "dfsPos should be freed after dominator tree");
        assertNull(graph.rpoOrder, "rpoOrder should be freed after retained sizes");

        // Dominator tree proves RPO was computed: idom[A] = VRoot, idom[B] = A
        IdMap idMap = graph.idMap;
        int aIdx = idMap.indexOf(0x100L) + 1;
        int bIdx = idMap.indexOf(0x200L) + 1;
        assertEquals(HeapGraph.VIRTUAL_ROOT, graph.idom[aIdx], "idom[A] = VRoot");
        assertEquals(aIdx, graph.idom[bIdx], "idom[B] = A");
    }

    @Test
    void fwdCsrFreedAfterRpoDfs() throws Exception {
        Path hprof = new HprofTestBuilder(4)
                .addUtf8(1L, "java/lang/Object")
                .addLoadClass(1, 0x10L, 1L)
                .addClass(0x10L, 0L, 0L, 16)
                .addInstanceObject(0x100L, 0x10L)
                .addGCRoot(0x100L, HPROF_GC_ROOT_STICKY_CLASS)
                .buildToPath();

        HeapGraph graph = new HeapGraphBuilder(hprof).build();

        assertNull(graph.fwdOffsets, "fwdOffsets should be freed after RPO DFS");
        assertNull(graph.fwdTargets, "fwdTargets should be freed after RPO DFS");
    }

    // ---- helper: decode inbound predecessors of node v ----
    private static List<Integer> decodePreds(HeapGraph g, int v) {
        List<Integer> result = new ArrayList<>();
        long start = Integer.toUnsignedLong(g.inboundOffsets[v]);
        long end   = Integer.toUnsignedLong(g.inboundOffsets[v + 1]);
        byte[][] stream = g.inboundStream;
        long pos = start;
        int prev = 0;
        int[] tmp = new int[1];
        while (pos < end) {
            pos = VByte.decode(stream, pos, tmp);
            int src = prev + tmp[0];
            prev = src;
            result.add(src);
        }
        return result;
    }
}
