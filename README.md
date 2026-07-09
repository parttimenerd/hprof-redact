# hprof-tools

[![Build](https://github.com/parttimenerd/hprof-tools/actions/workflows/build.yml/badge.svg)](https://github.com/parttimenerd/hprof-tools/actions/workflows/build.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/hprof-tools)](https://search.maven.org/artifact/me.bechberger/hprof-tools)


`hprof-tools` is a suite of tools for working with Java heap dumps (HPROF format):

- **`redact`** — stream and redact sensitive data while preserving heap structure and size characteristics
- **`diagnose`** — analyze heap dump structure, size attribution, and detect anomalies
- **`views`** — generate analysis reports (Markdown or HTML) with system overview, dominator tree, thread analysis, and more

This is useful for:

- Sharing heap dumps for analysis without exposing sensitive string data
- Testing and debugging production issues safely
- Compliance and privacy requirements when handling heap dumps
- Understanding heap dump structure and size characteristics

__This is currently just an early prototype, a proof of concept. Feel free to test it and provide me with feedback.__

The implementation is based on the HPROF format specified in the [OpenJDK source code](https://github.com/openjdk/jdk/blob/49e2a6b696c2063f0b4331b0a6d064852d676fcd/src/hotspot/share/services/heapDumper.cpp).

Features:
- Stream-based processing for large heap dumps
- Configurable transformers for redacting string contents and primitive values, including arrays
- Support for redacting field names, class names, method names, and other UTF-8 strings in the heap dump
- Heap analysis reports matching Eclipse MAT output
- Tiny JAR (< 100KB) with only [femtocli](https://github.com/parttimenerd/femtocli) as a dependency for the CLI interface

## Installation

### As a Standalone JAR

Download the latest release from [GitHub Releases](https://github.com/parttimenerd/hprof-tools/releases) and run:

```bash
java -jar hprof-tools.jar redact input.hprof output.hprof
```

Or use with [JBang](https://www.jbang.dev/): `jbang hprof-tools@parttimenerd/hprof-tools`

### Via Maven

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>hprof-tools</artifactId>
    <version>0.4.0</version>
</dependency>
```

## Usage

### Command Line

`hprof-tools` has three subcommands: **`redact`**, **`diagnose`**, and **`views`**.

#### Redact

```bash
Usage: hprof-tools redact [-hV] [--compress] [--dry-run] [-t=<transformer>] [--verbose]
                          <input> <output>
Stream and redact HPROF heap dumps.
      <input>                        Input HPROF path.
      <output>                       Output HPROF path or '-' for stdout.
      --compress                     Enable compression format (omit array and string data,
                                     store only sizes).
      --dry-run                      Process the file without writing output.
  -h, --help                         Show this help message and exit.
  -t, --transformer=<transformer>    Transformer to apply (default: zero).
                                     Options: zero (zero primitives + string
                                     contents), zero-strings (zero string
                                     contents only), drop-strings (empty string
                                     contents).
  -v, --verbose                      Log changed field values to stderr.
  -V, --version                      Print version information and exit.
```

#### Diagnose

```bash
Usage: hprof-tools diagnose [-hV] [-o=<output>] [--json]
                             [--detect-duplicate-ids] [--top-n=<topN>] [--object-align=<objectAlign>]
                             [--compact-headers] [--histogram] <input>
Analyze an HPROF heap dump and report size attribution, anomalies, and MAT-vs-disk discrepancies.
      <input>                     Input HPROF path (plain or .gz).
      --compact-headers           Use compact object header size (8 bytes,
                                  JDK 25+ JEP 519) for framing overhead
                                  calculation.
      --detect-duplicate-ids      Track duplicate object IDs (uses ~16 bytes per
                                  object; may OOM on large dumps).
  -h, --help                      Show this help message and exit.
      --histogram                 Emit full per-class histogram with framing
                                  overhead column (all classes, sorted by
                                  on-disk bytes).
      --json                      Output report as JSON.
  -o, --output=<output>           Write report to file instead of stdout.
      --object-align=<objectAlign>
                                  JVM object alignment in bytes for heap size
                                  estimation (default: 8).
      --top-n=<topN>              Number of top classes/arrays to report
                                  (default: 20).
  -V, --version                   Print version information and exit.
```

The `diagnose` command performs a single-pass analysis of an HPROF file and produces a
human-readable (or JSON) report covering:

- **Problems detected** — ranked by severity (ERROR / WARNING / INFO), including anomalies
  such as concatenated dumps, corrupt segments, duplicate object IDs, and abnormally large
  UTF-8 sections
- **File summary** — magic string, identifier size, capture timestamp
- **Record and subrecord histograms** — count and byte totals per tag
- **Size attribution** — on-disk bytes broken down by category (instances, arrays, class
  dumps, GC roots, UTF-8 strings, metadata, framing overhead), plus estimated Eclipse MAT
  heap size with standard and compact object headers
- **HPROF framing overhead** — with `idSize=8`, each `INSTANCE_DUMP` subrecord carries
  25 bytes of framing (object ID, class ID, stack-trace serial) that Eclipse MAT does not
  include in its heap-size figure. At 500 million objects this accounts for ~12.5 GB.
  The report shows how much of the disk/MAT gap is explained by this expected overhead so
  you can distinguish normal large files from structural anomalies.
- **UTF-8 string analysis** — total size, referenced vs. unreferenced bytes, largest record
- **Top-N classes and arrays** — by on-disk instance bytes and element count
- **Segment issues** — segments whose declared length differs from consumed bytes
- **Duplicate HPROF headers** — detects concatenated dumps
- **Trailing bytes** — bytes after the last parseable record
- **Duplicate object IDs** — optional scan (enable with `--detect-duplicate-ids`)

#### Views

```bash
Usage: hprof-tools views [-hV] <input> <output>
Generate a heap dump analysis report (Markdown or HTML).
      <input>                     Input HPROF path (plain or .gz).
      <output>                    Output path (.md for Markdown, .html for HTML).
  -h, --help                      Show this help message and exit.
  -V, --version                   Print version information and exit.
```

The `views` command builds a full heap object graph (dominator tree, retained sizes) and generates
a report with:

- **System Overview** — heap summary, class histogram by retained heap
- **Dominator Tree** — top objects by retained heap
- **Thread Overview** — threads with their retained heap
- **Leak Suspects** — large single objects and class groups
- **Top Consumers** — per-class breakdown with instance/shallow/retained sizes
- **GC Roots** — all GC root categories with object counts

### Compression Format

When using the `--compress` option with `redact`, the output HPROF format is modified to save space by omitting array and string data:

**UTF-8 Strings (HPROF_UTF8):**
- Standard format: `[record_tag][time][length][id][data...]`
- Compress format: `[record_tag][time][-1][actual_length][id]` (no data)

**Primitive Arrays (HPROF_GC_PRIM_ARRAY_DUMP):**
- Standard format: `[id][stackTrace][numElements][elementType][elements...]`
- Compress format: `[id][stackTrace][-1][actual_numElements][elementType]` (no elements)

This format allows tools to:
- Reconstruct the original heap structure and data types
- Determine array/string sizes without parsing the content
- Significantly reduce file size by omitting bulk data

**Use case:** When you need to share heap structure information without exposing string or array contents, and downstream tools support the compressed format.

## Transformers

Note: Method names and method signatures are treated as generic UTF-8 strings because
they cannot always be distinguished reliably in HPROF records. String transformers
therefore apply to them as well.

### `zero` (default)

Zeros out both primitive values and string contents while preserving structure.

- All numeric primitives become `0` / `0.0f` / `0.0d`
- Booleans become `false`
- Strings become `"0000..."` (same length as original, preserving offsets)

**Use case:** Maximum data redaction while maintaining heap structure analysis.

### `zero-strings`

Only zeros out string contents, leaves primitive values untouched.

- All strings become `"0000..."` (same length as original)
- Primitive values preserved as-is
- Field names, class names, method names are zeroed

**Use case:** When you need primitive values for analysis but want to hide string data.

### `drop-strings`

Removes string contents entirely, replaces with empty strings.

- All strings become `""` (empty)
- Primitive values preserved as-is
- Note: This changes heap layout as strings have different sizes

**Use case:** Maximum space savings with minimal data preservation.

## Programmatic Usage

```java
import me.bechberger.hprof.redact.HprofRedact;
import me.bechberger.hprof.redact.transformer.ZeroPrimitiveTransformer;

void main() throws IOException {
    try (OutputStream out = HprofIO.openOutputStream(Path.of("output.hprof"))) {
        new HprofRedact(new ZeroPrimitiveTransformer(), null).process(
            Path.of("input.hprof"), out);
    }
}
```

### Custom Transformers

Implement [`HprofTransformer`](src/main/java/me/bechberger/hprof/redact/transformer/HprofTransformer.java):

```java
import me.bechberger.hprof.redact.transformer.HprofTransformer;

public class MyTransformer implements HprofTransformer {
    @Override
    public String transformUtf8String(String value) {
        return "REDACTED";
    }
    
    @Override
    public int transformInt(int value) {
        return -1;
    }
}
```

## Development

### Building

```bash
mvn clean package
```

This generates:
- `target/hprof-tools.jar` - Executable JAR
- `target/hprof-tools` - Native executable (if GraalVM available)

### Running Tests

```bash
mvn test
```

The test suite includes:
- Unit tests for HPROF parsing and filtering
- Integration tests with real heap dumps
- Validation against `hprof-slurp` (downloaded automatically)

### Generating Test Heap Dumps

Use the provided `capture_heap_dumps.py` script to generate test heap dumps in the `heap_dumps/` directory. 
It compiles and runs Java test programs that create various heap scenarios, captures heap dumps using `jmap`, and extracts histograms for validation.

```bash
python3 capture_heap_dumps.py
```

### Release Process

```bash
./release.py [--major|--patch]
```

This:
1. Updates version in `pom.xml`
2. Updates `CHANGELOG.md`
3. Runs tests and builds package
4. Creates git tag and commits
5. Pushes to remote
6. Creates GitHub release with artifacts

## Migrating from hprof-redact

This project was previously named `hprof-redact`. In version 0.4.0 it was renamed to `hprof-tools` to reflect its expanded scope.

**What changed:**
- CLI binary: `hprof-redact <input> <output>` → `hprof-tools redact <input> <output>` (`redact` is now an explicit subcommand)
- Maven artifact: `me.bechberger:hprof-redact` → `me.bechberger:hprof-tools`
- JBang alias: `hprof-redact@parttimenerd/hprof-redact` → `hprof-tools@parttimenerd/hprof-tools`
- Java package: `me.bechberger.hprof.HprofRedact` → `me.bechberger.hprof.redact.HprofRedact`
- Java package: `me.bechberger.hprof.transformer.*` → `me.bechberger.hprof.redact.transformer.*`

## Related Work and Inspiration

- https://github.com/agourlay/hprof-slurp: a heap-dump analyzer written in rust
- https://github.com/eaftan/hprof-parser: written in Java
- [OpenJDK heapDumper.cpp](https://github.com/openjdk/jdk/blob/49e2a6b696c2063f0b4331b0a6d064852d676fcd/src/hotspot/share/services/heapDumper.cpp): the official writer that also includes the format
- https://bugs.openjdk.org/browse/JDK-8337517: Redacted Heap Dumps, but it never got in
- https://eclipse.dev/mat/: Eclipse Memory Analyzer Tool, a powerful heap dump analysis tool

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/hprof-tools/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors
