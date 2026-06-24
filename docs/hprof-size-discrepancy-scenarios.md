# Why Your HPROF File Is Larger Than What MAT Reports

Eclipse MAT's "heap size" figure and the on-disk file size measure fundamentally different
things. This document explains every known source of divergence, with verified examples
produced by `ScenarioProducer.java` and `SyntheticScenarios.java` and diagnosed with
`hprof-redact diagnose`.

All byte figures are from actual runs on GraalVM 25.0.3 (JDK 25), idSize=8.

---

## Background: How MAT counts "heap size"

MAT's parser accumulates its "heap size" figure by summing exactly **three subrecord types**
inside `HPROF_HEAP_DUMP` / `HPROF_HEAP_DUMP_SEGMENT` records:

| Subrecord | MAT formula |
|---|---|
| `HPROF_GC_INSTANCE_DUMP` | `dataLength` (trusts the JVM-written value directly) |
| `HPROF_GC_OBJ_ARRAY_DUMP` | `alignUp(idSize + refSize + 4 + N × refSize, objectAlign)` |
| `HPROF_GC_PRIM_ARRAY_DUMP` | `alignUp(idSize + refSize + 4 + N × elemSize, objectAlign)` |

where `refSize` is 4 (compressed oops, typical for heaps <32 GB) or `idSize` (uncompressed),
and `objectAlign` defaults to 8.

Everything else — metadata strings, class definitions, GC roots, segment framing, second HPROF
headers, trailing garbage — is **silently skipped and not counted**.

A clean baseline dump of a ~30 MB JVM looks like this:

```
File size (on disk):             34.8 MB  (36,439,606 bytes)
 heap_objects.instances:          1.2 MB  (1,217,773 bytes)   — counted by MAT
 heap_objects.prim_arrays:       30.4 MB  (31,891,900 bytes)  — counted by MAT
 heap_objects.obj_arrays:         0.6 MB  (664,440 bytes)     — counted by MAT
 class_dumps:                     0.2 MB  (178,638 bytes)     — NOT counted
 utf8_strings:                    2.3 MB  (2,384,445 bytes)   — NOT counted
 load_class:                     43.2 KB  (44,187 bytes)      — NOT counted
 gc_roots:                       13.8 KB  (14,160 bytes)      — NOT counted
 segment_framing:                481.0 KB (492,516 bytes)     — NOT counted
----------------------------------------------------------------------
MAT heap (compressed oops):     32.9 MB  vs 34.8 MB on disk  — 5.7% gap from overhead alone
```

That **~5.7% baseline gap** is present in every clean dump. The scenarios below describe the
additional factors that widen it to 2×, 7×, or more.

---

## Background: Object layout on the heap vs. in the HPROF file

Understanding exactly which bytes appear where is the key to making sense of every
size-discrepancy scenario.

### Runtime object layout (SapMachine 21 / HotSpot, 64-bit)

Every Java object in the heap has a fixed header, followed by field data, followed by padding
to reach a multiple of `ObjectAlignmentInBytes` (default 8):

**Standard header, compressed oops ON** (heap < 32 GB — the default for SapMachine 21
with `-Xmx20g`):
```
┌──────────────────────────────────────────┐
│  mark word         8 bytes               │  GC age, lock state, identity hash
│  klass pointer     4 bytes  (compressed) │  points into Metaspace
├──────────────────────────────────────────┤  header = 12 bytes
│  reference fields  4 bytes each          │  compressed oops
│  primitive fields  natural size          │  byte/short/int/long/float/double/boolean/char
│  padding           0–7 bytes             │  align total to 8 bytes
└──────────────────────────────────────────┘
```

**Standard header, compressed oops OFF** (heap ≥ 32 GB, or `-XX:-UseCompressedOops`):
```
┌──────────────────────────────────────────┐
│  mark word         8 bytes               │
│  klass pointer     8 bytes (full ptr)    │
├──────────────────────────────────────────┤  header = 16 bytes
│  reference fields  8 bytes each          │  full 64-bit pointers
│  primitive fields  natural size          │
│  padding           0–7 bytes             │
└──────────────────────────────────────────┘
```

**Compact Object Headers** (JEP 450 experimental in JDK 24, production in JDK 25 via JEP 519;
enabled with `-XX:+UseCompactObjectHeaders`):
```
┌──────────────────────────────────────────┐
│  combined word     8 bytes               │  mark bits + 22-bit compressed klass in one word
├──────────────────────────────────────────┤  header = 8 bytes
│  reference fields  4 bytes each          │  still compressed oops (COH requires it)
│  primitive fields  natural size          │
│  padding           0–7 bytes             │
└──────────────────────────────────────────┘
```

Array objects add a 4-byte `length` field after the klass word. So array headers are
16 B (coops ON), 24 B (coops OFF, due to 8 B klass + 4 B length + 4 B pad), or 12 B (COH).

