/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

public final class ZeroStringTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? null : "";
        }
        return "0".repeat(value.length());
    }
}
