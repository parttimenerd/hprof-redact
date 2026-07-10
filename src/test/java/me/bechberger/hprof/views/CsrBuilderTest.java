/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CsrBuilderTest {

    /** Build a HeapGraph stub sufficient for CsrBuilder. */
    private HeapGraph stub(int n) {
        IdMap idMap = new IdMap();
        HeapGraph g = new HeapGraph(null, 4, 0, "JAVA PROFILE 1.0.2", idMap);
        g.N = n;
        return g;
    }

    @Test
    void inPlaceFillAndRestore() {
        // 4-node graph: 0→1, 0→2, 1→3, 2→3
        int n = 4;
        HeapGraph g = stub(n);
        CsrBuilder b = new CsrBuilder(g, n, 4);

        // Count phase
        b.countEdge(1); b.countEdge(2); b.countEdge(3); b.countEdge(3);
        b.finishCounting();

        // After finishCounting, offsets are stored in CsrBuilder's internal offsetsCursor,
        // not in g.inboundOffsets (which is null until encodeVByte sets it).
        // We can only verify after encodeVByte by checking the stream.
        // For this test, just verify fill+restore works by encoding and checking predecessors.
        g.excludePairs = new short[0][];
        b.addEdge(0, 1, (short)0, (short)0);
        b.addEdge(0, 2, (short)0, (short)0);
        b.addEdge(1, 3, (short)0, (short)0);
        b.addEdge(2, 3, (short)0, (short)0);
        b.restoreOffsets();
        b.encodeVByteWithEmbeddedFlags();

        // node 1 has predecessor 0
        List<Integer> preds1 = iteratePreds(g, 1);
        assertEquals(1, preds1.size());
        assertEquals(0, (int) preds1.get(0));

        // node 3 has predecessors 1 and 2
        List<Integer> preds3 = iteratePreds(g, 3);
        assertEquals(2, preds3.size());
        assertTrue(preds3.contains(1));
        assertTrue(preds3.contains(2));
    }

    @Test
    void vByteEncodePreservesPredecessors() {
        // 3-node graph: 0→1, 0→2, 1→2
        int n = 3;
        HeapGraph g = stub(n);
        g.excludePairs = new short[0][]; // empty — no excludes
        CsrBuilder b = new CsrBuilder(g, n, 3);

        b.countEdge(1); b.countEdge(2); b.countEdge(2);
        b.finishCounting();

        // addEdge with embedded flag approach: no excludes → sign bits stay 0
        b.addEdge(0, 1, (short)0, (short)0);
        b.addEdge(0, 2, (short)0, (short)0);
        b.addEdge(1, 2, (short)0, (short)0);
        b.restoreOffsets();
        b.encodeVByteWithEmbeddedFlags();

        assertNotNull(g.inboundStream);
        assertTrue(g.inboundStream.length > 0);
        assertTrue(g.inboundStream[0].length > 0);

        // Iterate predecessors of node 2: should be 0 and 1
        List<Integer> preds2 = iteratePreds(g, 2);
        assertEquals(2, preds2.size());
        assertTrue(preds2.contains(0));
        assertTrue(preds2.contains(1));

        // Iterate predecessors of node 1: should be 0
        List<Integer> preds1 = iteratePreds(g, 1);
        assertEquals(1, preds1.size());
        assertEquals(0, (int)preds1.get(0));

        // Node 0 has no predecessors
        List<Integer> preds0 = iteratePreds(g, 0);
        assertEquals(0, preds0.size());
    }

    @Test
    void excludedEdgeFlagSetCorrectly() {
        // 2-node graph: 0→1 with excluded edge (matching exclude pair)
        int n = 2;
        HeapGraph g = stub(n);
        // Exclude pair: classIdx=5, nameIdx=3
        g.excludePairs = new short[][]{ {5, 3} };
        CsrBuilder b = new CsrBuilder(g, n, 1);

        b.countEdge(1);
        b.finishCounting();

        // addEdge: src=0, dst=1, nameIdx=3, srcClassIdx=5 → should be excluded
        b.addEdge(0, 1, (short)3, (short)5);
        b.restoreOffsets();
        b.encodeVByteWithEmbeddedFlags();

        // The single edge should have the exclude flag set
        assertTrue(g.excludedEdge.get(0), "edge 0 should be excluded");
    }

    @Test
    void nonExcludedEdgeFlagNotSet() {
        int n = 2;
        HeapGraph g = stub(n);
        g.excludePairs = new short[][]{ {5, 3} };
        CsrBuilder b = new CsrBuilder(g, n, 1);
        b.countEdge(1);
        b.finishCounting();
        // Different class + name → not excluded
        b.addEdge(0, 1, (short)4, (short)6);
        b.restoreOffsets();
        b.encodeVByteWithEmbeddedFlags();
        assertFalse(g.excludedEdge.get(0), "edge 0 should not be excluded");
    }

    @Test
    void largeRowSortedCorrectly() {
        // Node 0 receives 100 predecessors from nodes 1..100 added in reverse order
        int n = 101;
        HeapGraph g = stub(n);
        g.excludePairs = new short[0][];
        CsrBuilder b = new CsrBuilder(g, n, 100);

        for (int i = 100; i >= 1; i--) b.countEdge(0);
        b.finishCounting();
        for (int i = 100; i >= 1; i--) b.addEdge(i, 0, (short)0, (short)0);
        b.restoreOffsets();
        b.encodeVByteWithEmbeddedFlags();

        List<Integer> preds = iteratePreds(g, 0);
        assertEquals(100, preds.size());
        // Must be in sorted (ascending) order after encoding
        for (int i = 0; i < preds.size() - 1; i++) {
            assertTrue(preds.get(i) < preds.get(i + 1),
                    "Not sorted at " + i + ": " + preds.get(i) + " >= " + preds.get(i + 1));
        }
        // All values 1..100 present
        for (int i = 1; i <= 100; i++) {
            assertTrue(preds.contains(i), "missing " + i);
        }
    }

    // Helper: iterate predecessors of node v by decoding VByte stream
    private List<Integer> iteratePreds(HeapGraph g, int v) {
        List<Integer> result = new ArrayList<>();
        long start = g.inboundOffsets[v];
        long end   = g.inboundOffsets[v + 1];
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