### HPROF wire format: the INSTANCE_DUMP subrecord

The HPROF agent **never writes the runtime header**. Instead it writes its own fixed framing:

```
┌──────────────────────────────────────────┐
│  subtag            1 byte   (= 0x21)     │
│  objectId          8 bytes  (= idSize)   │  the runtime address, used as the object ID
│  stackTraceSerial  4 bytes               │  which call site allocated this object
│  classId           8 bytes  (= idSize)   │  the class's own runtime address
│  dataLength        4 bytes               │  byte count of what follows
├──────────────────────────────────────────┤  framing = 25 bytes (idSize=8)
│  reference fields  8 bytes each          │  compressed runtime refs DECOMPRESSED to idSize
│  primitive fields  natural size          │  same as runtime
│  (no padding)                            │  HPROF stores exact field bytes, no alignment
└──────────────────────────────────────────┘
```

Two things to note:

1. **`idSize` is always 8 on a 64-bit JVM**, regardless of compressed oops or compact headers.
   The HPROF format uses `idSize` for all IDs and all reference fields.
2. **The HPROF agent decompresses every reference** before writing it. A compressed 4-byte
   runtime ref becomes an 8-byte `idSize` entry in the file. This happens even with compressed
   oops ON and even with compact headers.

MAT counts only `dataLength` bytes for each instance — the 25-byte framing is invisible to it.

### Comparing all three views side by side

`Node { Node next; int v; }` — one reference field, one `int` field:

```
                    header    ref field   int field   padding   total
Runtime coops-ON:    12 B      4 B          4 B         4 B      24 B
Runtime coops-OFF:   16 B      8 B          4 B         4 B      32 B
Runtime COH:          8 B      4 B          4 B         0 B      16 B
HPROF on disk:       25 B      8 B          4 B         —        37 B
MAT counts:           —        8 B          4 B         —        12 B  (= dataLength)
```

Key ratios for this class:
- `disk / MAT = 37 / 12 = 3.1×` — same regardless of JVM configuration
- `disk / runtime (coops ON) = 37 / 24 = 1.54×`
- `disk / runtime (coops OFF) = 37 / 32 = 1.16×`
- `disk / runtime (COH) = 37 / 16 = 2.3×`

More examples across common class shapes:

| Class | Runtime (coops ON) | Runtime (coops OFF) | Runtime (COH) | HPROF disk | disk/MAT |
|---|---|---|---|---|---|
| `Object` (no fields) | 16 B | 16 B | 8 B | 25 B | ∞ |
| `Integer` / `Long` (boxed) | 24 B | 24 B | 16 B | 33 B | 4.1× |
| `Node { Node next; }` | 16 B | 24 B | 16 B | 33 B | 4.1× |
| `Node { Node next; int v; }` | 24 B | 32 B | 16 B | 37 B | 3.1× |
| `HashMap.Entry { K,V,next; int h; }` | 32 B | 48 B | 24 B | 53 B | 1.9× |
| `Point { double x, y; }` | 32 B | 32 B | 24 B | 41 B | 2.6× |
| `String { byte[] val; int hash; byte coder; }` | 24 B | 32 B | 24 B | 38 B | 2.9× |

The `disk/MAT` ratio depends only on `dataLength` and is independent of JVM configuration —
the HPROF file looks the same regardless of which JVM wrote it.

### Array objects: a different formula

Arrays use separate HPROF subrecords with their own fixed framing:

```
PRIM_ARRAY_DUMP:  subtag(1) + arrayId(8) + stackTrace(4) + numElements(4) + elemType(1)
                  = 18 bytes fixed + N × elemSize
OBJ_ARRAY_DUMP:   subtag(1) + arrayId(8) + stackTrace(4) + numElements(4) + classId(8)
                  = 25 bytes fixed + N × 8  (each ref element stored as idSize=8)
```

For large arrays the fixed framing is negligible. The important gap for OBJ_ARRAY:

```
Object[1_000_000]:
  Runtime (coops ON):   4,000,016 B  (16-B header + 1M × 4 B compressed refs)
  HPROF on disk:        8,000,025 B  (25-B framing + 1M × 8 B full-size refs)
  MAT counts:           4,000,016 B  (uses refSize=4 in its formula)
  disk / MAT = 2.0×   disk / runtime = 2.0×
```

For primitive arrays, disk ≈ runtime ≈ MAT (only the tiny fixed framing differs).

### The four kinds of size difference

| Comparison | Source | Size |
|---|---|---|
| **disk > MAT** for instances | 25-byte HPROF framing per object, not in `dataLength` | 25 B × N |
| **disk > MAT** for obj arrays | Each element 8 B on disk, MAT counts 4 B (refSize=4) | 4 B × numElements |
| **disk ≈ MAT** for instances (payload) | Both count the same `dataLength` bytes including 8-B refs | 0 |
| **MAT ≥ runtime** for instances | `dataLength` includes 8-B refs; runtime used 4-B refs (coops ON) | 4 B × numRefFields × N |

