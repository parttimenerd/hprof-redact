/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;

/**
 * Computes the Reverse-Post-Order (RPO) traversal of the heap graph,
 * starting from the virtual root (index 0) via GC root edges, using an
 * explicit stack to avoid JVM stack overflow on deep heap paths.
 *
 * After completion:
 * <ul>
 *   <li>{@code graph.rpoPos[v]} = RPO position of node v (0 = virtual root)</li>
 *   <li>{@code graph.rpoOrder[i]} = node at RPO position i</li>
 * </ul>
 *
 * Frees {@code graph.fwdOffsets} and {@code graph.fwdTargets} after the DFS.
 */
final class RpoDfs {

    private RpoDfs() {}

    static void compute(HeapGraph graph) {
        int N = graph.N;
        int[] fwdOffsets = graph.fwdOffsets;
        int[] fwdTargets = graph.fwdTargets;

        int[] rpoPos   = new int[N];
        int[] rpoOrder = new int[N];
        Arrays.fill(rpoPos, -1); // -1 = not yet visited; will be set to actual position

        // Explicit DFS stack: three parallel stacks
        // nodeStack[i]   = node being processed
        // cursorStack[i] = next child index to visit (fwdOffsets[node] + cursor)
        // For virtual root (0): children are all GC roots
        int stackCap = Math.min(N, 1 << 16);
        int[] nodeStack   = new int[stackCap];
        int[] cursorStack = new int[stackCap]; // index into fwdTargets for this node
        int top = -1;

        // Post-order sequence (collected in reverse)
        int[] postOrder = new int[N];
        int postCount = 0;

        // Virtual root at index 0 has edges to all GC roots (stored in gcRootIds)
        // We simulate virtual root's adjacency list as gcRootIds[]
        rpoPos[HeapGraph.VIRTUAL_ROOT] = Integer.MAX_VALUE; // in-progress sentinel
        top++;
        if (top == nodeStack.length) {
            nodeStack   = Arrays.copyOf(nodeStack, top * 2);
            cursorStack = Arrays.copyOf(cursorStack, top * 2);
        }
        nodeStack[top]   = HeapGraph.VIRTUAL_ROOT;
        cursorStack[top] = 0;

        while (top >= 0) {
            int node   = nodeStack[top];
            int cursor = cursorStack[top];

            boolean pushed = false;
            // Get adjacency list for node
            int childCount = childCount(node, graph, fwdOffsets);
            while (cursor < childCount) {
                int child = getChild(node, cursor, graph, fwdOffsets, fwdTargets);
                cursor++;
                if (child < 0 || child >= N) continue;
                if (rpoPos[child] == -1) { // not yet visited
                    rpoPos[child] = Integer.MAX_VALUE; // in-progress sentinel (overwritten at completion)
                    cursorStack[top] = cursor;
                    top++;
                    if (top == nodeStack.length) {
                        nodeStack   = Arrays.copyOf(nodeStack, top * 2);
                        cursorStack = Arrays.copyOf(cursorStack, top * 2);
                    }
                    nodeStack[top]   = child;
                    cursorStack[top] = 0;
                    pushed = true;
                    break;
                }
            }
            if (!pushed) {
                // Node fully explored: add to post-order
                cursorStack[top] = cursor; // update (though we're popping)
                if (postCount < N) postOrder[postCount++] = node;
                top--;
            }
        }

        // RPO = reverse of post-order
        for (int i = 0; i < postCount; i++) {
            int node = postOrder[postCount - 1 - i];
            rpoOrder[i] = node;
            rpoPos[node] = i;
        }

        graph.rpoPos   = rpoPos;
        graph.rpoOrder = rpoOrder;

        // Free forward CSR — no longer needed after DFS
        graph.freeFwdCsr();
    }

    private static int childCount(int node, HeapGraph graph, int[] fwdOffsets) {
        if (node == HeapGraph.VIRTUAL_ROOT) return graph.gcRootCount + graph.classDumpCount;
        if (fwdOffsets == null || node >= fwdOffsets.length - 1) return 0;
        return fwdOffsets[node + 1] - fwdOffsets[node];
    }

    private static int getChild(int node, int cursor, HeapGraph graph,
                                 int[] fwdOffsets, int[] fwdTargets) {
        if (node == HeapGraph.VIRTUAL_ROOT) {
            if (cursor < graph.gcRootCount) return graph.gcRootIds[cursor];
            int cdIdx = cursor - graph.gcRootCount;
            return cdIdx < graph.classDumpCount ? graph.classDumpIndices[cdIdx] : -1;
        }
        if (fwdOffsets == null || fwdTargets == null) return -1;
        int start = fwdOffsets[node];
        int idx   = start + cursor;
        return idx < fwdOffsets[node + 1] ? fwdTargets[idx] : -1;
    }
}
