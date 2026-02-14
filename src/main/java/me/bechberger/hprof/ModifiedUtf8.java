/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal Modified UTF-8 (MUTF-8) codec compatible with HotSpot Symbols.
 *
 * <p>HPROF "UTF8" records in HotSpot are emitted from the SymbolTable and therefore
 * use HotSpot's modified UTF-8 encoding rules (same as DataInputStream.readUTF,
 * but without the 2-byte length prefix).
 */
final class ModifiedUtf8 {
    private ModifiedUtf8() {}

    static String decode(byte[] bytes) {
        // Fast path: ASCII (and no NUL)
        boolean ascii = true;
        for (byte b : bytes) {
            if ((b & 0x80) != 0 || b == 0) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }

        StringBuilder sb = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                // Not expected in valid MUTF-8, but tolerate.
                sb.append('\u0000');
                i++;
                continue;
            }
            if (b <= 0x7F) {
                sb.append((char) b);
                i++;
                continue;
            }
            if ((b & 0xE0) == 0xC0) {
                if (i + 1 >= bytes.length) {
                    throw new IllegalArgumentException("Truncated modified UTF-8 sequence");
                }
                int b2 = bytes[i + 1] & 0xFF;
                if ((b2 & 0xC0) != 0x80) {
                    throw new IllegalArgumentException("Invalid modified UTF-8 continuation byte");
                }
                int ch = ((b & 0x1F) << 6) | (b2 & 0x3F);
                // MUTF-8 encodes NUL as 0xC0 0x80
                sb.append((char) ch);
                i += 2;
                continue;
            }
            if ((b & 0xF0) == 0xE0) {
                if (i + 2 >= bytes.length) {
                    throw new IllegalArgumentException("Truncated modified UTF-8 sequence");
                }
                int b2 = bytes[i + 1] & 0xFF;
                int b3 = bytes[i + 2] & 0xFF;
                if ((b2 & 0xC0) != 0x80 || (b3 & 0xC0) != 0x80) {
                    throw new IllegalArgumentException("Invalid modified UTF-8 continuation byte");
                }
                int ch = ((b & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                sb.append((char) ch);
                i += 3;
                continue;
            }

            // HotSpot Symbol modified UTF-8 shouldn't include 4-byte sequences.
            throw new IllegalArgumentException("Unsupported modified UTF-8 leading byte: 0x" + Integer.toHexString(b));
        }
        return sb.toString();
    }

    static byte[] encode(String value) {
        // Most symbols are ASCII.
        boolean ascii = true;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 0 || c > 0x7F) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            return value.getBytes(StandardCharsets.ISO_8859_1);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            int c = value.charAt(i);
            if (c >= 0x0001 && c <= 0x007F) {
                out.write(c);
            } else if (c <= 0x07FF) {
                out.write(0xC0 | ((c >> 6) & 0x1F));
                out.write(0x80 | (c & 0x3F));
            } else {
                out.write(0xE0 | ((c >> 12) & 0x0F));
                out.write(0x80 | ((c >> 6) & 0x3F));
                out.write(0x80 | (c & 0x3F));
            }
        }
        return out.toByteArray();
    }
}