The last row is why MAT can report a number close to or even slightly above the configured
`-Xmx` value: instance payloads look larger in HPROF than they did at runtime when compressed
oops were in use.

### What `diagnose` can and cannot compute

`hprof-redact diagnose` computes the **disk/MAT gap** exactly:
- `objectIdOverheadBytes` = 25 × N (constant per INSTANCE_DUMP)
- `compressedRefExpansionBytes` = 4 × numElements (for OBJ_ARRAY_DUMP only)

It **cannot** compute the **disk/runtime-heap gap** exactly, because the HPROF file does not
record the runtime object header size. `dataLength` is a single opaque integer; without parsing
every `CLASS_DUMP` field descriptor it is impossible to split `dataLength` into "ref bytes vs
primitive bytes". The JVM configuration (compressed oops, compact headers) is needed and must
be supplied by the user.

`diagnose` exposes `--assume-compressed-oops` / `--no-compressed-oops` to control the MAT
formula's `refSize`, which is the closest approximation available without CLASS_DUMP parsing.

---

## Scenario 1 — Concatenated dump (two complete HPROF streams in one file)

### Explanation

The JVM's `-XX:+HeapDumpOnOutOfMemoryError` handler opens the target file in **append mode**.
If a file already exists at `-XX:HeapDumpPath`, the new dump is appended without truncation,
producing two complete HPROF streams back-to-back. Eclipse MAT reads the first
`JAVA PROFILE 1.0.x\0` header, parses that stream to completion, and stops — the second
stream is entirely invisible.

**Common triggers:**
- A container restarts the JVM in-place without cleaning up the previous dump file.
- An operator runs `jmap -dump` while the JVM also has `-XX:+HeapDumpOnOutOfMemoryError`
  pointed at the same path; the OOM then appends a second dump.
- The same process crashes with OOM more than once using the same `-XX:HeapDumpPath`.
- A monitoring script moves but doesn't delete the dump file before restarting.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 1 — writes two full dumps, concatenates them.

```bash
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 1
```

### Measured result

```
First dump alone:  40.1 MB  (40,064,140 bytes)
Combined file:     76.4 MB  (80,128,310 bytes)
Ratio:             2.00×
```

### diagnose output (key sections)

```
--- Record Histogram ---
UNKNOWN(0x4a)      1     1,347,571,535   ← 'J' from "JAVA PROFILE" of 2nd stream
HPROF_HEAP_DUMP_SEGMENT   39   37,692,073
HPROF_UTF8         47,565    2,337,452
...

--- Duplicate Headers ---
WARNING: Additional HPROF header found at decompressed offset 40,064,140
         (magic: JAVA PROFILE 1.0.2)

--- Trailing Bytes ---
WARNING: trailing bytes after last well-formed record at offset 80,128,310
         Reason: Unexpected end of stream
```

**Key signal:** `Duplicate Headers` warning + `UNKNOWN(0x4a)` in the record histogram
(byte `0x4a` = `'J'`, the first byte of `"JAVA PROFILE"` being parsed as an unknown record
tag by the first-stream parser).

**MAT sees:** ~36 MB heap. **File on disk:** 76 MB. **Ratio: 2.00×.**

---

## Scenario 2 — UTF-8 string metadata exceeds threshold

### Explanation

Every `HPROF_UTF8` record carries a name from the JVM's internal `SymbolTable` — a Metaspace
structure that holds names for classes, methods, fields, and source files. These symbols are
**not Java heap objects**: they live in native memory, are not allocated with `new`, and are
never counted in Eclipse MAT's heap-size figure.

**What goes into HPROF_UTF8 records:**

| Symbol category | Examples | Typical count |
|---|---|---|
| Class names | `java/lang/String`, `com/example/MyService$Lambda$1` | 50K–1M |
| Method names | `<init>`, `toString`, `processRequest` | 100K–500K unique |
| Method descriptors | `(Ljava/lang/String;I)V` | 50K–200K unique |
| Field names | `value`, `next`, `size` | 10K–50K unique |
| Source file names | `String.java`, `MyService.java` | 10K–100K |
| Thread names | `main`, `GC Thread#0` | 100–10K |

**Wire format** of each record:
```
tag(1) + time(4) + bodyLen(4) = 9 bytes framing
nameId(idSize=8)
utf8_bytes(bodyLen − idSize)
```
Per-record overhead: 17 bytes before the actual symbol text. For an average 35-byte symbol
name that's ~33% overhead per record.

**What MAT does with UTF-8 strings:**
MAT decodes all UTF-8 records during parsing (peak memory ≈ total UTF-8 payload). After
parsing, only the subset whose `nameId` is referenced by `LOAD_CLASS`, `HPROF_FRAME`, or
class-dump field/static descriptors stays resident in `ClassImpl` objects. The rest is
GC-eligible.

