/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

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
        InputStream in = Files.newInputStream(path);
        return wrapInputStream(in);
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