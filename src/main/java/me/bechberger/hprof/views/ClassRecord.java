/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/**
 * Sealed interface for class metadata entries in HeapGraph.classList.
 *
 * Two variants:
 *   Full         — real class from HPROF_GC_CLASS_DUMP; carries all metadata
 *   ArrayClassRecord — synthesized entry for array types; stores only the name
 *
 * Using ArrayClassRecord for array entries saves ~7 fields × ~8 bytes each per
 * array class object, reducing heap pressure on large dumps.
 */
public sealed interface ClassRecord permits ClassRecord.Full, ArrayClassRecord {

    static final short NO_NAME = 0;

    String name();
    long classId();
    long superClassId();
    long classLoaderId();
    int instanceSize();
    int classSerialNumber();
    short[] objectFieldNameIds();
    int[] objectFieldOffsets();
    int ownFieldsSize();

    /**
     * Full class metadata from HPROF_GC_CLASS_DUMP records.
     */
    record Full(
            long classId,
            String name,
            long classLoaderId,
            long superClassId,
            int instanceSize,
            int classSerialNumber,
            short[] objectFieldNameIds,
            int[]   objectFieldOffsets,
            int     ownFieldsSize
    ) implements ClassRecord {}
}
