/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

import me.bechberger.hprof.HprofTransformer;

/**
 * Transformer that replaces all UTF-8 strings with repeated '0' characters.
 * Note: This preserves character length but not byte length for non-ASCII strings.
 */
public final class ZeroStringTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? null : "";
        }
        return TransformerUtil.zeroCharacterString(value);
    }
}