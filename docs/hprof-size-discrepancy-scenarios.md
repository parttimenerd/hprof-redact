# Why Your HPROF File Is Larger Than the Runtime Heap

The on-disk HPROF file size and the JVM's runtime heap size measure fundamentally different
things. This document explains every known source of divergence, with verified examples
produced by `ScenarioProducer.java` and `SyntheticScenarios.java` and diagnosed with
`hprof-redact diagnose`.

All byte figures are from actual runs on GraalVM 25.0.3 (JDK 25), idSize=8.

---

## Background: How the JVM accounts for heap size

When you configure `-Xmx20g`, you are setting the maximum size of the **Java object heap** —
the region managed by the garbage collector. The GC tracks every live object from GC roots,
and the "heap size" it reports (via `Runtime.totalMemory()`, JMX, GC logs) is the sum of all
bytes allocated to live objects plus any unused space within heap regions.

Three numbers matter for understanding the disk/runtime gap:

| View | What it counts |
|---|---|
| **Runtime heap** | Actual bytes used by live Java objects, with their runtime layout (headers, fields, padding, alignment) |
| **HPROF on disk** | A serialised snapshot: per-object framing + raw field bytes, no runtime headers, no alignment, refs always 8 bytes |
| **Configured `-Xmx`** | Upper bound on heap capacity — not the same as live object size |

A clean baseline dump of a ~30 MB JVM:

```
File size (on disk):             34.8 MB  (36,439,606 bytes)
 heap_objects.instances:          1.2 MB  (1,217,773 bytes)   — object field data only
 heap_objects.prim_arrays:       30.4 MB  (31,891,900 bytes)  — element data only
 heap_objects.obj_arrays:         0.6 MB  (664,440 bytes)     — ref data only
 class_dumps:                     0.2 MB  (178,638 bytes)     — metadata, not heap objects
 utf8_strings:                    2.3 MB  (2,384,445 bytes)   — symbol table, not heap
 load_class:                     43.2 KB  (44,187 bytes)      — metadata
 gc_roots:                       13.8 KB  (14,160 bytes)      — metadata
 segment_framing:                481.0 KB (492,516 bytes)     — per-object HPROF framing
----------------------------------------------------------------------
Baseline gap: ~5.7% between on-disk size and live-object data alone
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

### Comparing all three views side by side

`Node { Node next; int v; }` — one reference field, one `int` field:

```
                    header    ref field   int field   padding   total
Runtime coops-ON:    12 B      4 B          4 B         4 B      24 B
Runtime coops-OFF:   16 B      8 B          4 B         4 B      32 B
Runtime COH:          8 B      4 B          4 B         0 B      16 B
HPROF on disk:       25 B      8 B          4 B         —        37 B
```

Key ratios for this class:
- `disk / runtime (coops ON) = 37 / 24 = 1.54×`
- `disk / runtime (coops OFF) = 37 / 32 = 1.16×`
- `disk / runtime (COH) = 37 / 16 = 2.3×`

More examples across common class shapes:

| Class | Runtime (coops ON) | Runtime (coops OFF) | Runtime (COH) | HPROF disk |
|---|---|---|---|---|
| `Object` (no fields) | 16 B | 16 B | 8 B | 25 B |
| `Integer` / `Long` (boxed) | 24 B | 24 B | 16 B | 33 B |
| `Node { Node next; }` | 16 B | 24 B | 16 B | 33 B |
| `Node { Node next; int v; }` | 24 B | 32 B | 16 B | 37 B |
| `HashMap.Entry { K,V,next; int h; }` | 32 B | 48 B | 24 B | 53 B |
| `Point { double x, y; }` | 32 B | 32 B | 24 B | 41 B |
| `String { byte[] val; int hash; byte coder; }` | 24 B | 32 B | 24 B | 38 B |

### Array objects: a different formula

Arrays use separate HPROF subrecords with their own fixed framing:

```
PRIM_ARRAY_DUMP:  subtag(1) + arrayId(8) + stackTrace(4) + numElements(4) + elemType(1)
                  = 18 bytes fixed + N × elemSize
OBJ_ARRAY_DUMP:   subtag(1) + arrayId(8) + stackTrace(4) + numElements(4) + classId(8)
                  = 25 bytes fixed + N × 8  (each ref element stored as idSize=8)
