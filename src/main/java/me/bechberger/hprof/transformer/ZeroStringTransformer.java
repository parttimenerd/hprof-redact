/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

import static me.bechberger.hprof.transformer.TransformerUtil.zeroPreservingUtf8String;

/**
 * Transformer that replaces all UTF-8 strings with repeated '0' characters.
 * Note: This preserves character length but not byte length for non-ASCII strings.
 */
public final class ZeroStringTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        return zeroPreservingUtf8String(value);
    }
}