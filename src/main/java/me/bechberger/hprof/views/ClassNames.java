/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.views;

/**
 * Utilities for rendering class names for display.
 *
 * Class names are stored internally in a mix of forms:
 * - JVMS descriptor form for obj arrays: "[Ljava/lang/Object;", "[[I"
 * - Human-readable form for primitive arrays: "int[]", "byte[]"
 * - Internal name for classes: "java/lang/Object"
 *
 * {@link #pretty(String)} normalizes all of these into human-readable form.
 */
final class ClassNames {
    private ClassNames() {}

    /**
     * Convert a raw class name (possibly JVMS descriptor) into human-readable form.
     * Examples:
     *   "[Ljava/lang/Object;"  -> "java.lang.Object[]"
     *   "[[Ljava/lang/Object;" -> "java.lang.Object[][]"
     *   "[I"                   -> "int[]"
     *   "[[I"                  -> "int[][]"
     *   "java/lang/Object"     -> "java.lang.Object"
     *   "int[]"                -> "int[]"    (already pretty; passes through)
     *   "byte[]"               -> "byte[]"   (already pretty; passes through)
     */
    static String pretty(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        if (!raw.startsWith("[")) return raw.replace('/', '.');

        int dims = 0;
        while (dims < raw.length() && raw.charAt(dims) == '[') dims++;
        String rest = raw.substring(dims);

        String base;
        if (rest.length() == 1) {
            base = switch (rest.charAt(0)) {
                case 'Z' -> "boolean";
                case 'B' -> "byte";
                case 'C' -> "char";
                case 'S' -> "short";
                case 'I' -> "int";
                case 'J' -> "long";
                case 'F' -> "float";
                case 'D' -> "double";
                default  -> rest;
            };
        } else if (rest.startsWith("L") && rest.endsWith(";")) {
            base = rest.substring(1, rest.length() - 1).replace('/', '.');
        } else {
            base = rest.replace('/', '.');
        }

        StringBuilder sb = new StringBuilder(base.length() + 2 * dims);
        sb.append(base);
        for (int i = 0; i < dims; i++) sb.append("[]");
        return sb.toString();
    }
}
