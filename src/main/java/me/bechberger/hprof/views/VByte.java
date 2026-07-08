/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/** VByte (variable-length byte) integer encoding. 7 bits per byte, MSB = continuation flag. */
final class VByte {
    private VByte() {}

    /** Encode non-negative int into buf[pos..], return new pos. */
    static int encode(int value, byte[] buf, int pos) {
        while ((value & ~0x7F) != 0) {
            buf[pos++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf[pos++] = (byte) value;
        return pos;
    }

    /**
     * Decode one VByte int from buf[pos..], store result in out[0], return new pos.
     * Supports values up to Integer.MAX_VALUE.
     */
    static int decode(byte[] buf, int pos, int[] out) {
        int value = 0;
        int shift = 0;
        while (true) {
            int b = buf[pos++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        out[0] = value;
        return pos;
    }

    /** Number of bytes required to encode value. */
    static int encodedSize(int value) {
        if (value < 0) throw new IllegalArgumentException("negative value: " + value);
        int n = 1;
        while ((value & ~0x7F) != 0) { n++; value >>>= 7; }
        return n;
    }
}
