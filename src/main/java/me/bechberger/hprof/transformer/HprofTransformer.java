/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

public interface HprofTransformer {
    /**
     * Transform UTF-8 records used by class names, field names, method names,
     * thread names, thread group names, and other metadata strings.
     */
    default String transformUtf8(String value) {
        return value;
    }

    /**
     * Transform UTF-8 records when their specific kind cannot be distinguished reliably.
     *
     * <p>HPROF records do not always allow differentiating method names and method
     * signatures from other UTF-8 strings. The filter routes method names and
     * signatures through this hook.</p>
     */
    default String transformUtf8String(String value) {
        return transformUtf8(value);
    }

    default String transformClassName(String value) {
        return transformUtf8(value);
    }

    default String transformFieldName(String value) {
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

    /**
     * Transform values within primitive arrays (HPROF_GC_PRIM_ARRAY_DUMP).
     * Default implementations modify arrays in place using primitive handlers.
     */
    default void transformBooleanArray(boolean[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformBoolean(value[i]);
        }
    }

    default void transformByteArray(byte[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformByte(value[i]);
        }
    }

    default void transformShortArray(short[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformShort(value[i]);
        }
    }

    default void transformCharArray(char[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformChar(value[i]);
        }
    }

    default void transformIntArray(int[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformInt(value[i]);
        }
    }

    default void transformLongArray(long[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformLong(value[i]);
        }
    }

    default void transformFloatArray(float[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformFloat(value[i]);
        }
    }

    default void transformDoubleArray(double[] value) {
        if (value == null) {
            return;
        }
        for (int i = 0; i < value.length; i++) {
            value[i] = transformDouble(value[i]);
        }
    }
}