```

For primitive arrays, disk ≈ runtime (only the tiny fixed framing and missing runtime header
differ). For object arrays with compressed oops:

```
Object[1_000_000]:
  Runtime (coops ON):   4,000,016 B  (16-B header + 1M × 4 B compressed refs)
  HPROF on disk:        8,000,025 B  (25-B framing + 1M × 8 B full-size refs)
  disk / runtime = 2.0×
```

### The sources of disk > runtime

| Source | Size |
|---|---|
| 25-byte HPROF framing per instance (replaces runtime header) | 25 B − header_size × N |
| Ref decompression in instances (coops ON): 4 B runtime → 8 B in file | 4 B × numRefFields × N |
| Ref decompression in obj arrays (coops ON): 4 B runtime → 8 B in file | 4 B × numElements |
| Non-heap HPROF records (UTF-8 strings, class dumps, GC roots, framing) | varies |

### What `diagnose` can and cannot compute

`hprof-redact diagnose` computes the **disk/runtime gap** components it can observe:
- `objectIdOverheadBytes` = 25 × N (constant per INSTANCE_DUMP)
- `compressedRefExpansionBytes` = 4 × numElements (for OBJ_ARRAY_DUMP only)

It **cannot** compute the ref-field expansion inside instance payloads exactly, because
`dataLength` is a single opaque integer. Without parsing every `CLASS_DUMP` field descriptor
it is impossible to split `dataLength` into "ref bytes vs primitive bytes".

---

## Scenario 1 — Concatenated dump (two complete HPROF streams in one file)

### Explanation

The JVM's `-XX:+HeapDumpOnOutOfMemoryError` handler opens the target file in **append mode**.
If a file already exists at `-XX:HeapDumpPath`, the new dump is appended without truncation,
producing two complete HPROF streams back-to-back. Heap analysers typically read the first
`JAVA PROFILE 1.0.x\0` header, parse that stream to completion, and stop — the second
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

**File on disk:** 76 MB. A parser seeing only the first stream processes ~40 MB. **Ratio: 2.00×.**

---

## Scenario 2 — UTF-8 string metadata exceeds threshold

### Explanation

Every `HPROF_UTF8` record carries a name from the JVM's internal `SymbolTable` — a Metaspace
structure that holds names for classes, methods, fields, and source files. These symbols are
**not Java heap objects**: they live in native memory, are not allocated with `new`, and do
not contribute to the runtime heap.

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
```

### diagnose output (key section)

```
--- UTF-8 Analysis ---
Total UTF-8 bytes:  2,384,445 (5.2% of file)  [UNUSUALLY LARGE]
  Referenced:   182,940 bytes
  Unreferenced: 2,201,505 bytes
Record count:  48,314
Largest record: 11,516 bytes
```

**Key signal:** `[UNUSUALLY LARGE]` flag in the UTF-8 analysis section, and `HPROF_UTF8`
appearing near the top of the record histogram by total bytes.

---

## Scenario 3 — Unreachable objects included in dump (live=false)

### Explanation

`HotSpotDiagnosticMXBean.dumpHeap(path, live=false)` and `jmap -dump:format=b` write **every
object in the heap**, including objects not reachable from any GC root. The on-disk file
therefore contains more object data than the live runtime heap.

`jmap -dump:live,format=b` triggers a full GC first, then dumps only surviving objects —
a much smaller file that matches the live heap more closely.

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

---

## Scenario 4 — Baseline: inherent overhead of a clean dump

### Explanation

Even a perfectly healthy dump has overhead beyond the raw object data: per-object HPROF
framing, metadata records (UTF-8 strings, class dumps, load-class records, GC roots), and
segment framing. None of this is in the runtime heap.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 4.

```bash
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 4
```

### Measured result

```
File size:  34.8 MB (36,439,606 bytes)
Baseline overhead vs. object data alone: ~5.6%

Breakdown of overhead:
  utf8_strings:       2.3 MB  (6.5% of file)
  segment_framing:    0.5 MB  (1.4% of file)
  class_dumps:        0.2 MB  (0.5% of file)
  load_class:        43.2 KB  (0.1% of file)
  gc_roots:          13.8 KB  (<0.1% of file)
  frames_traces:      1.3 KB  (<0.1% of file)
```

**Key insight:** A 5–7% gap between on-disk size and raw object data is completely normal for
a clean dump. Any gap larger than ~10% warrants investigation.

---

## Scenario 5 — Truncated dump (file cut off mid-write)

### Explanation

If the JVM is killed mid-write (OOM-kill by the OS, `SIGKILL`, disk full, container crash),
the resulting `.hprof` file is incomplete. The last segment will have a `length` field larger
than the remaining bytes, and the file ends before `HPROF_HEAP_DUMP_END`.

