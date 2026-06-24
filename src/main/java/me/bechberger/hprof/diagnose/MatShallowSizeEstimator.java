/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import me.bechberger.hprof.HprofType;

/**
 * Mirrors the shallow-size formulas from Eclipse MAT's {@code HprofParserHandlerImpl}.
 *
 * <p>Used by the diagnostic report to estimate how much heap each object would contribute to MAT's
 * reported "heap size" figure, which differs from raw on-disk bytes.
 */
public final class MatShallowSizeEstimator {

    private MatShallowSizeEstimator() {}

    /**
     * Rounds {@code value} up to the nearest multiple of {@code align}.
     *
     * @param value the value to align
     * @param align the alignment boundary (must be a power of two)
     * @return the aligned value
     */
    public static long alignUp(long value, int align) {
        return ((value + align - 1) / align) * align;
    }

    /**
     * MAT shallow size for an INSTANCE_DUMP record. MAT trusts the JVM's {@code dataLength}
     * directly.
     *
     * @param dataLength the data length field from the HPROF INSTANCE_DUMP record
     * @return the MAT shallow size
     */
    public static long instanceMatSize(long dataLength) {
        return dataLength;
    }

    /**
     * MAT shallow size for an OBJ_ARRAY_DUMP record. MAT computes the JVM object layout.
     *
     * @param numElements the number of elements in the array
     * @param idSize id size from the HPROF header (4 or 8)
     * @param refSize reference size: {@code idSize} for uncompressed oops, 4 for compressed oops
     * @param objectAlign JVM object alignment (typically 8)
     * @return the MAT shallow size
     */
    public static long objArrayMatSize(long numElements, int idSize, int refSize, int objectAlign) {
        return alignUp(idSize + refSize + 4 + numElements * refSize, objectAlign);
    }

    /**
     * MAT shallow size for a PRIM_ARRAY_DUMP record. MAT computes the JVM object layout.
     *
     * @param numElements the number of elements in the array
     * @param elementType the primitive element type (must not be OBJECT or ARRAY_OBJECT)
     * @param idSize id size from the HPROF header (4 or 8)
     * @param refSize reference size: {@code idSize} for uncompressed oops, 4 for compressed oops
     * @param objectAlign JVM object alignment (typically 8)
     * @return the MAT shallow size
     * @throws IllegalArgumentException if {@code elementType} is not a primitive type
     */
    public static long primArrayMatSize(
            long numElements, HprofType elementType, int idSize, int refSize, int objectAlign) {
        return alignUp(
                idSize + refSize + 4 + numElements * primitiveSize(elementType), objectAlign);
    }

    /**
     * Returns the size in bytes of one element of the given primitive HPROF type.
     *
     * @param type the HPROF type
     * @return the size in bytes (1, 2, 4, or 8)
     * @throws IllegalArgumentException if {@code type} is OBJECT or ARRAY_OBJECT
     */
    public static int primitiveSize(HprofType type) {
        return switch (type) {
            case BOOLEAN, BYTE -> 1;
            case CHAR, SHORT -> 2;
            case INT, FLOAT -> 4;
            case LONG, DOUBLE -> 8;
            case OBJECT, ARRAY_OBJECT ->
                    throw new IllegalArgumentException(
                            "Not a primitive type: " + type);
        };
    }
}
