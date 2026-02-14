/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

public enum HprofType {
    ARRAY_OBJECT(HprofConstants.HPROF_TYPE_ARRAY_OBJECT),
    OBJECT(HprofConstants.HPROF_TYPE_OBJECT),
    BOOLEAN(HprofConstants.HPROF_TYPE_BOOLEAN),
    CHAR(HprofConstants.HPROF_TYPE_CHAR),
    FLOAT(HprofConstants.HPROF_TYPE_FLOAT),
    DOUBLE(HprofConstants.HPROF_TYPE_DOUBLE),
    BYTE(HprofConstants.HPROF_TYPE_BYTE),
    SHORT(HprofConstants.HPROF_TYPE_SHORT),
    INT(HprofConstants.HPROF_TYPE_INT),
    LONG(HprofConstants.HPROF_TYPE_LONG);

    private final int code;

    HprofType(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static HprofType fromCode(int code) {
        for (HprofType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported field type: 0x" + Integer.toHexString(code));
    }
}