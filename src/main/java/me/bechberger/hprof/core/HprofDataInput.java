/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.core;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

public final class HprofDataInput {
    private final InputStream raw;
    private final DataInputStream in;
    private int idSize;

    public HprofDataInput(InputStream in) {
        this.raw = in;
        this.in = new DataInputStream(in);
        this.idSize = 4;
    }

    public InputStream rawStream() {
        return raw;
    }

    public void setIdSize(int idSize) {
        this.idSize = idSize;
    }

    public int getIdSize() {
        return idSize;
    }

    public int readTag() throws IOException {
        return in.read();
    }

    public int readU1() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new EOFException("Unexpected end of stream");
        }
        return value;
    }

    public int readU2() throws IOException {
        return in.readUnsignedShort();
    }

    public long readU4() throws IOException {
        return Integer.toUnsignedLong(in.readInt());
    }

    public long readU8() throws IOException {
        return in.readLong();
    }

    public long readId() throws IOException {
        if (idSize == 4) {
            return readU4();
        }
        if (idSize == 8) {
            return readU8();
        }
        throw new IOException("Unsupported id size: " + idSize);
    }

    public void readFully(byte[] buffer) throws IOException {
        in.readFully(buffer);
    }

    public void readFully(byte[] buffer, int offset, int length) throws IOException {
        in.readFully(buffer, offset, length);
    }

    public void skipFully(long length) throws IOException {
        long remaining = length;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("Unexpected end of stream");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }
}
