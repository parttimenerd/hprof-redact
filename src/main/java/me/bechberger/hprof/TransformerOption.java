/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import java.util.Locale;
import me.bechberger.hprof.transformer.DropStringTransformer;
import me.bechberger.hprof.transformer.ZeroPrimitiveTransformer;
import me.bechberger.hprof.transformer.ZeroStringTransformer;

enum TransformerOption {
    ZERO("zero"),
    ZERO_STRINGS("zero-strings"),
    DROP_STRINGS("drop-strings");

    private final String optionName;

    TransformerOption(String optionName) {
        this.optionName = optionName;
    }

    static TransformerOption fromOption(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZERO;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (TransformerOption option : values()) {
            if (option.optionName.equals(normalized) || option.name().equalsIgnoreCase(normalized)) {
                return option;
            }
        }
        throw new IllegalArgumentException("Unknown transformer: " + raw);
    }

    HprofTransformer create() {
        return switch (this) {
            case ZERO -> new ZeroPrimitiveTransformer();
            case ZERO_STRINGS -> new ZeroStringTransformer();
            case DROP_STRINGS -> new DropStringTransformer();
        };
    }
}