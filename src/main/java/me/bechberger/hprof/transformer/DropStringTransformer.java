/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

import me.bechberger.hprof.HprofTransformer;

/**
 * Transformer that replaces all UTF-8 strings with empty strings.
 */
public final class DropStringTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        return "";
    }
}