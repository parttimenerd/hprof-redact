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
 * {@code objectFieldOffsets[i]} — byte offset within THE FULL instance data blob for that
 * field. This offset includes the walk through the class hierarchy — subclass fields first,
 * then super's fields at offset = subclass's ownFieldsSize, etc. Populated in
 * HeapGraphBuilder.resolveInheritedFieldOffsets() after all ClassRecords are built.
 * {@code ownFieldsSize} — size in bytes of THIS class's own instance fields (used to
 * chain offsets into superclass field positions).
 */
record ClassRecord(
    long classId,
    String name,
    long classLoaderId,
    long superClassId,
    int instanceSize,
    int classSerialNumber,
    short[] objectFieldNameIds,   // interned name index per OBJECT-type instance field (full hierarchy)
    int[]   objectFieldOffsets,   // byte offset in FULL instance data for each OBJECT-type field
    int     ownFieldsSize         // bytes for THIS class's own fields (excluding inherited)
) {
    static final short NO_NAME = 0;
}
