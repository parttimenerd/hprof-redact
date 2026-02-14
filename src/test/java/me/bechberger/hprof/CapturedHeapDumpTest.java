/*
 * Copyright (c) 2026.
 * SPDX-License-Identifier: MIT
 */
package me.bechberger.hprof;

import me.bechberger.hprof.HprofFilter;
import me.bechberger.hprof.HprofIo;
import me.bechberger.hprof.HprofTransformer;
import me.bechberger.hprof.ZeroPrimitiveTransformer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests using real captured heap dumps from test_programs.
 */
class CapturedHeapDumpTest {

    private static final Path HEAP_DUMPS_DIR = Paths.get("heap_dumps");

    /**
     * Provides all available heap dump files (.hprof and .hprof.gz).
     */
    static Stream<Path> heapDumpFiles() throws IOException {
        if (!Files.exists(HEAP_DUMPS_DIR) || !Files.isDirectory(HEAP_DUMPS_DIR)) {
            return Stream.empty();
        }

        return Files.list(HEAP_DUMPS_DIR)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.endsWith(".hprof") || name.endsWith(".hprof.gz");
                })
                .sorted();
    }

    @ParameterizedTest(name = "Filter heap dump: {0}")
    @MethodSource("heapDumpFiles")
    void canFilterCapturedHeapDump(Path heapDumpPath) throws Exception {
        assertNotNull(heapDumpPath, "Heap dump path should not be null");
        assertTrue(Files.exists(heapDumpPath), "Heap dump file should exist: " + heapDumpPath);
        assertTrue(comparableSize(heapDumpPath) > 0, "Heap dump file should not be empty: " + heapDumpPath);

        Path outputPath = Files.createTempFile("filtered-", ".hprof");
        try {
            // Apply zero transformer to the heap dump
            try (OutputStream out = HprofIo.openOutputStream(outputPath)) {
                HprofFilter.filter(heapDumpPath, out, new ZeroPrimitiveTransformer());
            }

            // Verify output was created and has content
            assertTrue(Files.exists(outputPath), "Output file should exist");
            assertTrue(comparableSize(outputPath) > 0, "Output file should not be empty");

            // Output should be smaller or similar size (zeros may compress better)
            long inputSize = comparableSize(heapDumpPath);
            long outputSize = comparableSize(outputPath);
            assertTrue(outputSize > 0, "Output should have content");

            System.out.printf("Filtered %s: input=%d bytes, output=%d bytes (%.1f%%)%n",
                    heapDumpPath.getFileName(), inputSize, outputSize,
                    100.0 * outputSize / inputSize);
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    @ParameterizedTest(name = "Round-trip with identity transformer (exact match): {0}")
    @MethodSource("heapDumpFiles")
    void roundTripWithIdentityTransformer(Path heapDumpPath) throws Exception {
        assertNotNull(heapDumpPath, "Heap dump path should not be null");
        assertTrue(Files.exists(heapDumpPath), "Heap dump file should exist: " + heapDumpPath);

        Path outputPath = Files.createTempFile("roundtrip-", ".hprof");
        try {
            // Apply identity transformer (should produce identical output)
            try (OutputStream out = HprofIo.openOutputStream(outputPath)) {
                HprofFilter.filter(heapDumpPath, out, new HprofTransformer() {});
            }

            // Verify output was created
            assertTrue(Files.exists(outputPath), "Output file should exist");

            long inputSize = comparableSize(heapDumpPath);
            long outputSize = comparableSize(outputPath);

            // With identity transformer, files should be EXACTLY the same
            assertEquals(inputSize, outputSize,
                "File sizes must match with identity transformer for " + heapDumpPath.getFileName());

            // Verify byte-for-byte equality (decompressed for .gz inputs)
            byte[] inputBytes = readComparableBytes(heapDumpPath);
            byte[] outputBytes = readComparableBytes(outputPath);
            assertArrayEquals(inputBytes, outputBytes,
                "Files must be byte-for-byte identical with identity transformer for " + heapDumpPath.getFileName());

            System.out.printf("✓ Round-trip exact match %s: %d bytes%n",
                    heapDumpPath.getFileName(), inputSize);
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    @ParameterizedTest(name = "Round-trip with primitive redaction (same size): {0}")
    @MethodSource("heapDumpFiles")
    void roundTripWithPrimitiveRedactionSameSize(Path heapDumpPath) throws Exception {
        assertNotNull(heapDumpPath, "Heap dump path should not be null");
        assertTrue(Files.exists(heapDumpPath), "Heap dump file should exist: " + heapDumpPath);

        Path outputPath = Files.createTempFile("roundtrip-primitives-", ".hprof");
        try {
            // Apply transformer that only redacts primitive values
            HprofTransformer transformer = new HprofTransformer() {
                @Override
                public int transformInt(int value) {
                    return 0;
                }

                @Override
                public long transformLong(long value) {
                    return 0L;
                }
            };

            try (OutputStream out = HprofIo.openOutputStream(outputPath)) {
                HprofFilter.filter(heapDumpPath, out, transformer);
            }

            assertTrue(Files.exists(outputPath), "Output file should exist");

            long inputSize = comparableSize(heapDumpPath);
            long outputSize = comparableSize(outputPath);

            // When only redacting primitives, file size must remain the same
            // (we're replacing values, not changing structure)
            assertEquals(inputSize, outputSize,
                "File sizes must match when only redacting primitives for " + heapDumpPath.getFileName());

            System.out.printf("✓ Primitive redaction same size %s: %d bytes%n",
                    heapDumpPath.getFileName(), inputSize);
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    @ParameterizedTest(name = "Round-trip with all primitives redacted (same size): {0}")
    @MethodSource("heapDumpFiles")
    void roundTripWithAllPrimitivesRedactedSameSize(Path heapDumpPath) throws Exception {
        assertNotNull(heapDumpPath, "Heap dump path should not be null");
        assertTrue(Files.exists(heapDumpPath), "Heap dump file should exist: " + heapDumpPath);

        Path outputPath = Files.createTempFile("roundtrip-all-primitives-", ".hprof");
        try {
            // Apply transformer that redacts all primitive types
            HprofTransformer transformer = new HprofTransformer() {
                @Override
                public boolean transformBoolean(boolean value) {
                    return false;
                }

                @Override
                public byte transformByte(byte value) {
                    return 0;
                }

                @Override
                public short transformShort(short value) {
                    return 0;
                }

                @Override
                public char transformChar(char value) {
                    return 0;
                }

                @Override
                public int transformInt(int value) {
                    return 0;
                }

                @Override
                public long transformLong(long value) {
                    return 0L;
                }

                @Override
                public float transformFloat(float value) {
                    return 0.0f;
                }

                @Override
                public double transformDouble(double value) {
                    return 0.0;
                }
            };

            try (OutputStream out = HprofIo.openOutputStream(outputPath)) {
                HprofFilter.filter(heapDumpPath, out, transformer);
            }

            assertTrue(Files.exists(outputPath), "Output file should exist");

            long inputSize = comparableSize(heapDumpPath);
            long outputSize = comparableSize(outputPath);

            // File size must remain the same when redacting all primitives
            assertEquals(inputSize, outputSize,
                "File sizes must match when redacting all primitives for " + heapDumpPath.getFileName());

            System.out.printf("✓ All primitives redacted same size %s: %d bytes%n",
                    heapDumpPath.getFileName(), inputSize);
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    @ParameterizedTest(name = "hprof-slurp --json matches after redaction: {0}")
    @MethodSource("heapDumpFiles")
    void hprofSlurpJsonMatchesPreAndPostRedaction(Path heapDumpPath) throws Exception {
        assertNotNull(heapDumpPath, "Heap dump path should not be null");
        assertTrue(Files.exists(heapDumpPath), "Heap dump file should exist: " + heapDumpPath);

        // Ensure tool is installed (downloaded+cached) or skip test with a clear reason.
        HprofSlurpRunner.ensureInstalledOrSkip();

        Path uncompressedInput = materializeUncompressedIfNeeded(heapDumpPath);
        Path redacted = Files.createTempFile("redacted-", ".hprof");
        Path preJson = null;
        Path postJson = null;
        try {
            try (OutputStream out = HprofIo.openOutputStream(redacted)) {
                // Redacting primitives must not change instance counts/sizes.
                HprofFilter.filter(heapDumpPath, out, new ZeroPrimitiveTransformer());
            }

            preJson = HprofSlurpRunner.runJson(uncompressedInput);
            postJson = HprofSlurpRunner.runJson(redacted);

            ObjectMapper mapper = new ObjectMapper();
            JsonNode pre = normalizeSlurpJson(mapper.readTree(Files.readString(preJson)));
            JsonNode post = normalizeSlurpJson(mapper.readTree(Files.readString(postJson)));

            assertEquals(pre, post, "hprof-slurp JSON differs for " + heapDumpPath.getFileName());
        } finally {
            Files.deleteIfExists(redacted);
            if (preJson != null) Files.deleteIfExists(preJson);
            if (postJson != null) Files.deleteIfExists(postJson);
            if (uncompressedInput != null && uncompressedInput != heapDumpPath) {
                Files.deleteIfExists(uncompressedInput);
            }
        }
    }

    @Test
    void canListAvailableHeapDumps() throws IOException {
        if (!Files.exists(HEAP_DUMPS_DIR)) {
            System.out.println("Heap dumps directory does not exist: " + HEAP_DUMPS_DIR);
            return;
        }

        List<Path> heapDumps = heapDumpFiles().toList();
        System.out.println("Found " + heapDumps.size() + " heap dump(s):");
        for (Path dump : heapDumps) {
            System.out.printf("  - %s (%d bytes)%n",
                    dump.getFileName(), comparableSize(dump));
        }

        // Test should pass even if no heap dumps are found
        // (they need to be generated first)
    }

    @ParameterizedTest(name = "Custom redaction transformer: {0}")
    @MethodSource("heapDumpFiles")
    void customRedactionTransformer(Path heapDumpPath) throws Exception {
        assertNotNull(heapDumpPath, "Heap dump path should not be null");
        assertTrue(Files.exists(heapDumpPath), "Heap dump file should exist: " + heapDumpPath);

        Path outputPath = Files.createTempFile("redacted-", ".hprof");
        try {
            // Apply custom redaction transformer
            HprofTransformer transformer = new HprofTransformer() {
                @Override
                public String transformClassName(String value) {
                    // Redact custom class names but keep standard Java classes
                    if (value != null && !value.startsWith("java.") &&
                        !value.startsWith("javax.") && !value.startsWith("sun.")) {
                        return "RedactedClass";
                    }
                    return value;
                }

                @Override
                public int transformInt(int value) {
                    return 0; // Zero out all int values
                }

                @Override
                public long transformLong(long value) {
                    return 0L; // Zero out all long values
                }
            };

            try (OutputStream out = HprofIo.openOutputStream(outputPath)) {
                HprofFilter.filter(heapDumpPath, out, transformer);
            }

            assertTrue(Files.exists(outputPath), "Output file should exist");
            assertTrue(comparableSize(outputPath) > 0, "Output file should not be empty");

            System.out.printf("Redacted %s: output=%d bytes%n",
                    heapDumpPath.getFileName(), comparableSize(outputPath));
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    @Test
    void specificHeapDumpTests() throws Exception {
        // Test specific heap dumps if they exist
        testSpecificHeapDump("SimpleObject");
        testSpecificHeapDump("ArrayTest");
        testSpecificHeapDump("CollectionsTest");
        testSpecificHeapDump("StringPoolTest");
    }

    private void testSpecificHeapDump(String testName) throws Exception {
        Path heapDump = findHeapDumpForTest(testName);
        if (heapDump == null) {
            System.out.println("Heap dump not found for: " + testName);
            return;
        }

        System.out.println("Testing specific heap dump: " + testName);

        Path outputPath = Files.createTempFile(testName + "-filtered-", ".hprof");
        try {
            try (OutputStream out = HprofIo.openOutputStream(outputPath)) {
                HprofFilter.filter(heapDump, out, new ZeroPrimitiveTransformer());
            }

            assertTrue(Files.exists(outputPath), "Output should exist for " + testName);
            assertTrue(comparableSize(outputPath) > 0, "Output should have content for " + testName);

            System.out.printf("  ✓ Successfully filtered %s%n", testName);
        } finally {
            Files.deleteIfExists(outputPath);
        }
    }

    private Path findHeapDumpForTest(String testName) throws IOException {
        if (!Files.exists(HEAP_DUMPS_DIR)) {
            return null;
        }

        return Files.list(HEAP_DUMPS_DIR)
                .filter(p -> {
                    String name = p.getFileName().toString();
                    return name.startsWith(testName + "_") &&
                           (name.endsWith(".hprof") || name.endsWith(".hprof.gz"));
                })
                .findFirst()
                .orElse(null);
    }

    /**
     * Get the uncompressed size of a file.
     * For compressed files, reads through the decompressed stream to count bytes.
     * For uncompressed files, returns the file size directly.
     */
    private long getUncompressedSize(Path path) throws IOException {
        try (var input = HprofIo.openInputStream(path)) {
            long size = 0;
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                size += bytesRead;
            }
            return size;
        }
    }

    private long comparableSize(Path path) throws IOException {
        return isGzipPath(path) ? getUncompressedSize(path) : Files.size(path);
    }

    private byte[] readComparableBytes(Path path) throws IOException {
        if (!isGzipPath(path)) {
            return Files.readAllBytes(path);
        }
        try (var input = HprofIo.openInputStream(path);
             var out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private boolean isGzipPath(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".gz");
    }

    private Path materializeUncompressedIfNeeded(Path heapDumpPath) throws IOException {
        if (!isGzipPath(heapDumpPath)) {
            return heapDumpPath;
        }
        Path tmp = Files.createTempFile("uncompressed-", ".hprof");
        try (var in = HprofIo.openInputStream(heapDumpPath);
             var out = Files.newOutputStream(tmp)) {
            in.transferTo(out);
        }
        return tmp;
    }

    private static JsonNode normalizeSlurpJson(JsonNode node) {
        // Current hprof-slurp JSON is mostly deterministic; defensively drop obvious volatile metadata if present.
        if (node instanceof ObjectNode obj) {
            Set<String> volatileKeys = Set.of(
                    "input_file", "input_path", "inputFile",
                    "elapsed_ms", "duration_ms", "duration", "generated_at", "timestamp"
            );
            for (String k : volatileKeys) {
                obj.remove(k);
            }
        }
        return node;
    }
}