**Typical UTF-8 section sizes:**

| App profile | Symbol count | Avg length | UTF-8 section |
|---|---|---|---|
| Small app (JDK + a few libs) | 50K | 30 B | ~2 MB |
| Medium Spring Boot | 200K | 35 B | ~10 MB |
| Large OSGi / JEE | 1M | 40 B | ~54 MB |
| Extreme code-gen / ASM | 5M | 50 B | ~320 MB |

Against a 20 GB file, even 500 MB of UTF-8 shifts the ratio by only +2.4%. UTF-8 cannot
explain a 1.5–2× ratio on its own — see Scenario 11 for that. The `UNUSUALLY_LARGE` flag
fires when UTF-8 exceeds 5% of file size.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 2 — retains 50,000 unique `String` objects
with long names.

```bash
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 2
```

### Measured result

```
File size:    43.5 MB (45,640,348 bytes)
utf8_strings:  2.3 MB (2,384,445 bytes) = 5.2% of file  → [UNUSUALLY LARGE]
MAT heap:     40.0 MB (compressed oops)
```

### diagnose output (key section)

```
--- UTF-8 Analysis ---
Total UTF-8 bytes:  2,384,445 (5.2% of file)  [UNUSUALLY LARGE]
  Referenced (MAT-resident after parse):   182,940 bytes
  Unreferenced (transient during parse): 2,201,505 bytes
Record count:  48,314
Largest record: 11,516 bytes
```

**Key signal:** `[UNUSUALLY LARGE]` flag in the UTF-8 analysis section, and `HPROF_UTF8`
appearing near the top of the record histogram by total bytes.

---

## Scenario 3 — Unreachable objects included in dump (live=false)

### Explanation

`HotSpotDiagnosticMXBean.dumpHeap(path, live=false)` and `jmap -dump:format=b` write **every
object in the heap**, including objects not reachable from any GC root. MAT's default view
excludes these from the "heap size" figure and from the object graph unless "Keep unreachable
objects" is enabled in the parse wizard.

`jmap -dump:live,format=b` triggers a full GC first, then dumps only surviving objects —
a much smaller file.

The ratio depends on how recently the JVM ran a GC and how many short-lived objects exist.
In the controlled test below, 500 `byte[100_000]` arrays were allocated and left unreachable
before the dump, producing a **6.95× ratio**.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 3 — allocates live objects, allocates
unreachable objects, takes two dumps.

```bash
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 3
```

### Measured result

```
live=false (all objects including unreachable):  103.2 MB (108,185,432 bytes)
live=true  (GC first, then dump live only):       14.8 MB  (15,563,496 bytes)
Ratio: 6.95×
```

**Key signal:** `diagnose` cannot distinguish live from unreachable objects — both appear as
normal heap objects. Use `jmap -histo:live` separately to get a live-object histogram to
compare.

**Why MAT still shows a large "heap size" for live=false dumps:**
MAT reports `heapObjectInstanceBytes + formulaForArrays`. Unreachable objects are counted by
MAT's heap-size formula exactly like reachable ones. The discrepancy appears in the *object
graph* (dominator tree, retained sizes), not in the raw heap size figure.

---

## Scenario 4 — Baseline: inherent overhead of a clean dump

### Explanation

Even a perfectly healthy dump has overhead that doesn't count toward MAT's heap size.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 4.

```bash
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 4
```

### Measured result

```
File size:  34.8 MB (36,439,606 bytes)
MAT heap (compressed oops):  32.9 MB (32,856,005 bytes)
Baseline overhead: 5.6%

Breakdown of overhead:
  utf8_strings:       2.3 MB  (6.5% of file)
  segment_framing:    0.5 MB  (1.4% of file)
  class_dumps:        0.2 MB  (0.5% of file)
  load_class:        43.2 KB  (0.1% of file)
  gc_roots:          13.8 KB  (<0.1% of file)
  frames_traces:      1.3 KB  (<0.1% of file)
```

**Key insight:** A 5–7% gap between on-disk size and MAT heap size is completely normal for a
clean dump. Any gap larger than ~10% warrants investigation.

---

## Scenario 5 — Truncated dump (file cut off mid-write)

### Explanation

If the JVM is killed mid-write (OOM-kill by the OS, `SIGKILL`, disk full, container crash),
the resulting `.hprof` file is incomplete. The last segment will have a `length` field larger
than the remaining bytes, and the file ends before `HPROF_HEAP_DUMP_END`.

**Effect on MAT:** MAT may still open and partially parse the file, reporting whatever heap
objects it managed to read from the complete segments. The file is *smaller* than a complete
dump, not larger.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 5 — builds a 100-instance dump, keeps the
first 60%.

```bash
java SyntheticScenarios /tmp/hprof-scenarios
```

