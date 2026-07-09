/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.core;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public final class HprofDataOutput {
    private final DataOutputStream out;
    private int idSize;

    public HprofDataOutput(OutputStream out) {
        this.out = new DataOutputStream(out);
        this.idSize = 4;
    }

    public void setIdSize(int idSize) {
        this.idSize = idSize;
    }

    public void writeU1(int value) throws IOException {
        out.writeByte(value);
    }

    public void writeU2(int value) throws IOException {
        out.writeShort(value & 0xFFFF);
    }

    public void writeU4(long value) throws IOException {
        out.writeInt((int) value);
    }

    public void writeU8(long value) throws IOException {
        out.writeLong(value);
    }

    public void writeId(long value) throws IOException {
        if (idSize == 4) {
            writeU4(value);
            return;
        }
        if (idSize == 8) {
            writeU8(value);
            return;
        }
        throw new IOException("Unsupported id size: " + idSize);
    }

    public void writeBytes(byte[] buffer) throws IOException {
        out.write(buffer);
    }

    public void writeBytes(byte[] buffer, int offset, int length) throws IOException {
        out.write(buffer, offset, length);
    }

    public void flush() throws IOException {
        out.flush();
    }
}