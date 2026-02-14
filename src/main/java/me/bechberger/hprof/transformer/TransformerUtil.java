/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.transformer;

import java.nio.charset.StandardCharsets;

/**
 * Utility methods for string transformation that preserve UTF-8 byte lengths.
 */
public final class TransformerUtil {
    private TransformerUtil() {}

    /**
     * Transform a string to all zero bytes while preserving the exact byte length when encoded as UTF-8.
     * This ensures the binary format remains the same size after transformation.
     *
     * @param value the original string value
     * @return a string that encodes to the same number of bytes as the original, but with all zero bytes
     */
    public static String zeroPreservingUtf8String(String value) {
        byte[] originalBytes = value.getBytes(StandardCharsets.UTF_8);
        byte[] zeros = new byte[originalBytes.length];
        // zeros array is initialized to 0x00 by default in Java
        return new String(zeros, StandardCharsets.UTF_8);
    }

    /**
     * Transform a string to repeated '0' characters while preserving character length.
     * Note: This only preserves byte length for ASCII strings.
     *
     * @param value the original string value
     * @return a string with the same character length but all '0' characters
     */
    public static String zeroCharacterString(String value) {
        return "0".repeat(value.length());
    }
}