/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class VByteTest {

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 126, 127, 128, 255, 16383, 16384, 0x3FFFFF, 0x1FFFFFFF, Integer.MAX_VALUE})
    void roundTrip(int value) {
        byte[] buf = new byte[8];
        int[] out = new int[1];
        int end = VByte.encode(value, buf, 0);
        int after = VByte.decode(buf, 0, out);
        assertEquals(value, out[0]);
        assertEquals(end, after);
    }

    @Test
    void encodedSizeMatchesActualEncoded() {
        int[] values = {0, 127, 128, 16383, 16384, Integer.MAX_VALUE};
        for (int v : values) {
            byte[] buf = new byte[8];
            int end = VByte.encode(v, buf, 0);
            assertEquals(end, VByte.encodedSize(v), "size mismatch for " + v);
        }
    }

    @Test
    void deltaSequenceRoundTrip() {
        int[] values = {0, 1, 5, 5, 100, 1000, 100000, Integer.MAX_VALUE - 1};
        byte[] buf = new byte[64];
        int pos = 0;
        int prev = 0;
        for (int v : values) {
            pos = VByte.encode(v - prev, buf, pos);
            prev = v;
        }
        pos = 0;
        prev = 0;
        int[] tmp = new int[1];
        for (int v : values) {
            pos = VByte.decode(buf, pos, tmp);
            int decoded = prev + tmp[0];
            assertEquals(v, decoded);
            prev = decoded;
        }
    }
}
