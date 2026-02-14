/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

public interface HprofTransformer {
    /**
     * Transform UTF-8 records used by class names, field names, method names,
     * thread names, thread group names, and other metadata strings.
     */
    default String transformUtf8(String value) {
        return value;
    }

    default String transformUtf8String(String value) {
        return transformUtf8(value);
    }

    default String transformClassName(String value) {
        return transformUtf8(value);
    }

    default String transformFieldName(String value) {
        return transformUtf8(value);
    }

    default String transformMethodName(String value) {
        return transformUtf8(value);
    }

    default String transformMethodSignature(String value) {
        return transformUtf8(value);
    }

    default String transformSourceFileName(String value) {
        return transformUtf8(value);
    }

    default String transformThreadName(String value) {
        return transformUtf8(value);
    }

    default String transformThreadGroupName(String value) {
        return transformUtf8(value);
    }

    default String transformThreadGroupParentName(String value) {
        return transformUtf8(value);
    }

    default boolean transformBoolean(boolean value) {
        return value;
    }

    default byte transformByte(byte value) {
        return value;
    }

    default short transformShort(short value) {
        return value;
    }

    default char transformChar(char value) {
        return value;
    }

    default int transformInt(int value) {
        return value;
    }

    default long transformLong(long value) {
        return value;
    }

    default float transformFloat(float value) {
        return value;
    }

    default double transformDouble(double value) {
        return value;
    }
}