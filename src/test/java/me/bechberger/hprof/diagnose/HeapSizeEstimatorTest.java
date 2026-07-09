/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import me.bechberger.hprof.core.HprofType;
import org.junit.jupiter.api.Test;

import static me.bechberger.hprof.diagnose.HeapSizeEstimator.*;
import static org.junit.jupiter.api.Assertions.*;

class HeapSizeEstimatorTest {

    // -------------------------------------------------------------------------
    // alignUp
    // -------------------------------------------------------------------------

    @Test
    void alignUp_zero() {
        assertEquals(0, alignUp(0, 8));
    }

    @Test
    void alignUp_one() {
        assertEquals(8, alignUp(1, 8));
    }

    @Test
    void alignUp_exactMultiple() {
        assertEquals(8, alignUp(8, 8));
    }

    @Test
    void alignUp_justOverMultiple() {
        assertEquals(16, alignUp(9, 8));
    }

    // -------------------------------------------------------------------------
    // objArrayMatSize — compressed oops (idSize=8, refSize=4)
    // -------------------------------------------------------------------------

    @Test
    void objArray_compressedOops_zeroElements() {
        // alignUp(8 + 4 + 4 + 0, 8) = alignUp(16, 8) = 16
        assertEquals(16, objArrayMatSize(0, 8, 4, 8));
    }

    @Test
    void objArray_compressedOops_oneElement() {
        // alignUp(8 + 4 + 4 + 4, 8) = alignUp(20, 8) = 24
        assertEquals(24, objArrayMatSize(1, 8, 4, 8));
    }

    @Test
    void objArray_compressedOops_tenElements() {
        // alignUp(8 + 4 + 4 + 40, 8) = alignUp(56, 8) = 56
        assertEquals(56, objArrayMatSize(10, 8, 4, 8));
    }

    // -------------------------------------------------------------------------
    // objArrayMatSize — uncompressed oops (idSize=8, refSize=8)
    // -------------------------------------------------------------------------

    @Test
    void objArray_uncompressedOops_zeroElements() {
        // alignUp(8 + 8 + 4 + 0, 8) = alignUp(20, 8) = 24
        assertEquals(24, objArrayMatSize(0, 8, 8, 8));
    }

    @Test
    void objArray_uncompressedOops_oneElement() {
        // alignUp(8 + 8 + 4 + 8, 8) = alignUp(28, 8) = 32
        assertEquals(32, objArrayMatSize(1, 8, 8, 8));
    }

    // -------------------------------------------------------------------------
    // primArrayMatSize — BYTE (idSize=8, refSize=8)
    // -------------------------------------------------------------------------

    @Test
    void primArray_byte_zeroElements() {
        // alignUp(8 + 8 + 4 + 0, 8) = alignUp(20, 8) = 24
        assertEquals(24, primArrayMatSize(0, HprofType.BYTE, 8, 8, 8));
    }

    @Test
    void primArray_byte_hundredElements() {
        // alignUp(8 + 8 + 4 + 100, 8) = alignUp(120, 8) = 120
        assertEquals(120, primArrayMatSize(100, HprofType.BYTE, 8, 8, 8));
    }

    // -------------------------------------------------------------------------
    // primArrayMatSize — LONG (idSize=8, refSize=8)
    // -------------------------------------------------------------------------

    @Test
    void primArray_long_oneElement() {
        // alignUp(8 + 8 + 4 + 8, 8) = alignUp(28, 8) = 32
        assertEquals(32, primArrayMatSize(1, HprofType.LONG, 8, 8, 8));
    }

    @Test
    void primArray_long_tenElements() {
        // alignUp(8 + 8 + 4 + 80, 8) = alignUp(100, 8) = 104
        assertEquals(104, primArrayMatSize(10, HprofType.LONG, 8, 8, 8));
    }

    // -------------------------------------------------------------------------
    // primitiveSize — illegal types
    // -------------------------------------------------------------------------

    @Test
    void primitiveSize_object_throws() {
        assertThrows(IllegalArgumentException.class, () -> primitiveSize(HprofType.OBJECT));
    }

    @Test
    void primitiveSize_arrayObject_throws() {
        assertThrows(IllegalArgumentException.class, () -> primitiveSize(HprofType.ARRAY_OBJECT));
    }
}