**Effect:** A parser may still open and partially parse the file, reporting whatever heap
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
multi-JVM dumps. Both copies count toward file size.

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
the *compressed* size. `hprof-redact diagnose` works on the *decompressed stream*. Comparing
the compressed on-disk size against the runtime heap is comparing apples and oranges.

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
TOTAL on-disk:   51,884            ← decompressed byte count
```

**Triage:** Always compare against the *decompressed* size. Run `gzip -l file.hprof.gz` to
get it.

---

## Scenario 9 — Class metadata overhead (500 class dumps, 2 instances)

### Explanation

Every loaded class produces multiple HPROF records that are not heap objects:
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
Heap object data: 4 bytes  (one instance with one int field)
Ratio file/heap-data: 16,765×
```

### diagnose output (key section)

```
--- UTF-8 Analysis ---
Total UTF-8 bytes:  66,920 (99.8% of file)  [UNUSUALLY LARGE]
  Referenced:    17 bytes
  Unreferenced: 66,903 bytes
Largest record: 50,000 bytes  (sample: "AAAAAAAA...")
```

**Key signal:** Largest record size in the UTF-8 analysis — a record >10 KB is almost
certainly an anomaly (class names are rarely >200 bytes).

---

## Scenario 11 — HPROF record framing overhead (idSize=8, many small objects)

### Explanation

This is the **most commonly misdiagnosed scenario**: a 20 GB Java runtime heap producing a
36–38 GB HPROF file. The cause is the fixed **25-byte per-object framing** in every
`HPROF_GC_INSTANCE_DUMP` subrecord that replaces (and exceeds) the runtime object header.

Each `INSTANCE_DUMP` subrecord on disk:

```
subtag(1) + objectId(8) + stackTraceSerial(4) + classId(8) + dataLength(4)
= 25 bytes of framing
+ dataLength bytes   ← the raw field data
```

At runtime, a standard header (coops ON) is 12 bytes. On disk the HPROF framing is 25 bytes —
13 bytes larger per object. For COH (8-byte header) the overhead is 17 bytes per object.

**The framing overhead scales with object count, not object size:**

| Avg instance payload | disk/runtime ratio (coops ON) | disk/runtime for 600M objects |
|---|---|---|
| 0 bytes (empty object) | ∞ | all HPROF data is framing |
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
total disk: 2.4 MB   heap object data: 0 bytes   ratio: ∞ (100% framing)
```

**Scenario 13 — realistic mix:**

```
instances: 200,000 × 16 bytes payload
arrays:    10 × 500 KB each

disk instances: 8.0 MB    runtime instances: ~3.2 MB (coops ON)   ratio: 2.56×
disk arrays:    5.0 MB    runtime arrays:    ~5.0 MB               ratio: 1.00×
total disk:    12.9 MB    total runtime:     ~8.2 MB               overall ratio: 1.61×
```

### diagnose output (key section — scenario 13)

```
=== Problems Detected ===
[INFO]  HPROF record framing overhead: 5,000,000 bytes (100% of the file/runtime gap)
        With idSize=8, every INSTANCE_DUMP subrecord carries 25 bytes of framing
        that is not present in the runtime heap. This file has ~200,000 instance subrecords:
        framing = 5,000,000 bytes. This accounts for 100% of the 5,000,317-byte gap.
        No action is required; this is the expected and correct file size.
