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
 * Supports compressed OOPs: addresses fit in 29 bits after right-shifting by 3 → int[] storage.
 *
 * Address-bucket index: the address range is divided into N/8 buckets; each bucket stores the
 * first sorted-array position whose address falls in or after that bucket. indexOf() computes
 * the bucket in O(1), then does a short sequential linear scan (~8 entries average).
 */
final class IdMap {
    private static final int INITIAL_CAPACITY = 1 << 16;

    private long[] buf;
    private int size;
    private boolean compressedOops;
    // After sort: either buf (long[]) or intBuf (int[], compressed-oops) holds sorted addresses.
    private int[] intBuf;
    // Bucket index: bucket[i] = first sorted-array index whose address ≥ bucketStart(i).
    private int[] bucket;
    private int bucketCount;
    private long addrMin, addrRange; // for bucket computation
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
     * Sort all appended addresses and build the bucket index.
     * Detects compressed OOPs: if all addresses are 8-byte aligned and fit in 32 bits after
     * right-shifting by 3, use int[] storage to halve memory consumption.
     */
    void sort() {
        Arrays.sort(buf, 0, size); // sort in-place up to size; no copy needed
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
        buildBucketIndex();
        sorted = true;
    }

    private boolean canUseCompressedOops() {
        if (size == 0) return false;
        for (int i = 0; i < size; i++) {
            if ((buf[i] & 0x7L) != 0) return false; // not 8-byte aligned
        }
        return (buf[size - 1] >>> 3) <= 0xFFFFFFFFL; // max fits in unsigned 32-bit
    }

    private void buildBucketIndex() {
        if (size == 0) { bucket = new int[0]; bucketCount = 0; return; }
        addrMin = get(0);
        long addrMax = get(size - 1);
        addrRange = addrMax - addrMin + 1;

        // Use N/8 buckets → average 8 entries per bucket (sequential scan, cache-friendly)
        bucketCount = Math.max(1, size / 8);
        bucket = new int[bucketCount + 1];
        // Sweep sorted array once to fill bucket start positions.
        int arrIdx = 0;
        for (int b = 0; b <= bucketCount; b++) {
            long bStart = addrMin + (long) b * addrRange / bucketCount;
            while (arrIdx < size && get(arrIdx) < bStart) arrIdx++;
            bucket[b] = arrIdx;
        }
    }

    private long get(int idx) {
        if (compressedOops) {
            return Integer.toUnsignedLong(intBuf[idx]) << 3;
        }
        return buf[idx];
    }

    /** Returns the heap address of the object at sorted index idx (0-based, 0 = virtual root). */
    public long addressAt(int idx) {
        if (!sorted) throw new IllegalStateException("sort() not yet called");
        if (idx <= 0 || idx >= size) return 0L;
        return get(idx);
    }

    /**
     * Returns the index (0-based) for the given address, or -1 if not found.
     * Must be called after sort().
     */
    int indexOf(long address) {
        if (!sorted) throw new IllegalStateException("sort() not yet called");
        if (size == 0) return -1;
        if (address < addrMin) return -1;

        // O(1) bucket lookup: compute bucket index from address
        long off = address - addrMin;
        int b = (int) Math.min((off * (long) bucketCount) / addrRange, bucketCount - 1);

        int lo = bucket[b];
        // Extend hi by one extra bucket to cover integer-division boundary cases
        // where the reverse formula (off*bucketCount/addrRange) maps to b-1 for an
        // address that sits exactly at bStart of bucket b.
        int hi = (b + 2 <= bucketCount) ? bucket[b + 2] : size;

        // Linear scan within the small window (~4 entries average)
        if (compressedOops) {
            int key = (int) (address >>> 3);
            for (int i = lo; i < hi; i++) {
                int v = intBuf[i];
                if (v == key) return i;
                if (v > key) return -1;
            }
        } else {
            for (int i = lo; i < hi; i++) {
                long v = buf[i];
                if (v == address) return i;
                if (v > address) return -1;
            }
        }
        return -1;
    }

    /** Release the sorted address arrays after indexOf() will no longer be called. Frees ~N*4 or ~N*8 bytes. */
    void freeSortedArrays() {
        buf    = null;
        intBuf = null;
        bucket = null;
    }

    boolean isCompressedOops() { return compressedOops; }
}
