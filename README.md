# hprof-redact

[![Build](https://github.com/parttimenerd/hprof-redact/actions/workflows/build.yml/badge.svg)](https://github.com/parttimenerd/hprof-redact/actions/workflows/build.yml) [![Maven Central Version](https://img.shields.io/maven-central/v/me.bechberger/hprof-redact)](https://search.maven.org/artifact/me.bechberger/hprof-redact)


`hprof-redact` is a tool for processing Java heap dumps (HPROF format) to redact sensitive data while preserving
heap structure and size characteristics. This is useful for:

- Sharing heap dumps for analysis without exposing sensitive string data
- Testing and debugging production issues safely
- Compliance and privacy requirements when handling heap dumps

__This is currently just an early prototype, a proof of concept. Feel free to test it and provide me with feedback.__

The implementation is based on the HPROF format specified in the [OpenJDK source code](https://github.com/openjdk/jdk/blob/49e2a6b696c2063f0b4331b0a6d064852d676fcd/src/hotspot/share/services/heapDumper.cpp).

Features:
- Stream-based processing for large heap dumps
- Configurable transformers for redacting string contents and primitive values, including arrays
- Support for redacting field names, class names, method names, and other UTF-8 strings in the heap dump
- Tiny JAR (< 100KB) with only [femtocli](https://github.com/parttimenerd/femtocli) as a dependency for the CLI interface

Non-Features:
- It doesn't parse every section of the heap dump, it only processes the records relevant for redacting string contents and primitive values.
- It is therefore no general purpose heap dump parser.
- It has no complex redaction logic like [jfr-redact](https://github.com/parttimenerd/jfr-redact) and only supports simple transformations of string contents and primitive values, but it can be extended with custom transformers.

## Installation

### As a Standalone JAR

Download the latest release from [GitHub Releases](https://github.com/parttimenerd/hprof-redact/releases) and run:

```bash
java -jar hprof-redact.jar input.hprof output.hprof
```

Or use with [JBang](https://www.jbang.dev/): `jbang hprof-redact@parttimenerd/hprof-redact`

### Via Maven

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>hprof-redact</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Usage

### Command Line

```bash
Usage: hprof-redact [-hV] [--transformer=<transformer>] [--verbose] <input>
                    <output>
Stream and redact HPROF heap dumps.
      <input>                        Input HPROF path.
      <output>                       Output HPROF path or '-' for stdout.
  -h, --help                         Show this help message and exit.
  -t, --transformer=<transformer>    Transformer to apply (default: zero).
                                     Options: zero (zero primitives + string
                                     contents), zero-strings (zero string
                                     contents only), drop-strings (empty string
                                     contents).
  -v, --verbose                      Log changed field values (primitive fields
                                     only) to stderr.
  -V, --version                      Print version information and exit.
```

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
HprofFilter filter = new HprofFilter(new ZeroPrimitiveTransformer(), null);
filter.filter(
    Path.of("input.hprof"),
    new FileOutputStream("output.hprof")
);
```

### Custom Transformers

Implement `HprofTransformer`:

```java
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
- `target/hprof-redact.jar` - Executable JAR
- `target/hprof-redact` - Native executable (if GraalVM available)

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

## Related Work and Inspiration

- https://github.com/agourlay/hprof-slurp: a heap-dump analyzer written in rust
- https://github.com/eaftan/hprof-parser: written in Java
- [OpenJDK heapDumper.cpp](https://github.com/openjdk/jdk/blob/49e2a6b696c2063f0b4331b0a6d064852d676fcd/src/hotspot/share/services/heapDumper.cpp): the official writer that also includes the format
- https://bugs.openjdk.org/browse/JDK-8337517: Redacted Heap Dumps, but it never got in
- https://eclipse.dev/mat/: Eclipse Memory Analyzer Tool, a powerful heap dump analysis tool

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/hprof-redact/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors