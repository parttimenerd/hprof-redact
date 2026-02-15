/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

/**
 * Transformer that replaces all UTF-8 strings with empty strings.
 */
public final class DropStringTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        if (value == null) {
            return null;
        }
        return "";
    }
}