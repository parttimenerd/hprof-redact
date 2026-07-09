/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/**
 * Slim class-metadata entry for synthesized array types.
 * Only the name is meaningful; all other fields return zero/empty defaults.
 */
public record ArrayClassRecord(String name) implements ClassRecord {

    @Override public long classId()           { return 0L; }
    @Override public long classLoaderId()     { return 0L; }
    @Override public long superClassId()      { return 0L; }
    @Override public int  instanceSize()      { return 0; }
    @Override public int  classSerialNumber() { return 0; }
    @Override public short[] objectFieldNameIds() { return new short[0]; }
    @Override public int[]   objectFieldOffsets()  { return new int[0]; }
    @Override public int     ownFieldsSize()       { return 0; }
}
