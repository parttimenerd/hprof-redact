/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeaderScannerTest {

    private static final byte[] MAGIC = "JAVA PROFILE 1.0.2\0".getBytes(StandardCharsets.US_ASCII);
    private static final int MAGIC_LEN = MAGIC.length; // 20

    // -----------------------------------------------------------------------
    // Helper
    // -----------------------------------------------------------------------

    private static List<DiagnosticReport.HeaderOccurrence> scan(byte[] data) throws IOException {
        return HeaderScanner.scan(new ByteArrayInputStream(data));
    }

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] result = new byte[total];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, pos, result, pos, p.length);
            pos += p.length;
        }
        return result;
    }

    /** Concatenate parts sequentially (not relying on pos trick above). */
    private static byte[] join(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) total += p.length;
        byte[] result = new byte[total];
        int cursor = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, result, cursor, p.length);
            cursor += p.length;
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Test 1: single header — no duplicates
    // -----------------------------------------------------------------------

    @Test
    void testSingleHeader() throws IOException {
        byte[] junk = new byte[200]; // all zeros, but not a magic sequence
        byte[] data = join(MAGIC, junk);

        List<DiagnosticReport.HeaderOccurrence> results = scan(data);

        assertEquals(1, results.size(), "should find exactly one header");
        assertEquals(0L, results.get(0).decompressedOffset(), "header must be at offset 0");
        assertEquals("JAVA PROFILE 1.0.2", results.get(0).magic());
    }

    // -----------------------------------------------------------------------
    // Test 2: concatenated dump — two headers
    // -----------------------------------------------------------------------

    @Test
    void testConcatenatedDump() throws IOException {
        byte[] pad = new byte[100]; // 100 zero bytes between the two headers
        byte[] data = join(MAGIC, pad, MAGIC);

        List<DiagnosticReport.HeaderOccurrence> results = scan(data);

        assertEquals(2, results.size(), "should find two headers");

        assertEquals(0L, results.get(0).decompressedOffset());
        assertEquals("JAVA PROFILE 1.0.2", results.get(0).magic());

        long expectedSecond = MAGIC_LEN + pad.length; // 20 + 100 = 120
        assertEquals(expectedSecond, results.get(1).decompressedOffset(),
                "second header offset should be " + expectedSecond);
        assertEquals("JAVA PROFILE 1.0.2", results.get(1).magic());
    }

    // -----------------------------------------------------------------------
    // Test 3: no magic at all
    // -----------------------------------------------------------------------

    @Test
    void testNoMagic() throws IOException {
        byte[] data = new byte[200]; // all zeros

        List<DiagnosticReport.HeaderOccurrence> results = scan(data);

        assertTrue(results.isEmpty(), "should find no headers in all-zero data");
    }

    // -----------------------------------------------------------------------
    // Test 4: magic straddles a 64KB chunk boundary
    // -----------------------------------------------------------------------

    @Test
    void testMagicStraddlesChunkBoundary() throws IOException {
        // We want the second magic to start at offset 65530, which is 14 bytes before
        // the 64KB boundary (65536). With a 20-byte magic, it will straddle the boundary
        // (first 6 bytes in the first chunk window, last 14 in the second).
        int firstMagicEnd = MAGIC_LEN;       // 20
        int chunkSize = 64 * 1024;            // 65536
        int secondMagicOffset = 65530;        // straddles the 64KB boundary

        // Build: MAGIC + zeros to fill up to secondMagicOffset + MAGIC + trailing zeros
        int zerosBeforeSecond = secondMagicOffset - firstMagicEnd;
        byte[] zeros1 = new byte[zerosBeforeSecond];
        byte[] zeros2 = new byte[100];

        byte[] data = join(MAGIC, zeros1, MAGIC, zeros2);

        List<DiagnosticReport.HeaderOccurrence> results = scan(data);

        assertEquals(2, results.size(),
                "should find 2 headers even when the second straddles a chunk boundary");
        assertEquals(0L, results.get(0).decompressedOffset());
        assertEquals((long) secondMagicOffset, results.get(1).decompressedOffset(),
                "second header should be at offset " + secondMagicOffset);
        assertEquals("JAVA PROFILE 1.0.2", results.get(1).magic());
    }
}
