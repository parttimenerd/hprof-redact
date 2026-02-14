/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

public final class DropStringTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        return "";
    }
}
