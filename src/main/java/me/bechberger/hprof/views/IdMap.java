/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

import java.util.Arrays;

/**
 * Sorted address → integer-index mapping for HPROF object IDs.
 *
 * Append all addresses during Phase A.1, call sort(), then use indexOf() for lookups.
 * Supports compressed OOPs: if addresses fit in 29 bits after right-shifting by 3, they are
 * stored as int[] to halve memory consumption.
 *
 * Skip-index with SKIP_STRIDE=256 entries reduces binary search comparisons from ~log2(N) to
 * ~log2(256) + scan, keeping cache-line pressure low.
 */
final class IdMap {
    private static final int INITIAL_CAPACITY = 1 << 16;
    private static final int SKIP_STRIDE = 256;

    private long[] buf;
    private int size;
    private boolean compressedOops;
    // After sort: either buf (long[]) remains if !compressedOops, or intBuf (int[]) is used.
    private int[] intBuf;
    // Skip index: skipIndex[i] = value at position i*SKIP_STRIDE in the sorted array.
    private long[] skipIndex;
    private boolean sorted;

    IdMap() {
        buf = new long[INITIAL_CAPACITY];
        size = 0;
        sorted = false;
    }

    /** Append an address. Must be called before sort(). */
    void append(long address) {
        if (size == buf.length) {
            buf = Arrays.copyOf(buf, buf.length * 2);
        }
        buf[size++] = address;
    }

    int size() { return size; }

    /**
     * Sort all appended addresses and build the skip index.
     * Detects compressed OOPs: if all addresses are 8-byte aligned and fit in 32 bits after
     * right-shifting by 3 (i.e., max address ≤ 0x7FFFFFF8L = ~2 GB * 8), use int[] storage.
     */
    void sort() {
        buf = Arrays.copyOf(buf, size);
        Arrays.sort(buf);
        // Deduplicate (GC root addresses may duplicate object addresses)
        int unique = 0;
        for (int i = 0; i < size; i++) {
            if (i == 0 || buf[i] != buf[i - 1]) buf[unique++] = buf[i];
        }
        size = unique;
        buf = Arrays.copyOf(buf, size);
        // Detect compressed OOPs
        compressedOops = canUseCompressedOops();
        if (compressedOops) {
            intBuf = new int[size];
            for (int i = 0; i < size; i++) {
                intBuf[i] = (int) (buf[i] >>> 3);
            }
            buf = null; // free the long[]
        }
        buildSkipIndex();
        sorted = true;
    }

    private boolean canUseCompressedOops() {
        for (int i = 0; i < size; i++) {
            long addr = buf[i];
            if ((addr & 0x7L) != 0) return false; // not 8-byte aligned
            if ((addr >>> 3) > 0xFFFFFFFFL) return false; // doesn't fit in unsigned int
        }
        return true;
    }

    private void buildSkipIndex() {
        int skipLen = (size / SKIP_STRIDE) + 1;
        skipIndex = new long[skipLen];
        for (int i = 0; i < skipLen; i++) {
            int idx = Math.min(i * SKIP_STRIDE, size - 1);
            skipIndex[i] = get(idx);
        }
    }

    private long get(int idx) {
        if (compressedOops) {
            return Integer.toUnsignedLong(intBuf[idx]) << 3;
        }
        return buf[idx];
    }

    /**
     * Returns the index (0-based) for the given address, or -1 if not found.
     * Must be called after sort().
     */
    int indexOf(long address) {
        if (!sorted) throw new IllegalStateException("sort() not yet called");
        // Narrow search range using skip index
        long searchAddr = address;
        int lo = 0, hi = size;
        if (compressedOops) {
            // search in intBuf
            int key = (int) (address >>> 3);
            // Narrow via skip index
            int skipLo = 0, skipHi = skipIndex.length - 1;
            while (skipLo < skipHi) {
                int mid = (skipLo + skipHi + 1) >>> 1;
                long skipVal = skipIndex[mid]; // stored as unshifted address
                if (skipVal <= address) skipLo = mid;
                else skipHi = mid - 1;
            }
            lo = skipLo * SKIP_STRIDE;
            hi = Math.min(lo + SKIP_STRIDE + 1, size);
            // Linear scan within small window, then binary for safety
            int pos = Arrays.binarySearch(intBuf, lo, hi, key);
            return pos >= 0 ? pos : -1;
        } else {
            // Narrow via skip index
            int skipLo = 0, skipHi = skipIndex.length - 1;
            while (skipLo < skipHi) {
                int mid = (skipLo + skipHi + 1) >>> 1;
                if (skipIndex[mid] <= searchAddr) skipLo = mid;
                else skipHi = mid - 1;
            }
            lo = skipLo * SKIP_STRIDE;
            hi = Math.min(lo + SKIP_STRIDE + 1, size);
            int pos = Arrays.binarySearch(buf, lo, hi, searchAddr);
            return pos >= 0 ? pos : -1;
        }
    }

    boolean isCompressedOops() { return compressedOops; }
}
