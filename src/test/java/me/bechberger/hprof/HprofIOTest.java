/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HprofIOTest {
    @Test
    void detectsGzipByMagicBytes() throws Exception {
        byte[] original = "payload".getBytes();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(original);
        }

        try (InputStream wrapped = HprofIO.wrapInputStream(new ByteArrayInputStream(out.toByteArray()))) {
            byte[] roundTrip = wrapped.readAllBytes();
            assertArrayEquals(original, roundTrip);
        }
    }

    @Test
    void failsFastOnInvalidGzip() {
        byte[] invalid = new byte[]{0x1f, (byte) 0x8b, 0x00, 0x00, 0x00};
        assertThrows(IOException.class, () -> {
            try (InputStream wrapped = HprofIO.wrapInputStream(new ByteArrayInputStream(invalid))) {
                wrapped.readAllBytes();
            }
        });
    }

    @Test
    void gzSuffixProducesGzipOutput() throws Exception {
        Path temp = Files.createTempFile("hprof", ".hprof.gz");
        try {
            try (var out = HprofIO.openOutputStream(temp)) {
                out.write("data".getBytes());
            }
            byte[] bytes = Files.readAllBytes(temp);
            assertTrue(bytes.length >= 2);
            assertTrue(bytes[0] == 0x1f && bytes[1] == (byte) 0x8b);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}