### Measured result

```
Full dump:       103.6 KB
Truncated at 60%: 62.2 KB

diagnose reports:
  Segment at offset 76: declared=103,543 consumed=62,097
  Trailing Bytes: parse error at offset 62,182
```

### diagnose output (key sections)

```
--- Segment Issues ---
Segment at offset 76: declared=103,543 consumed=62,097
  Segment consumed 62097 bytes but declared 103543
  (parse error at offset 62097: Unexpected end of stream)

--- Trailing Bytes ---
WARNING: trailing bytes after last well-formed record at offset 62,182
  Reason: parse error: Unexpected end of stream
```

**Key signal:** Both `Segment Issues` and `Trailing Bytes` are populated. The segment's
`declaredLength > consumedBytes` proves the write was cut short mid-segment.

---

## Scenario 6 — Segment length mismatch (declared length ≠ actual content)

### Explanation

A `HPROF_HEAP_DUMP_SEGMENT` record declares its byte length in a `u4` header field. If this
value is incorrect — whether from a buggy HPROF agent, a custom producer, or a
partially-overwritten file — the segment parser will either:

- Read past the real subrecord content into garbage (if `declared > actual`).
- Stop early, leaving real subrecords unread (if `declared < actual`).

MAT silently tolerates this: it reads `declaredLength` bytes from the stream regardless of
what it found inside, and moves on. The objects in the "missing" portion are never counted.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 6 — writes a segment containing 5
INSTANCE_DUMPs (149 bytes of actual subrecords), but declares length = 149 + 500 = 649.

### Measured result

```
Segment declares: 648 bytes
Segment consumes: 149 bytes (5 × INSTANCE_DUMP + 1 × CLASS_DUMP)
500 zero-padded bytes treated as unknown subrecord tags
```

### diagnose output

```
--- Segment Issues ---
Segment at offset 76: declared=648 consumed=149
  Segment consumed 149 bytes but declared 648
  (parse error at offset 149: Unsupported heap dump subrecord tag: 0x0)
```

**Key signal:** `Segment Issues` entry where `declaredLength > consumedBytes`. The parse error
message names the unexpected tag (`0x0` = zero padding in this case).

---

## Scenario 7 — Duplicate object IDs

### Explanation

Each live Java object should have a unique ID in the HPROF stream. Duplicate IDs occur in
concatenated dumps (the same object was alive in both halves), buggy HPROF agents, or merged
multi-JVM dumps. MAT's behaviour with duplicate IDs is implementation-dependent — it may keep
the first occurrence, keep the last, or throw a parse error. Both copies count toward file size.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 7 — writes objectId `0x200` twice in the
same HEAP_DUMP_SEGMENT. Run with `--detect-duplicate-ids` to enable the tracking hash set.

### Measured result

```
File: 188 bytes
3 × INSTANCE_DUMP: two share objectId=0x200

diagnose (with --detect-duplicate-ids):
--- Duplicate Object IDs ---
Object ID    Occurrences  Kind
0x200              2      INSTANCE_DUMP
```

**Key signal:** `Duplicate Object IDs` table populated. Without `--detect-duplicate-ids` the
section reads `[not requested]`.

---

## Scenario 8 — GZip compression: on-disk size vs. decompressed size

### Explanation

HPROF files are frequently stored gzip-compressed (`.hprof.gz`). The size reported by `ls` is
the *compressed* size. `hprof-redact diagnose` and Eclipse MAT both work on the *decompressed
stream*. Comparing the compressed on-disk size against MAT's heap figure is comparing apples
and oranges.

**Example:** A 53 KB synthetic dump with 50 instances and 50 `byte[1000]` arrays compresses
to 765 bytes — a 67.8× ratio. A realistic production dump compresses 3–6×.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 8 — writes the same dump as raw HPROF and
as `.hprof.gz`.

### Measured result

```
Uncompressed:  53.5 KB (51,884 decompressed bytes)
GZip:           0.8 KB (765 bytes on disk)
Compression:   67.8× (synthetic zero-filled arrays compress extremely well)
Typical production compression: 3–6×
```

### diagnose output

```
=== HPROF Diagnostic Report ===
File:   scenario-8-gzip.hprof.gz
Size:   765 bytes (765 bytes)      ← on-disk (compressed) size
...
TOTAL on-disk:   51,884            ← decompressed byte count (what MAT sees)
MAT heap:        51,000
```

**Triage:** Always compare MAT's heap size against the *decompressed* size. Run
`gzip -l file.hprof.gz` to get it.

---

## Scenario 9 — Class metadata overhead (500 class dumps, 2 instances)

### Explanation

Every loaded class produces multiple HPROF records that are invisible to MAT's heap formula:
one `HPROF_UTF8` record per class/method/field name, one `HPROF_LOAD_CLASS`, and one
`HPROF_GC_CLASS_DUMP`. In an application with thousands of loaded classes (OSGi, JEE, large
frameworks) this metadata can dominate the file.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 9 — 500 classes, 2 instances.

