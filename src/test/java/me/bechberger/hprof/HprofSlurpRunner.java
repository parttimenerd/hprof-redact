/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

/**
 * Test-only helper that downloads a platform-specific hprof-slurp binary (cached) and runs it.
 */
final class HprofSlurpRunner {

    static final String VERSION = "v0.6.2";

    private HprofSlurpRunner() {}

    static Path ensureInstalledOrSkip() {
        try {
            return ensureInstalled();
        } catch (Exception e) {
            Assumptions.assumeTrue(false, "hprof-slurp not available: " + e.getMessage());
            throw new AssertionError("unreachable");
        }
    }

    static Path ensureInstalled() throws IOException, InterruptedException {
        Platform platform = Platform.detect();
        String assetName = platform.assetName();

        Path cacheDir = cacheDir().resolve("hprof-slurp").resolve(VERSION).resolve(platform.cacheKey());
        Files.createDirectories(cacheDir);

        Path marker = cacheDir.resolve(".installed");
        Path exe = cacheDir.resolve(platform.exeName());

        if (Files.isRegularFile(exe) && Files.isRegularFile(marker)) {
            return exe;
        }

        // Download into a temp file then atomically move into cache.
        String downloadUrl = "https://github.com/agourlay/hprof-slurp/releases/download/" + VERSION + "/" + assetName;
        Path downloadTmp = Files.createTempFile("hprof-slurp-", platform.isZip() ? ".zip" : ".tar.gz");
        try {
            downloadTo(downloadUrl, downloadTmp);

            // Extract into cacheDir
            if (platform.isZip()) {
                extractZip(downloadTmp, cacheDir);
            } else {
                try (InputStream in = Files.newInputStream(downloadTmp)) {
                    extractTarGz(in, cacheDir);
                }
            }

            // Some archives contain the binary at root; some might nest it. Find it.
            Path found = findExecutable(cacheDir, platform);
            if (found == null) {
                throw new IOException("Could not locate extracted " + platform.exeName() + " in " + cacheDir);
            }

            if (!found.equals(exe)) {
                // Normalize to expected name to make callers simpler.
                try {
                    Files.move(found, exe, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException moveError) {
                    // If move fails (e.g., cross-device), copy then delete.
                    Files.copy(found, exe, StandardCopyOption.REPLACE_EXISTING);
                    Files.deleteIfExists(found);
                }
            }

            makeExecutable(exe);
            Files.writeString(marker, "ok");
            return exe;
        } finally {
            Files.deleteIfExists(downloadTmp);
        }
    }

    static Path runJson(Path heapDumpFile) throws IOException, InterruptedException {
        Objects.requireNonNull(heapDumpFile, "heapDumpFile");
        if (!Files.isRegularFile(heapDumpFile)) {
            throw new IOException("Heap dump does not exist: " + heapDumpFile);
        }

        Path exe = ensureInstalled();

        Path workDir = Files.createTempDirectory("hprof-slurp-");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exe.toAbsolutePath().toString(),
                    "-i", heapDumpFile.toAbsolutePath().toString(),
                    "--top", "200",
                    "--json"
            );
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);

