/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.diagnose;

import me.bechberger.hprof.core.HprofIO;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Scans a decompressed HPROF stream for all occurrences of known HPROF magic strings.
 *
 * <p>A normal HPROF file has exactly one magic string at offset 0. Finding additional
 * occurrences past offset 0 indicates that a second (or more) heap dump was concatenated
 * into the file — something Eclipse MAT silently tolerates but which can explain large
 * file-vs-parsed-heap size gaps.
 */
public final class HeaderScanner {

    private static final String[] MAGIC_STRINGS = {
        "JAVA PROFILE 1.0.2\0",
        "JAVA PROFILE 1.0.1\0",
        "JAVA PROFILE 1.0\0"
    };

    // Pre-encoded magic bytes
    private static final byte[][] MAGIC_BYTES;
    private static final int MAGIC_MAX_LENGTH;

    static {
        MAGIC_BYTES = new byte[MAGIC_STRINGS.length][];
        int maxLen = 0;
        for (int i = 0; i < MAGIC_STRINGS.length; i++) {
            MAGIC_BYTES[i] = MAGIC_STRINGS[i].getBytes(StandardCharsets.US_ASCII);
            if (MAGIC_BYTES[i].length > maxLen) {
                maxLen = MAGIC_BYTES[i].length;
            }
        }
        MAGIC_MAX_LENGTH = maxLen;
    }

    private static final int CHUNK_SIZE = 64 * 1024; // 64KB
    private static final int OVERLAP = MAGIC_MAX_LENGTH - 1; // 19 bytes

    private HeaderScanner() {}

    /**
     * Scans the decompressed stream for all occurrences of known HPROF magic strings.
     *
     * @param inputPath path to the HPROF file (may be gzip-compressed)
     * @return list of occurrences ordered by offset; the entry at offset 0 is the normal header
     * @throws IOException if the file cannot be read
     */
    public static List<DiagnosticReport.HeaderOccurrence> scan(Path inputPath) throws IOException {
        try (InputStream in = HprofIO.openInputStream(inputPath)) {
            return scan(in);
        }
    }

    /**
     * Scans the decompressed stream for all occurrences of known HPROF magic strings.
     *
     * <p>Uses a 64KB read buffer with an overlap of {@code MAGIC_MAX_LENGTH - 1} bytes across
     * chunk boundaries to ensure no match is missed.
     *
     * @param in already-decompressed input stream
     * @return list of occurrences ordered by offset
     * @throws IOException if reading fails
     */
    public static List<DiagnosticReport.HeaderOccurrence> scan(InputStream in) throws IOException {
        List<DiagnosticReport.HeaderOccurrence> results = new ArrayList<>();

        // Window = overlap bytes from previous chunk + up to CHUNK_SIZE new bytes.
        // We always read CHUNK_SIZE new bytes into window[OVERLAP..OVERLAP+CHUNK_SIZE-1].
        byte[] window = new byte[OVERLAP + CHUNK_SIZE];
        int windowFill = 0;       // number of valid bytes currently in window
        long windowOffset = 0;    // decompressed byte offset that corresponds to window[0]

        while (true) {
            // Read up to CHUNK_SIZE new bytes into the space after the overlap region.
            // (On the very first iteration there is no overlap, windowFill == 0.)
            int readStart = windowFill;
            int toRead = window.length - windowFill;

            // Fill as much as possible (read() may return less than requested).
            while (windowFill < window.length) {
                int n = in.read(window, windowFill, window.length - windowFill);
                if (n == -1) break;
                windowFill += n;
            }

            if (windowFill == readStart) {
                // No new bytes were read at all — stream was already exhausted.
                // Scan any leftover overlap bytes from the previous iteration.
                scanWindow(window, windowFill, windowOffset, OVERLAP, results);
                break;
            }

            boolean moreData = (windowFill == window.length);

            if (moreData) {
                // There may be more data. We can only safely commit (= not re-scan) matches
                // whose start positions are in [0, CHUNK_SIZE), i.e. window[0..CHUNK_SIZE-1].
                // Matches starting at or after CHUNK_SIZE will be covered by the overlap next
                // iteration.  We still need to search through the full window so that a match
                // starting just before the boundary (and extending into the overlap bytes) is
                // found; matchAtPosition checks that the full magic fits within windowFill.
                scanWindow(window, windowFill, windowOffset, CHUNK_SIZE, results);

                // Slide: carry the last OVERLAP bytes forward as the new overlap region.
                System.arraycopy(window, CHUNK_SIZE, window, 0, OVERLAP);
                windowOffset += CHUNK_SIZE;
                windowFill = OVERLAP;
            } else {
                // Last (or only) chunk — scan everything.
                scanWindow(window, windowFill, windowOffset, windowFill, results);
                break;
            }
        }

        return results;
    }

    /**
     * Scans up to {@code length} bytes in {@code window}, but only records (commits) matches
     * whose start position is strictly less than {@code commitUpTo}.
     *
     * <p>Searching through {@code length} bytes (which may include the overlap region) ensures
     * that a magic sequence starting just before the boundary — and extending into the overlap —
     * is still matched (matchAtPosition requires the full magic to fit within {@code length}).
     * However, only positions {@code < commitUpTo} are recorded to avoid double-counting when
     * the same bytes reappear in the next iteration's overlap.
     *
     * @param window      the byte buffer to search
     * @param length      total valid bytes in window (may include overlap)
     * @param windowOffset decompressed offset of window[0]
     * @param commitUpTo  only record matches whose start index is {@code < commitUpTo}
     * @param results     list to append found occurrences to
     */
    private static void scanWindow(byte[] window, int length, long windowOffset,
                                   int commitUpTo,
                                   List<DiagnosticReport.HeaderOccurrence> results) {
        int pos = 0;
        while (pos < length) {
            int matchLen = matchAtPosition(window, pos, length);
            if (matchLen > 0) {
                if (pos < commitUpTo) {
                    String magic = magicStringAt(window, pos, matchLen);
                    results.add(new DiagnosticReport.HeaderOccurrence(windowOffset + pos, magic));
                }
                pos += matchLen; // skip past the match to avoid double-counting
            } else {
                pos++;
            }
        }
    }

    /**
     * Returns the length of the longest magic byte sequence that starts at {@code pos}
     * in {@code window} (within {@code length} bytes), or 0 if none matches.
     */
    private static int matchAtPosition(byte[] window, int pos, int length) {
        for (byte[] magic : MAGIC_BYTES) {
            if (pos + magic.length > length) continue;
            boolean match = true;
            for (int i = 0; i < magic.length; i++) {
                if (window[pos + i] != magic[i]) {
                    match = false;
                    break;
                }
            }
            if (match) return magic.length;
        }
        return 0;
    }

    /**
     * Returns the magic string (without NUL terminator) for a match of {@code matchLen} bytes.
     */
    private static String magicStringAt(byte[] window, int pos, int matchLen) {
        // The magic string stored in MAGIC_STRINGS ends with \0; strip it for display
        String raw = new String(window, pos, matchLen, StandardCharsets.US_ASCII);
        // Remove trailing NUL
        if (!raw.isEmpty() && raw.charAt(raw.length() - 1) == '\0') {
            return raw.substring(0, raw.length() - 1);
        }
        return raw;
    }
}
