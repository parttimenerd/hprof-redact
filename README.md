# heap-dump-filter

`heap-dump-filter` is a tool for processing Java heap dumps (HPROF format) to redact sensitive data while preserving heap structure and size characteristics. This is useful for:

- Sharing heap dumps for analysis without exposing sensitive string data
- Testing and debugging production issues safely
- Compliance and privacy requirements when handling heap dumps

__This is currently just an early prototype, a proof of concept. Feel free to test it and provide me with feedback.__

## Installation

### As a Standalone JAR

Download the latest release from [GitHub Releases](https://github.com/parttimenerd/heap-dump-filter/releases) and run:

```bash
java -jar hprof-redact.jar -i input.hprof -o output.hprof
```

### Via Maven

Add to your `pom.xml`:

```xml
<dependency>
    <groupId>me.bechberger</groupId>
    <artifactId>hprof-redact</artifactId>
    <version>0.0.0</version>
</dependency>
```

## Usage

### Command Line

```bash
java -jar hprof-redact.jar \
  --input input.hprof \
  --output output.hprof \
  --transformer zero
```

Or short form:

```bash
java -jar hprof-redact.jar -i input.hprof -o output.hprof -t zero
```

### Options

- `-i, --input` (required): Path to input HPROF heap dump file
- `-o, --output` (required): Path to output HPROF file
- `-t, --transformer` (optional, default: `zero`): Transformer to apply

## Transformers

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
import me.bechberger.hprof.HprofFilter;
import me.bechberger.hprof.ZeroPrimitiveTransformer;
import java.nio.file.Path;
import java.io.FileOutputStream;

HprofFilter.filter(
    Path.of("input.hprof"),
    new FileOutputStream("output.hprof"),
    new ZeroPrimitiveTransformer()
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

Use the provided `capture_heap_dumps.py` script to generate test heap dumps:

```bash
python3 capture_heap_dumps.py
```

This script:
- Automatically finds Java test programs in `test_programs/`
- Compiles each test program
- Runs it to generate heap dumps using `jmap`
- Captures histograms for analysis
- Stores results in `heap_dumps/`
- Caches compilation metadata to skip unchanged files

The generated heap dumps are used by the test suite to validate:
- Correct HPROF format parsing
- Data transformation accuracy
- Structure preservation after redaction

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
- https://github.com/openjdk/jdk/blob/49e2a6b696c2063f0b4331b0a6d064852d676fcd/src/hotspot/share/services/heapDumper.cpp: the official writer that also includes the format
- https://bugs.openjdk.org/browse/JDK-8337517: Redacted Heap Dumps, but it never got in

## Support, Feedback, Contributing

This project is open to feature requests/suggestions, bug reports etc.
via [GitHub](https://github.com/parttimenerd/heap-dump-filter/issues) issues.
Contribution and feedback are encouraged and always welcome.

## License

MIT, Copyright 2026 SAP SE or an SAP affiliate company, Johannes Bechberger and contributors
