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
     * objectFieldNameIds and objectFieldOffsets can be nulled after Phase A2
     * via freeFieldArrays() — they are not used by report writers.
     */
    final class Full implements ClassRecord {
        private final long classId;
        private final String name;
        private final long classLoaderId;
        private final long superClassId;
        private final int instanceSize;
        private final int classSerialNumber;
        // Nullable after Phase A2 (only used during edge scanning)
        private short[] objectFieldNameIds;
        private int[]   objectFieldOffsets;
        private final int ownFieldsSize;

        Full(long classId, String name, long classLoaderId, long superClassId,
             int instanceSize, int classSerialNumber,
             short[] objectFieldNameIds, int[] objectFieldOffsets, int ownFieldsSize) {
            this.classId = classId;
            this.name = name;
            this.classLoaderId = classLoaderId;
            this.superClassId = superClassId;
            this.instanceSize = instanceSize;
            this.classSerialNumber = classSerialNumber;
            this.objectFieldNameIds = objectFieldNameIds;
            this.objectFieldOffsets = objectFieldOffsets;
            this.ownFieldsSize = ownFieldsSize;
        }

        @Override public long classId()               { return classId; }
        @Override public String name()                { return name; }
        @Override public long classLoaderId()         { return classLoaderId; }
        @Override public long superClassId()          { return superClassId; }
        @Override public int instanceSize()           { return instanceSize; }
        @Override public int classSerialNumber()      { return classSerialNumber; }
        @Override public short[] objectFieldNameIds() { return objectFieldNameIds != null ? objectFieldNameIds : new short[0]; }
        @Override public int[]   objectFieldOffsets() { return objectFieldOffsets != null ? objectFieldOffsets : new int[0]; }
        @Override public int ownFieldsSize()          { return ownFieldsSize; }

        /** Release field layout arrays — only needed during A2 edge scanning. */
        void freeFieldArrays() { objectFieldNameIds = null; objectFieldOffsets = null; }
    }
}
