/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class HprofClassInfo {
    private final long classId;
    private final long superClassId;
    private final List<FieldDef> instanceFields;

    public HprofClassInfo(long classId, long superClassId, List<FieldDef> instanceFields) {
        this.classId = classId;
        this.superClassId = superClassId;
        this.instanceFields = new ArrayList<>(instanceFields);
    }

    public long classId() {
        return classId;
    }

    public long superClassId() {
        return superClassId;
    }

    public List<HprofType> instanceFieldTypes() {
        List<HprofType> types = new ArrayList<>(instanceFields.size());
        for (FieldDef def : instanceFields) {
            types.add(def.type());
        }
        return Collections.unmodifiableList(types);
    }

    public List<FieldDef> instanceFields() {
        return Collections.unmodifiableList(instanceFields);
    }

    public record FieldDef(long nameId, HprofType type) {}
}