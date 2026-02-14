/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

public final class HprofConstants {
    private HprofConstants() {}

    public static final int HPROF_UTF8 = 0x01;
    public static final int HPROF_LOAD_CLASS = 0x02;
    public static final int HPROF_UNLOAD_CLASS = 0x03;
    public static final int HPROF_FRAME = 0x04;
    public static final int HPROF_TRACE = 0x05;
    public static final int HPROF_ALLOC_SITES = 0x06;
    public static final int HPROF_HEAP_SUMMARY = 0x07;
    public static final int HPROF_START_THREAD = 0x0A;
    public static final int HPROF_END_THREAD = 0x0B;
    public static final int HPROF_HEAP_DUMP = 0x0C;
    public static final int HPROF_CPU_SAMPLES = 0x0D;
    public static final int HPROF_CONTROL_SETTINGS = 0x0E;

    public static final int HPROF_HEAP_DUMP_SEGMENT = 0x1C;
    public static final int HPROF_HEAP_DUMP_END = 0x2C;

    public static final int HPROF_GC_ROOT_UNKNOWN = 0xFF;
    public static final int HPROF_GC_ROOT_JNI_GLOBAL = 0x01;
    public static final int HPROF_GC_ROOT_JNI_LOCAL = 0x02;
    public static final int HPROF_GC_ROOT_JAVA_FRAME = 0x03;
    public static final int HPROF_GC_ROOT_NATIVE_STACK = 0x04;
    public static final int HPROF_GC_ROOT_STICKY_CLASS = 0x05;
    public static final int HPROF_GC_ROOT_THREAD_BLOCK = 0x06;
    public static final int HPROF_GC_ROOT_MONITOR_USED = 0x07;
    public static final int HPROF_GC_ROOT_THREAD_OBJ = 0x08;
    public static final int HPROF_GC_CLASS_DUMP = 0x20;
    public static final int HPROF_GC_INSTANCE_DUMP = 0x21;
    public static final int HPROF_GC_OBJ_ARRAY_DUMP = 0x22;
    public static final int HPROF_GC_PRIM_ARRAY_DUMP = 0x23;

    public static final int HPROF_TYPE_ARRAY_OBJECT = 0x01;
    public static final int HPROF_TYPE_OBJECT = 0x02;
    public static final int HPROF_TYPE_BOOLEAN = 0x04;
    public static final int HPROF_TYPE_CHAR = 0x05;
    public static final int HPROF_TYPE_FLOAT = 0x06;
    public static final int HPROF_TYPE_DOUBLE = 0x07;
    public static final int HPROF_TYPE_BYTE = 0x08;
    public static final int HPROF_TYPE_SHORT = 0x09;
    public static final int HPROF_TYPE_INT = 0x0A;
    public static final int HPROF_TYPE_LONG = 0x0B;
}