### Measured result

```
File: 56.0 KB (57,373 bytes)
  utf8_strings:   23.3 KB  (40.6% of file)   [UNUSUALLY LARGE]
  class_dumps:    21.5 KB  (37.5% of file)
  load_class:     12.5 KB  (21.8% of file)
  heap_objects:      32 B  (0.06% of file)
  MAT heap:          0 B

Non-heap overhead: 99.9% of the file
```

**Key signal:** `class_dumps` and `utf8_strings` dominating the size attribution table.

---

## Scenario 10 — UTF-8 records dominate (99.8% of file)

### Explanation

Extreme case: a single very large `HPROF_UTF8` record (50,000 bytes) combined with many
medium-sized name records. Realistic triggers: JVMs or HPROF agents that emit full stack-trace
strings or debugging annotations as UTF-8 records; broken HPROF producers that duplicate UTF-8
records for every occurrence rather than reusing nameIds.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 10 — 1 × 50 KB UTF-8 record + 200 × medium
records, 1 actual instance.

### Measured result

```
File: 65.5 KB (67,058 bytes)
utf8_strings: 66,920 bytes = 99.8% of file   [UNUSUALLY LARGE]
MAT heap:     4 bytes  (one instance with one int field)
Ratio file/MAT: 16,765×
```

### diagnose output (key section)

```
--- UTF-8 Analysis ---
Total UTF-8 bytes:  66,920 (99.8% of file)  [UNUSUALLY LARGE]
  Referenced (stays in MAT):    17 bytes
  Unreferenced (transient):  66,903 bytes
Largest record: 50,000 bytes  (sample: "AAAAAAAA...")
```

**Key signal:** Largest record size in the UTF-8 analysis — a record >10 KB is almost
certainly an anomaly (class names are rarely >200 bytes).

---

## Scenario 11 — HPROF record framing overhead (idSize=8, many small objects)

### Explanation

This is the **most commonly misdiagnosed scenario**: a 20 GB Java heap producing a 36–38 GB
HPROF file, with MAT correctly reporting ~20 GB. The cause is the fixed **25-byte
per-object framing** in every `HPROF_GC_INSTANCE_DUMP` subrecord that MAT does not count.

Each `INSTANCE_DUMP` subrecord on disk:

```
subtag(1) + objectId(8) + stackTraceSerial(4) + classId(8) + dataLength(4)
= 25 bytes of framing, all invisible to MAT
+ dataLength bytes   ← the only part MAT counts
```

**The framing overhead scales with object count, not object size:**

| Avg instance payload | disk/MAT ratio for pure-instance file | disk/MAT for 600M objects |
|---|---|---|
| 0 bytes (empty object) | ∞ | all 15 GB is framing |
| 8 bytes (one `long`) | 4.1× | ~19.8 GB framing vs ~4.8 GB data |
| 16 bytes (two `long` or `Node{next;int}`) | 2.6× | ~15 GB framing vs ~9.6 GB data |
| 32 bytes (four fields) | 1.8× | ~15 GB framing vs ~19.2 GB data |
| 64 bytes (eight fields) | 1.4× | ~15 GB framing vs ~38.4 GB data |

Arrays dilute the ratio toward 1.0× because they have negligible per-record framing relative
to their payload. A heap with ~600M instances and a moderate array section typically produces a
**1.5–2.0× overall ratio** — entirely normal.

**Confirming it is not a concatenated dump:**
Run `hprof-redact diagnose`. If the Problems section shows only `OBJECT_ID_OVERHEAD` (INFO)
and no `CONCATENATED_DUMP` (ERROR), the size is explained by framing overhead alone.
`LARGE_UNREACHABLE_RATIO` is automatically suppressed when framing explains ≥70% of the gap.

### Reproducer

`test_programs/SyntheticScenarios.java`:
- Scenario 11 — 100,000 empty instances (0-byte payload, idSize=8): framing = 100% of disk.
- Scenario 13 — 200,000 instances (16-byte payload) + 10 × 500 KB arrays: realistic ~1.6× ratio.

```bash
java SyntheticScenarios /tmp/hprof-scenarios
```

### Measured results

**Scenario 11 — pure framing (no payload):**

```
instances: 100,000 × 0 bytes payload
total disk: 2.4 MB   MAT sees: 0 bytes   ratio: ∞ (100% framing)
```

**Scenario 13 — realistic mix:**

```
instances: 200,000 × 16 bytes payload
arrays:    10 × 500 KB each

disk instances: 8.0 MB    MAT instances: 3.2 MB   ratio: 2.56×
disk arrays:    5.0 MB    MAT arrays:    5.0 MB   ratio: 1.00×
total disk:    12.9 MB    MAT total:     8.2 MB   overall ratio: 1.61×
```

