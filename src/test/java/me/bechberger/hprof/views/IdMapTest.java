/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdMapTest {

    @Test
    void sortAndIndex() {
        IdMap map = new IdMap();
        long[] addrs = {0x200L, 0x100L, 0x400L, 0x300L, 0x500L, 0x800L, 0x700L, 0x600L, 0xA00L, 0x900L};
        for (long a : addrs) map.append(a);
        map.sort();
        // Every address must be found at some index
        for (long a : addrs) {
            int idx = map.indexOf(a);
            assertNotEquals(-1, idx, "Not found: 0x" + Long.toHexString(a));
        }
        // Unknown address returns -1
        assertEquals(-1, map.indexOf(0xFFFF0000L));
    }

    @Test
    void notFound() {
        IdMap map = new IdMap();
        map.append(0x100L);
        map.append(0x200L);
        map.sort();
        assertEquals(-1, map.indexOf(0x150L));
    }

    @Test
    void sortOrderIsConsistent() {
        IdMap map = new IdMap();
        // Add in reverse order
        for (int i = 100; i >= 1; i--) {
            map.append((long) i * 8); // 8-byte aligned
        }
        map.sort();
        // indexOf each should return a non-negative unique index
        int[] indices = new int[101];
        for (int i = 1; i <= 100; i++) {
            int idx = map.indexOf((long) i * 8);
            assertNotEquals(-1, idx, "Not found: " + i);
            assertEquals(0, indices[idx], "Duplicate index for address " + i);
            indices[idx] = 1;
        }
    }

    @Test
    void compressedOopsDetectedForAligned8ByteAddresses() {
        IdMap map = new IdMap();
        // All addresses are 8-byte aligned and fit in 29 bits shifted right 3
        for (int i = 1; i <= 50; i++) {
            map.append((long) i * 8);
        }
        map.sort();
        assertTrue(map.isCompressedOops());
        // All addresses still findable
        for (int i = 1; i <= 50; i++) {
            assertNotEquals(-1, map.indexOf((long) i * 8));
        }
    }

    @Test
    void largeAddressesDisableCompressedOops() {
        IdMap map = new IdMap();
        map.append(0x100L);
        map.append(0x10000000000L); // > 32 bits shifted right 3
        map.sort();
        assertFalse(map.isCompressedOops());
        assertNotEquals(-1, map.indexOf(0x100L));
        assertNotEquals(-1, map.indexOf(0x10000000000L));
    }

    @Test
    void manyAddresses() {
        IdMap map = new IdMap();
        int N = 100_000;
        for (int i = 1; i <= N; i++) {
            map.append((long) i * 8);
        }
        map.sort();
        // Sample check
        assertEquals(0, map.indexOf(1 * 8));
        assertNotEquals(-1, map.indexOf((long) N * 8));
        assertEquals(-1, map.indexOf((long) (N + 1) * 8));
    }
}