            Process p = pb.start();
            String output;
            try (InputStream in = p.getInputStream()) {
                output = new String(in.readAllBytes());
            }
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("hprof-slurp exited with " + code + "\n" + output);
            }

            // hprof-slurp may name the file "hprof-slurp.json" or "hprof-slurp-<timestamp>.json"
            Path json;
            try (var files = Files.list(workDir)) {
                json = files
                        .filter(f -> f.getFileName().toString().startsWith("hprof-slurp") && f.getFileName().toString().endsWith(".json"))
                        .findFirst()
                        .orElse(null);
            }
            if (json == null || !Files.isRegularFile(json)) {
                throw new IOException("Expected JSON output file not found in: " + workDir + "\n" + output);
            }

            Path out = Files.createTempFile("hprof-slurp-", ".json");
            Files.copy(json, out, StandardCopyOption.REPLACE_EXISTING);
            return out;
        } finally {
            // best effort cleanup
            try {
                Files.walk(workDir)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {}
                        });
            } catch (IOException ignored) {}
        }
    }

    private static void downloadTo(String url, Path dest) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "heap-dump-filter-tests")
                .build();

        HttpResponse<Path> resp = client.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("Failed to download " + url + ": HTTP " + resp.statusCode());
        }
    }

    private static Path cacheDir() {
        String override = System.getenv("HPROF_SLURP_CACHE_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home", ".");

        if (os.contains("mac")) {
            return Path.of(home, "Library", "Caches", "heap-dump-filter");
        }

        String xdg = System.getenv("XDG_CACHE_HOME");
        if (xdg != null && !xdg.isBlank()) {
            return Path.of(xdg, "heap-dump-filter");
        }

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null && !localAppData.isBlank()) {
                return Path.of(localAppData, "heap-dump-filter", "cache");
            }
        }

        return Path.of(home, ".cache", "heap-dump-filter");
    }

    private static void extractZip(Path zipFile, Path destDir) throws IOException {
        try (var zis = new java.util.zip.ZipInputStream(Files.newInputStream(zipFile))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                Path out = destDir.resolve(Path.of(e.getName()).getFileName().toString());
                Files.createDirectories(out.getParent());
                try (OutputStream os = Files.newOutputStream(out)) {
                    zis.transferTo(os);
                }
            }
        }
    }

    // Minimal tar.gz extractor sufficient for hprof-slurp release archives.
    private static void extractTarGz(InputStream tarGz, Path destDir) throws IOException {
        try (var gzin = new java.util.zip.GZIPInputStream(tarGz)) {
            byte[] header = new byte[512];
            while (true) {
                int read = readFully(gzin, header);
                if (read == 0) {
                    return;
                }
                if (read < 512) {
                    throw new IOException("Truncated tar header");
                }

                boolean allZero = true;
                for (byte b : header) {
                    if (b != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    return;
                }

                String name = parseNullTerminatedString(header, 0, 100);
                String sizeOctal = parseNullTerminatedString(header, 124, 12).trim();
                long size = sizeOctal.isEmpty() ? 0 : Long.parseLong(sizeOctal, 8);
                int typeFlag = header[156];

                // Only extract regular files.
                boolean isRegular = typeFlag == 0 || typeFlag == '0';
                Path out = destDir.resolve(Path.of(name).getFileName().toString());

                if (isRegular) {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) {
                        copyN(gzin, os, size);
                    }
                } else {
                    skipN(gzin, size);
                }

                long padding = (512 - (size % 512)) % 512;
                if (padding > 0) {
                    skipN(gzin, padding);
                }
            }
        }
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) {
                break;
            }
            off += r;
        }
        return off;
    }

    private static void copyN(InputStream in, OutputStream out, long n) throws IOException {
        byte[] buf = new byte[8192];
        long remaining = n;
        while (remaining > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (r < 0) {
                throw new IOException("Unexpected EOF while extracting tar");
            }
            out.write(buf, 0, r);
            remaining -= r;
        }
    }

    private static void skipN(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("Unexpected EOF while skipping tar entry");
                }
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }

    private static String parseNullTerminatedString(byte[] buf, int off, int len) {
        int end = off;
        int max = off + len;
        while (end < max && buf[end] != 0) {
            end++;
        }
        return new String(buf, off, Math.max(0, end - off), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Path findExecutable(Path dir, Platform platform) throws IOException {
        // common case: extracted at root
        Path direct = dir.resolve(platform.exeName());
        if (Files.isRegularFile(direct)) {
            return direct;
        }

        try (var walk = Files.walk(dir)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(platform.exeName())
                            || p.getFileName().toString().equals("hprof-slurp")
                            || p.getFileName().toString().equals("hprof-slurp.exe"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void makeExecutable(Path exe) throws IOException {
        // Best effort across platforms.
        exe.toFile().setExecutable(true, false);
        try {
            var perms = java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x");
            Files.setPosixFilePermissions(exe, perms);
        } catch (UnsupportedOperationException ignored) {
            // Windows or non-POSIX FS.
        }
    }

    private record Platform(String os, String arch) {

        static Platform detect() {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            return new Platform(os, arch);
        }

        boolean isWindows() {
            return os.contains("win");
        }

        boolean isMac() {
            return os.contains("mac") || os.contains("darwin");
        }

        boolean isLinux() {
            return os.contains("linux");
        }

        boolean isArm64() {
            return arch.contains("aarch64") || arch.contains("arm64");
        }

        String rustArch() {
            return isArm64() ? "aarch64" : "x86_64";
        }

        String rustOs() {
            if (isMac()) {
                return "apple-darwin";
            }
            if (isLinux()) {
                return "unknown-linux-gnu";
            }
            if (isWindows()) {
                return "pc-windows-msvc";
            }
            throw new IllegalStateException("Unsupported OS for hprof-slurp tests: " + os);
        }

        String assetName() {
            String ext = isWindows() ? ".zip" : ".tar.gz";
            return "hprof-slurp-" + rustArch() + "-" + rustOs() + ext;
        }

        boolean isZip() {
            return isWindows();
        }

        String exeName() {
            return isWindows() ? "hprof-slurp.exe" : "hprof-slurp";
        }

        String cacheKey() {
            return rustArch() + "-" + rustOs();
        }
    }
}
