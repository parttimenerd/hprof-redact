/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/** VByte (variable-length byte) integer encoding. 7 bits per byte, MSB = continuation flag. */
final class VByte {
    private VByte() {}

    /** Chunk size for chunked byte[][] streams (512 MB per chunk). */
    static final int CHUNK_BITS  = 29;                        // 512 MB
    static final int CHUNK_SIZE  = 1 << CHUNK_BITS;          // 536,870,912
    static final long CHUNK_MASK = CHUNK_SIZE - 1L;

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

    /**
     * Decode one VByte int from a chunked byte[][] stream at byte position {@code pos}.
     * Stores result in out[0], returns new position (long).
     * Each chunk is exactly CHUNK_SIZE bytes; the last chunk may be shorter.
     */
    static long decode(byte[][] chunks, long pos, int[] out) {
        int chunkIdx = (int) (pos >>> CHUNK_BITS);
        int chunkOff = (int) (pos & CHUNK_MASK);
        byte[] chunk = chunks[chunkIdx];

        int value = 0;
        int shift = 0;
        while (true) {
            if (chunkOff == chunk.length) {
                chunk = chunks[++chunkIdx];
                chunkOff = 0;
            }
            int b = chunk[chunkOff++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        out[0] = value;
        // If the last byte consumed was at end of a full-size chunk, chunkOff == CHUNK_SIZE.
        // Re-encode position correctly: (chunkIdx << CHUNK_BITS) | chunkOff would produce
        // the wrong result because CHUNK_SIZE sets the same bit as chunkIdx+1.
        if (chunkOff == CHUNK_SIZE) {
            return ((long) (chunkIdx + 1) << CHUNK_BITS);
        }
        return ((long) chunkIdx << CHUNK_BITS) | chunkOff;
    }

    /** Encode into a chunked byte[][] stream at byte position {@code pos}, return new position. */
    static long encode(int value, byte[][] chunks, long pos) {
        while ((value & ~0x7F) != 0) {
            int chunkIdx = (int) (pos >>> CHUNK_BITS);
            int chunkOff = (int) (pos & CHUNK_MASK);
            chunks[chunkIdx][chunkOff] = (byte) ((value & 0x7F) | 0x80);
            pos++;
            value >>>= 7;
        }
        int chunkIdx = (int) (pos >>> CHUNK_BITS);
        int chunkOff = (int) (pos & CHUNK_MASK);
        chunks[chunkIdx][chunkOff] = (byte) value;
        return pos + 1;
    }

    /** Number of bytes required to encode value. */
    static int encodedSize(int value) {
        if (value < 0) throw new IllegalArgumentException("negative value: " + value);
        int n = 1;
        while ((value & ~0x7F) != 0) { n++; value >>>= 7; }
        return n;
    }
}