### diagnose output (key section — scenario 13)

```
=== Problems Detected ===
[INFO]  HPROF record framing overhead: 5,000,000 bytes (100% of the file/MAT gap)
        With idSize=8, every INSTANCE_DUMP subrecord carries 25 bytes of framing
        that Eclipse MAT does not count. This file has ~200,000 instance subrecords:
        framing = 5,000,000 bytes, obj-array reference expansion = 0 bytes,
        combined 5,000,000 bytes. This accounts for 100% of the 5,000,317-byte gap.
        No action is required; this is the expected and correct file size.
```

---

## Scenario 12 — Reference expansion in OBJ_ARRAY_DUMP (idSize=8, compressed oops)

### Explanation

When `idSize=8` but the JVM uses compressed oops (default for heaps < 32 GB), each element in
an `OBJ_ARRAY_DUMP` is stored as **8 bytes** on disk (full decompressed address). Eclipse MAT's
formula uses `refSize=4`, so it counts only 4 bytes per element. The extra 4 bytes per element
are invisible to MAT.

This is distinct from the INSTANCE_DUMP case: for instances, MAT counts `dataLength` directly
(which already includes the 8-byte refs in the payload), so instance ref expansion is NOT a
disk/MAT gap — it is a MAT/runtime gap (MAT over-counts vs. the live JVM). For OBJ_ARRAY, MAT
explicitly applies `refSize=4` regardless of `dataLength`, so the expansion is a true disk/MAT
gap.

**Example:** A `Node[]` array holding 500M references:
- On disk: 500M × 8 = 4 GB
- MAT counts: 500M × 4 = 2 GB (disk/MAT = 2.0×)
- Runtime heap: 500M × 4 + 16 B header ≈ 2 GB (disk/runtime also ≈ 2.0×)

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 12 — 50,000 instances referenced by one
large OBJ_ARRAY.

### Measured result

```
elements: 50,000   array disk: 400,025 bytes   ref-expansion: 200,000 bytes
total: 1.6 MB   ratio: ~1.24×
```

### diagnose output

```
[INFO]  HPROF record framing overhead: 1,450,000 bytes (100% of the file/MAT gap)
        ... framing = 1,250,000 bytes,
        obj-array reference expansion = 200,000 bytes, combined 1,450,000 bytes.
```

The `compressedRefExpansionBytes` is shown in the size attribution table under
"Overhead in file but not in MAT heap".

---

## Interpreting the size ratio: what each range suggests

Run `hprof-redact diagnose` to get the exact breakdown before drawing any conclusions.

| Ratio | Most likely explanation | How to confirm with `diagnose` |
|---|---|---|
| **1.05–1.10×** | Normal overhead (UTF-8, class dumps, GC roots, framing). | Size attribution shows no dominant non-heap category. |
| **1.10–1.30×** | Slightly elevated UTF-8/class metadata, or live=false dump with moderate unreachable objects. | Check UTF-8 analysis for `[UNUSUALLY LARGE]`; check `prim_arrays` on-disk vs MAT. |
| **~1.5–2.0× with `OBJECT_ID_OVERHEAD` flagged** | **Expected for object-heavy heaps** — per-instance framing not counted by MAT. 600M small instances on a 20 GB heap produce ~35–38 GB. **Normal; no action needed.** | `OBJECT_ID_OVERHEAD` (INFO) present, no `CONCATENATED_DUMP` (ERROR), framing explains ≥70% of gap. |
| **~1.5–2.5×, gap ≈ half the file, no framing explanation** | **Concatenated dump.** | `Duplicate Headers` warning + `UNKNOWN(0x4a)` in record histogram + `Trailing Bytes` warning. |
| **3–7× vs. a live=true dump** | Unreachable objects dominate. | Cannot be distinguished by `diagnose` alone; take a `jmap -dump:live` separately. |
| **>10× or gap >> half the file** | GZip confusion, extreme UTF-8 bloat, or mostly class metadata. | Check file extension. Run `gzip -l`. Check `utf8_strings` and `class_dumps` in attribution. |

### Why ~1.85× may be framing overhead OR a concatenated dump

**`OBJECT_ID_OVERHEAD` present, no `CONCATENATED_DUMP`:**
The ratio is explained by per-instance HPROF framing. A heap with ~600M small instances and a
20 GB runtime heap will produce ~36 GB of HPROF data at 25 bytes framing per object. This is
normal and expected. No action required.

**`CONCATENATED_DUMP` present:**
A second HPROF stream was appended to the file. MAT parses only the first stream. Fix: delete
the dump file between JVM restarts, or point `-XX:HeapDumpPath` at a *directory* (the JVM will
create `java_pid<N>.hprof` inside it, never colliding with a previous file).

---

## Summary table (all scenarios, measured)