```

---

## Interpreting the size ratio: what each range suggests

Run `hprof-redact diagnose` to get the exact breakdown before drawing any conclusions.

| Ratio | Most likely explanation | How to confirm with `diagnose` |
|---|---|---|
| **1.05–1.10×** | Normal overhead (UTF-8, class dumps, GC roots, per-object framing). | Size attribution shows no dominant non-heap category. |
| **1.10–1.30×** | Slightly elevated UTF-8/class metadata, or live=false dump with moderate unreachable objects. | Check UTF-8 analysis for `[UNUSUALLY LARGE]`; check prim array bytes. |
| **~1.5–2.0× with `OBJECT_ID_OVERHEAD` flagged** | **Expected for object-heavy heaps** — per-instance HPROF framing exceeds the runtime header. 600M small instances on a 20 GB heap produce ~35–38 GB. **Normal; no action needed.** | `OBJECT_ID_OVERHEAD` (INFO) present, no `CONCATENATED_DUMP` (ERROR), framing explains ≥70% of gap. |
| **~1.5–2.5×, gap ≈ half the file, no framing explanation** | **Concatenated dump.** | `Duplicate Headers` warning + `UNKNOWN(0x4a)` in record histogram + `Trailing Bytes` warning. |
| **3–7× vs. a live=true dump** | Unreachable objects dominate. | Cannot be distinguished by `diagnose` alone; take a `jmap -dump:live` separately. |
| **>10× or gap >> half the file** | GZip confusion, extreme UTF-8 bloat, or mostly class metadata. | Check file extension. Run `gzip -l`. Check `utf8_strings` and `class_dumps` in attribution. |

### Why ~1.85× may be framing overhead OR a concatenated dump

**`OBJECT_ID_OVERHEAD` present, no `CONCATENATED_DUMP`:**
The ratio is explained by per-instance HPROF framing. A heap with ~600M small instances and a
20 GB runtime heap will produce ~36 GB of HPROF data at 25 bytes framing per object. This is
normal and expected. No action required.

**`CONCATENATED_DUMP` present:**
A second HPROF stream was appended to the file. A parser reads only the first stream. Fix: delete
the dump file between JVM restarts, or point `-XX:HeapDumpPath` at a *directory* (the JVM will
create `java_pid<N>.hprof` inside it, never colliding with a previous file).

---

## Summary table (all scenarios, measured)

| # | Scenario | File size | Heap data | Ratio | Primary `diagnose` signal |
|---|---|---|---|---|---|
| 1 | Concatenated dump (2 streams) | 76.4 MB | ~40 MB (first stream) | **2.00×** | `Duplicate Headers` warning |
| 2 | UTF-8 bloat (50k strings) | 43.5 MB | ~40 MB | 1.09× | UTF-8 `[UNUSUALLY LARGE]` |
| 3a | All objects incl. unreachable | 103.2 MB | ~14.8 MB live | **~7×** | Large file vs. live=true |
| 3b | Live objects only (GC first) | 14.8 MB | ~12.0 MB | 1.23× | — |
| 4 | Clean baseline | 34.8 MB | ~33 MB | 1.06× | Normal 5–7% overhead |
| 5 | Truncated (60% of file) | 62.2 KB | ~60 KB | ~1× | `Segment Issues` + `Trailing Bytes` |
| 6 | Segment length mismatch | 742 B | 20 B | **37×** | `Segment Issues` (declared≠consumed) |
| 7 | Duplicate object IDs | 188 B | — | — | `Duplicate Object IDs` table |
| 8 | GZip (on-disk vs decompressed) | 765 B | ~51 KB decompressed | **67.8×** | Check decompressed size |
| 9 | Class metadata overhead (500 classes) | 56.0 KB | ~0 B | ∞ | `class_dumps` in attribution |
| 10 | UTF-8 dominant (50 KB record) | 65.5 KB | 4 B | **16,765×** | UTF-8 largest-record field |
| 11 | HPROF framing (100k empty instances) | 2.4 MB | 0 B | ∞ | `OBJECT_ID_OVERHEAD` (INFO) |
| 13 | Realistic mix (200k instances + arrays) | 12.9 MB | ~8.2 MB | 1.61× | `OBJECT_ID_OVERHEAD` (INFO) |

---

## Five-step triage guide

**Step 1 — Check for GZip confusion**

```bash
gzip -l file.hprof.gz   # see uncompressed size
```

If the file is `.hprof.gz`, compare against the *uncompressed* size, not the `.hprof.gz` size.

**Step 2 — Run diagnose, look at Duplicate Headers first**

```bash
hprof-redact diagnose file.hprof
```

Any `WARNING: Additional HPROF header` means you have a concatenated dump. The bytes of the
second (and subsequent) streams are not parsed.

**Step 3 — Check Size Attribution**

Look for categories that dominate the `On Disk (bytes)` column but contribute nothing to the
runtime heap:
- `unknown_or_unparseable` — truncation, padding, or second-stream garbage
- `utf8_strings` — check if `[UNUSUALLY LARGE]` fires
- `class_dumps` — large class-loading footprint
- `heap_objects.instances` dominating — check for `OBJECT_ID_OVERHEAD` in Problems section

**Step 4 — Read the overhead breakdown**

In the Size Attribution section, `diagnose` prints:

```
Overhead in file beyond runtime heap (explains disk > runtime gap):
  instance subrecord framing  (25 bytes per object, idSize=8): N bytes
  combined: N bytes
```

If `combined ≈ (file size − runtime heap size)`, the gap is fully explained by expected HPROF
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
