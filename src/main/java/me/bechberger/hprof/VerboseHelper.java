/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import java.io.PrintStream;
import java.util.HashMap;
import java.util.Map;

final class VerboseHelper {
    private final PrintStream out;
    private final Map<Long, Long> classNameIds = new HashMap<>();
    private final Map<Long, String> nameStrings = new HashMap<>();

    VerboseHelper(PrintStream out) {
        this.out = out;
    }

    void recordClassNameId(long classId, long nameId) {
        classNameIds.putIfAbsent(classId, nameId);
    }

    void recordNameString(HprofRedact.NameKind kind, long id, String value, boolean onlyIfAbsent) {
        if (kind != HprofRedact.NameKind.CLASS_NAME && kind != HprofRedact.NameKind.FIELD_NAME) {
            return;
        }
        if (onlyIfAbsent) {
            nameStrings.putIfAbsent(id, value);
        } else {
            nameStrings.put(id, value);
        }
    }

    String resolveClassName(long classId) {
        Long nameId = classNameIds.get(classId);
        if (nameId == null) {
            return "class#" + classId;
        }
        return resolveName(nameId, "class#" + classId);
    }

    String resolveName(long nameId, String fallback) {
        return nameStrings.getOrDefault(nameId, fallback);
    }

    void logUtf8Change(HprofRedact.NameKind kind, long id, String original, String transformed) {
        String kindLabel = kind == null ? "UTF8" : kind.name();
        out.println(kindLabel + " id=" + id + ": " + original + " -> " + transformed);
    }

    void logFieldChange(String className, String fieldName, String oldValue, String newValue, boolean isStatic) {
        String prefix = isStatic ? "static " : "";
        out.println(prefix + className + "." + fieldName + ": " + oldValue + " -> " + newValue);
    }

    void logArrayChanges(long arrayId, String type, boolean[] before, boolean[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, byte[] before, byte[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, short[] before, short[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, char[] before, char[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, int[] before, int[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, long[] before, long[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, float[] before, float[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (Float.floatToRawIntBits(before[i]) != Float.floatToRawIntBits(after[i])) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    void logArrayChanges(long arrayId, String type, double[] before, double[] after) {
        if (before == null) {
            return;
        }
        int count = 0;
        String firstChange = null;
        String lastChange = null;
        for (int i = 0; i < before.length; i++) {
            if (Double.doubleToRawLongBits(before[i]) != Double.doubleToRawLongBits(after[i])) {
                count++;
                String change = "[" + i + "] " + before[i] + " -> " + after[i];
                if (firstChange == null) {
                    firstChange = change;
                }
                lastChange = change;
            }
        }
        logArraySummary(arrayId, type, count, firstChange, lastChange);
    }

    private void logArraySummary(long arrayId, String type, int count, String firstChange, String lastChange) {
        if (count == 0) {
            return;
        }
        if (count == 1 || firstChange == null || lastChange == null) {
            out.println("array#" + arrayId + " " + type + ": " + count + " changed (" + firstChange + ")");
            return;
        }
        out.println("array#" + arrayId + " " + type + ": " + count + " changed (first " + firstChange
                + ", last " + lastChange + ")");
    }
}