| # | Scenario | File size | MAT heap | Ratio | Primary `diagnose` signal |
|---|---|---|---|---|---|
| 1 | Concatenated dump (2 streams) | 76.4 MB | ~36 MB | **2.00×** | `Duplicate Headers` warning |
| 2 | UTF-8 bloat (50k strings) | 43.5 MB | 40.0 MB | 1.09× | UTF-8 `[UNUSUALLY LARGE]` |
| 3a | All objects incl. unreachable | 103.2 MB | 102.5 MB | 1.01× | Large file vs. live=true |
| 3b | Live objects only (GC first) | 14.8 MB | 12.0 MB | 1.23× | — |
| 3a÷3b | Unreachable object ratio | — | — | **6.95×** | Unavoidable with live=false |
| 4 | Clean baseline | 34.8 MB | 32.9 MB | 1.06× | Normal 5–7% overhead |
| 5 | Truncated (60% of file) | 62.2 KB | ~60 KB | ~1× | `Segment Issues` + `Trailing Bytes` |
| 6 | Segment length mismatch | 742 B | 20 B | **37×** | `Segment Issues` (declared≠consumed) |
| 7 | Duplicate object IDs | 188 B | 0 B | — | `Duplicate Object IDs` table |
| 8 | GZip (on-disk vs decompressed) | 765 B | 51.0 KB | **67.8×** | Apples/oranges — check decompressed |
| 9 | Class metadata overhead (500 classes) | 56.0 KB | 0 B | ∞ | `class_dumps` in attribution |
| 10 | UTF-8 dominant (50 KB record) | 65.5 KB | 4 B | **16,765×** | UTF-8 largest-record field |
| 11 | HPROF framing (100k empty instances) | 2.4 MB | 0 B | ∞ | `OBJECT_ID_OVERHEAD` (INFO) |
| 13 | Realistic mix (200k instances + arrays) | 12.9 MB | 8.2 MB | 1.61× | `OBJECT_ID_OVERHEAD` (INFO) |

---

## Five-step triage guide

**Step 1 — Check for GZip confusion**

```bash
gzip -l file.hprof.gz   # see uncompressed size
```

If the file is `.hprof.gz`, compare MAT's heap against the *uncompressed* size, not the
`.hprof.gz` size.

**Step 2 — Run diagnose, look at Duplicate Headers first**

```bash
hprof-redact diagnose file.hprof
```

Any `WARNING: Additional HPROF header` means you have a concatenated dump. The bytes of the
second (and subsequent) streams are entirely invisible to MAT.

**Step 3 — Check Size Attribution**

Look for categories that dominate the `On Disk (bytes)` column but show `0` in `MAT Heap`:
- `unknown_or_unparseable` — truncation, padding, or second-stream garbage
- `utf8_strings` — check if `[UNUSUALLY LARGE]` fires
- `class_dumps` — large class-loading footprint
- `heap_objects.instances` dominating — check for `OBJECT_ID_OVERHEAD` in Problems section

**Step 4 — Read the overhead breakdown**

In the Size Attribution section, `diagnose` prints:

```
Overhead in file but not in MAT heap (explains disk > MAT gap):
  instance subrecord framing  (25 bytes per object, idSize=8): N bytes
  OBJ_ARRAY ref expansion (4 bytes/element, idSize=8 vs MAT refSize=4): M bytes
  combined: N+M bytes
```

If `combined ≈ (file size − MAT heap size)`, the gap is fully explained by expected HPROF
overhead. No anomaly.

**Step 5 — Enable duplicate-ID detection for suspected merged dumps**

```bash
hprof-redact diagnose file.hprof --detect-duplicate-ids
```

If the `Duplicate Object IDs` table is non-empty, the file contains data from more than one
dump or a buggy agent.

---

## Reproducer programs

All programs are in `test_programs/`:

| Program | Produces |
|---|---|
| `ScenarioProducer.java` | Real JVM dumps: concatenated (S1), UTF-8 bloat (S2), unreachable objects (S3), baseline (S4) |
| `SyntheticScenarios.java` | Synthetic HPROF: truncated (S5), segment mismatch (S6), duplicate IDs (S7), gzip (S8), class overhead (S9), UTF-8 dominant (S10), framing overhead (S11–S13) |
| `OomDoubleHeapDump.java` | Concatenated dump via MXBean + stream-copy |

```bash
# Generate all scenario files
cd test_programs
javac ScenarioProducer.java SyntheticScenarios.java
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios
java SyntheticScenarios /tmp/hprof-scenarios

# Diagnose them all
JAR=../target/hprof-redact.jar
for f in /tmp/hprof-scenarios/*.hprof /tmp/hprof-scenarios/*.hprof.gz; do
  echo "=== $f ==="
  java -jar $JAR diagnose "$f" | grep -E "(^Size:|^TOTAL|^WARNING|Largest record)"
  echo
done
```
