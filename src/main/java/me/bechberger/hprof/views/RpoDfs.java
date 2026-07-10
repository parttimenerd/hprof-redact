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
 *   <li>{@code graph.rpoOrder[i]} = node at RPO position i</li>
 *   <li>{@code graph.dfsPos[v]} = DFS pre-order visit number (0 = virtual root)</li>
 *   <li>{@code graph.dfsOrder[i]} = node at DFS pre-order position i</li>
 *   <li>{@code graph.dfsParent[v]} = DFS spanning-tree parent of v (-1 = virtual root has no parent)</li>
 * </ul>
 *
 * Frees {@code graph.fwdOffsets}, {@code graph.fwdStream}, and {@code graph.fwdTargets} after the DFS.
 *
 * Note: rpoPos[] is NOT produced here. Reachability is determined via dfsPos[v] >= 0.
 */
final class RpoDfs {

    private RpoDfs() {}

    static void compute(HeapGraph graph) {
        int N = graph.N;
        int[] fwdOffsets = graph.fwdOffsets;
        byte[][] fwdStream = graph.fwdStream;

        int[] rpoOrder = graph.phaseArrays.takeRaw(); // donated by A2; avoids fresh int[N]

        // DFS pre-order: assigned when a node is first pushed onto the stack.
        // dfsPos[v] = -1 means not yet visited; >= 0 means visited (also serves as visited-sentinel).
        int[] dfsPos   = new int[N];
        int[] dfsOrder = new int[N];
        int[] dfsParent = new int[N];
        Arrays.fill(dfsPos,    -1);
        Arrays.fill(dfsParent, -1);

        // Per-node decoded adjacency state: for node at stack[top], we need:
        //   - the current byte position in fwdStream (long, for chunked decode)
        //   - the end byte position for this node's row
        //   - the previous decoded value (for delta reconstruction)
        //   - how many children remain
        // We store cursor as long (byte offset in fwdStream) and prev as int.
        int stackCap = Math.min(N, 1 << 16);
        int[] nodeStack    = new int[stackCap];
        long[] cursorStack = new long[stackCap]; // byte offset of next unread child in fwdStream
        long[] endStack    = new long[stackCap]; // byte offset past last child for this node
        int[]  prevStack   = new int[stackCap];  // delta-decode base (last decoded value)

        int top = -1;

        int rpoIdx   = N;
        int dfsCount = 0;

        dfsPos[HeapGraph.VIRTUAL_ROOT]    = dfsCount;
        dfsOrder[dfsCount++]              = HeapGraph.VIRTUAL_ROOT;
        dfsParent[HeapGraph.VIRTUAL_ROOT] = -1;
        top++;
        nodeStack[top]   = HeapGraph.VIRTUAL_ROOT;
        cursorStack[top] = 0; // virtual root uses gcRootIds cursor (int index, stored in low 32 bits)
        endStack[top]    = graph.gcRootCount;
        prevStack[top]   = 0;

        int[] decodeBuf = new int[1];

        while (top >= 0) {
            int  node   = nodeStack[top];
            long cursor = cursorStack[top];
            long end    = endStack[top];

            boolean pushed = false;

            // Scan children until we push an unvisited one or exhaust this node's list.
            while (cursor < end) {
                int child;
                if (node == HeapGraph.VIRTUAL_ROOT) {
                    child = graph.gcRootIds[(int) cursor];
                    cursor++;
                    cursorStack[top] = cursor;
                } else {
                    long newPos = VByte.decode(fwdStream, cursor, decodeBuf);
                    int  val    = prevStack[top] + decodeBuf[0];
                    prevStack[top]   = val;
                    cursorStack[top] = newPos;
                    cursor = newPos;
                    child = val;
                }
                if (child < 0 || child >= N) continue;
                if (dfsPos[child] == -1) {
                    dfsPos[child]        = dfsCount;
                    dfsOrder[dfsCount++] = child;
                    dfsParent[child]     = node;
                    top++;
                    if (top == nodeStack.length) {
                        nodeStack   = Arrays.copyOf(nodeStack,   top * 2);
                        cursorStack = Arrays.copyOf(cursorStack, top * 2);
                        endStack    = Arrays.copyOf(endStack,    top * 2);
                        prevStack   = Arrays.copyOf(prevStack,   top * 2);
                    }
                    nodeStack[top] = child;
                    if (fwdStream == null || fwdOffsets == null || child >= fwdOffsets.length - 1) {
                        cursorStack[top] = 0;
                        endStack[top]    = 0;
                    } else {
                        long byteStart = Integer.toUnsignedLong(fwdOffsets[child]);
                        long byteEnd   = Integer.toUnsignedLong(fwdOffsets[child + 1]);
                        cursorStack[top] = byteStart;
                        endStack[top]    = byteEnd;
                    }
                    prevStack[top] = 0;
                    pushed = true;
                    break;
                }
            }
            if (!pushed) {
                rpoOrder[--rpoIdx] = node;
                top--;
            }
        }

        // RPO entries are in rpoOrder[rpoIdx..N-1]; shift to rpoOrder[0..postCount-1].
        int postCount = N - rpoIdx;
        System.arraycopy(rpoOrder, rpoIdx, rpoOrder, 0, postCount);

        graph.rpoOrder     = rpoOrder;
        graph.rpoReachable = postCount;
        graph.dfsPos    = dfsPos;
        graph.dfsOrder  = dfsOrder;
        graph.dfsParent = dfsParent;

        graph.freeFwdCsr();
    }
}
