/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/**
 * Per-class metadata extracted from HPROF_GC_CLASS_DUMP records.
 *
 * {@code objectFieldNameIds[i]} — interned field-name index (into HeapGraph.fieldNameIntern)
 * for the i-th OBJECT-type instance field (0 = no-name sentinel for array elements).
 * {@code objectFieldOffsets[i]} — byte offset within INSTANCE_DUMP data bytes for that field.
 */
record ClassRecord(
    long classId,
    String name,
    long classLoaderId,
    long superClassId,
    int instanceSize,
    int classSerialNumber,
    short[] objectFieldNameIds,   // interned name index per OBJECT-type instance field
    int[]   objectFieldOffsets    // byte offset within instance data for each OBJECT-type field
) {
    static final short NO_NAME = 0;
}
