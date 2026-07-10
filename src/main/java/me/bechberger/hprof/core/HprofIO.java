/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class HprofIO {
    private HprofIO() {}

    public static InputStream openInputStream(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        InputStream in = Files.newInputStream(path);
        if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
            return openTarGzInputStream(path, in);
        }
        return wrapInputStream(in);
    }

    private static InputStream openTarGzInputStream(Path path, InputStream in) throws IOException {
        // TAR format: each entry has a 512-byte header followed by the file data
        // padded to a 512-byte boundary.  Two consecutive zero blocks = end of archive.
        GZIPInputStream gz = new GZIPInputStream(new BufferedInputStream(in));

        byte[] header = gz.readNBytes(512);
        if (header.length < 512) {
            throw new IOException("Truncated TAR header in " + path.getFileName());
        }

        // Parse entry file size from octal field at bytes 124–135.
        String sizeOctal = new String(header, 124, 12, java.nio.charset.StandardCharsets.US_ASCII).trim();
        long entrySize = sizeOctal.isEmpty() ? 0L : Long.parseLong(sizeOctal, 8);

        // Peek for a second non-end-of-archive entry after skipping entry data + padding.
        // We do this before returning so the caller gets a clean single-entry guarantee.
        long dataPlusP = entrySize + (512 - (entrySize % 512)) % 512;
        gz.skipNBytes(dataPlusP);
        byte[] next = gz.readNBytes(512);
        boolean allZero = true;
        for (byte b : next) { if (b != 0) { allZero = false; break; } }
        if (!allZero) {
            throw new IOException(
                path.getFileName() + " is a multi-entry TAR archive; only single-entry .tar.gz files are supported. " +
                "Extract the .hprof file and pass it directly.");
        }
        gz.close();

        // Re-open and re-parse to return the entry data stream from the start.
        GZIPInputStream gz2 = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path)));
        gz2.skipNBytes(512); // skip header
        final long size = entrySize;
        return new java.io.FilterInputStream(gz2) {
            private long remaining = size;
            @Override public int read() throws IOException {
                if (remaining <= 0) return -1;
                int b = in.read(); if (b >= 0) remaining--; return b;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                if (remaining <= 0) return -1;
                int n = in.read(b, off, (int) Math.min(len, remaining));
                if (n > 0) remaining -= n;
                return n;
            }
        };
    }

    public static InputStream wrapInputStream(InputStream input) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(input);
        buffered.mark(2);
        int b1 = buffered.read();
        int b2 = buffered.read();
        buffered.reset();
        if (b1 == 0x1f && b2 == 0x8b) {
            return new GZIPInputStream(buffered);
        }
        return buffered;
    }

    public static OutputStream openOutputStream(Path path) throws IOException {
        OutputStream out = Files.newOutputStream(path);
        OutputStream buffered = new BufferedOutputStream(out);
        if (isGzipPath(path)) {
            return new GZIPOutputStream(buffered);
        }
        return buffered;
    }

    private static boolean isGzipPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".gz");
    }
}