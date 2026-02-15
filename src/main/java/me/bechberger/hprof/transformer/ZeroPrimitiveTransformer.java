/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

/**
 * Transformer that zeros all primitive values while preserving UTF-8 string byte lengths.
 */
public final class ZeroPrimitiveTransformer implements HprofTransformer {
    @Override
    public boolean transformBoolean(boolean value) {
        return false;
    }

    @Override
    public byte transformByte(byte value) {
        return 0;
    }

    @Override
    public short transformShort(short value) {
        return 0;
    }

    @Override
    public char transformChar(char value) {
        return 0;
    }

    @Override
    public int transformInt(int value) {
        return 0;
    }

    @Override
    public long transformLong(long value) {
        return 0L;
    }

    @Override
    public float transformFloat(float value) {
        return 0.0f;
    }

    @Override
    public double transformDouble(double value) {
        return 0.0d;
    }

    @Override
    public String transformUtf8String(String value) {
        return TransformerUtil.zeroPreservingUtf8String(value);
    }
}