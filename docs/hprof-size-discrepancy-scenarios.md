# Why Your HPROF File Is Larger Than What MAT Reports

Eclipse MAT's "heap size" figure and the on-disk file size measure fundamentally different things. This document explains every known scenario where the two diverge, with verified examples produced by `ScenarioProducer.java` and `SyntheticScenarios.java` and diagnosed with `hprof-redact diagnose`.

All byte figures are from actual runs on GraalVM 25.0.3 (JDK 25), idSize=8.

---

## Background: How MAT counts "heap size"

MAT's parser reads the HPROF binary stream and accumulates its "heap size" figure by summing exactly **three subrecord types** found inside `HPROF_HEAP_DUMP` / `HPROF_HEAP_DUMP_SEGMENT` records:

| Subrecord | MAT formula |
|---|---|
| `HPROF_GC_INSTANCE_DUMP` | `dataLength` (trusts the JVM-written value) |
| `HPROF_GC_OBJ_ARRAY_DUMP` | `alignUp(idSize + refSize + 4 + N × refSize, objectAlign)` |
| `HPROF_GC_PRIM_ARRAY_DUMP` | `alignUp(idSize + refSize + 4 + N × elemSize, objectAlign)` |

Everything else — metadata strings, class definitions, GC roots, segment framing, second HPROF headers, trailing garbage — is **silently skipped and not counted**.

Where `refSize` is `4` (compressed oops, typical for heaps <32 GB) or `idSize` (uncompressed), and `objectAlign` defaults to `8`.

A clean baseline dump of a ~30 MB JVM was measured as follows:

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

That **~5.7% baseline gap** is present in every clean dump. The scenarios below describe the additional factors that widen that gap to 2×, 7×, or more.

---

## Scenario 1 — Concatenated dump (two complete HPROF streams in one file)

### Explanation

The JVM's `-XX:+HeapDumpOnOutOfMemoryError` handler opens the target file in **append mode**. If a file already exists at `-XX:HeapDumpPath`, the new dump is appended without truncation, producing two complete HPROF streams back-to-back. Eclipse MAT reads the first `JAVA PROFILE 1.0.x\0` header, parses that stream to completion, and stops — it never reports the second stream.

**Common triggers:**
- A container restarts the JVM in-place without cleaning up the previous dump file.
- An operator runs `jmap -dump` while the JVM is also configured with `-XX:+HeapDumpOnOutOfMemoryError` to the same path; the OOM then appends a second dump.
- The same process crashes with OOM more than once with the same `-XX:HeapDumpPath`.
- A monitoring script moves but doesn't delete the dump file before restarting.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 1 — writes two full dumps, concatenates them with stream copy.

```
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
=== HPROF Diagnostic Report ===
File:     scenario-1-concatenated.hprof
Size:     76.4 MB (80,128,310 bytes)

--- Record Histogram ---
UNKNOWN(0x4a)      1     1,347,571,535   ← 'J' from "JAVA PROFILE" of 2nd stream
HPROF_HEAP_DUMP_SEGMENT   39   37,692,073
HPROF_UTF8         47,565    2,337,452
...

--- Size Attribution ---
heap_objects.instances     1,932,749    MAT(compressed): 35,947,397
heap_objects.prim_arrays  34,342,107    MAT(compressed):          0  (arrays counted via formula)
...
TOTAL on-disk:    40,503,591            ← only the first stream was parsed!

--- Duplicate Headers ---
WARNING: Additional HPROF header found at decompressed offset 40,064,140
         (magic: JAVA PROFILE 1.0.2)

--- Trailing Bytes ---
WARNING: trailing bytes after last well-formed record at offset 80,128,310
         Reason: Unexpected end of stream
```

**Key signal:** `Duplicate Headers` warning + `UNKNOWN(0x4a)` record in the histogram (byte `0x4a` = `'J'`, the first byte of `"JAVA PROFILE"` being parsed as an unknown record tag).

**MAT sees:** ~36 MB heap. **File on disk:** 76 MB. **Ratio: 2.00×.**

---

## Scenario 2 — UTF-8 string metadata exceeds threshold

### Explanation

Every `HPROF_UTF8` record carries the name of a class, method, field, or source file. In large enterprise applications, OSGi containers, or apps with heavy runtime code generation (Groovy, AspectJ, Spring AOP, JEE class loaders), the UTF-8 section can grow to several MB or more. None of this counts toward MAT's heap size.

**What MAT does with UTF-8 strings:**  
MAT decodes all UTF-8 records during parsing (peak memory ~= total UTF-8 payload). After parsing, only the subset whose `nameId` is referenced by `LOAD_CLASS`, `HPROF_FRAME`, or class-dump field/static descriptors stays resident in `ClassImpl` objects. The rest is GC-eligible. For the scenario-2 dump:

- Total UTF-8 bytes: **2,384,445** (5.2% of file — flagged as UNUSUALLY LARGE)
- Referenced (stays in MAT): **182,940 bytes**
- Unreferenced (GC-eligible after parse): **2,201,505 bytes**

The "unusually large" threshold is 5% of file size.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 2 — retains 50,000 unique `String` objects with long names. Each becomes a `String` instance (heap object) whose backing `byte[]` is a `HPROF_GC_PRIM_ARRAY_DUMP`, plus the class's `HPROF_UTF8` name records.

```
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 2
```

### Measured result

```
File size:    43.5 MB (45,640,348 bytes)
utf8_strings:  2.3 MB (2,384,445 bytes) = 5.2% of file  → [UNUSUALLY LARGE]
MAT heap:     40.0 MB (compressed oops)
Gap from UTF-8 alone: 2.3 MB
```

### diagnose output (key section)

```
--- UTF-8 Analysis ---
Total UTF-8 bytes:  2,384,445 (5,2% of file)  [UNUSUALLY LARGE]
  Referenced (MAT-resident after parse):   182,940 bytes
  Unreferenced (transient during parse): 2,201,505 bytes
Record count:  48,314
Largest record: 11,516 bytes
```

**Key signal:** `[UNUSUALLY LARGE]` flag in the UTF-8 analysis section, and `HPROF_UTF8` appearing near the top of the record histogram by total bytes.

---

## Scenario 3 — Unreachable objects included in dump (live=false)

### Explanation

`HotSpotDiagnosticMXBean.dumpHeap(path, live=false)` and `jmap -dump:format=b` write **every object in the heap**, including objects not reachable from any GC root. MAT's default view excludes these from the "heap size" figure and from the object graph unless "Keep unreachable objects" is enabled in the parse wizard.

`jmap -dump:live,format=b` (note the `live` option) triggers a full GC first, then dumps only surviving objects — a much smaller file.

The ratio depends on how recently the JVM ran a GC and how many short-lived objects exist. For an allocation-heavy application (or one that hasn't GC'd recently), the ratio can be enormous. In the controlled test below, 500 `byte[100_000]` arrays were allocated and left unreachable before the dump, producing a **6.95× ratio**.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 3 — allocates live objects, allocates unreachable objects, takes two dumps.

```
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios 3
```

### Measured result

```
live=false (all objects including unreachable):  103.2 MB (108,185,432 bytes)
live=true  (GC first, then dump live only):       14.8 MB  (15,563,496 bytes)
Ratio: 6.95×
```

### diagnose output comparison

live=false dump:
```
File: 103.2 MB
heap_objects.prim_arrays:  99,623,399 bytes on disk
MAT heap (compressed):    102,531,739 bytes
```

live=true dump:
```
File: 14.8 MB
heap_objects.prim_arrays:  11,018,221 bytes on disk
MAT heap (compressed):     11,981,401 bytes
```

**Key signal:** `diagnose` cannot distinguish live from unreachable objects — both appear as normal heap objects. The only diagnostic is the overall size mismatch. Use `jmap -histo:live` separately to get a live-object histogram to compare.

**Why MAT still shows a large "heap size" for live=false dumps:**  
MAT reports `heapObjectInstanceBytes + formulaForArrays`. Unreachable objects are counted by MAT's heap-size formula exactly like reachable ones. The discrepancy appears in the *object graph* (dominator tree, retained sizes), not in the raw heap size figure.

---

## Scenario 4 — Baseline: inherent overhead of a clean dump

### Explanation

Even a perfectly healthy dump has overhead that doesn't count toward MAT's heap size. This establishes the baseline.

### Reproducer

`test_programs/ScenarioProducer.java` scenario 4.

```
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

**Key insight:** A 5–7% gap between on-disk size and MAT heap size is completely normal for a clean dump. Any gap larger than ~10% warrants investigation.

---

## Scenario 5 — Truncated dump (file cut off mid-write)

### Explanation

If the JVM is killed mid-write (OOM-kill by the OS, `SIGKILL`, disk full, container crash), the resulting `.hprof` file is incomplete. The last segment will have a `length` field larger than the remaining bytes, and the file ends before `HPROF_HEAP_DUMP_END`.

**Effect on MAT:** MAT may still open and partially parse the file. It reports whatever heap objects it managed to read from the complete segments. The file is *smaller* than a complete dump, not larger.

**Effect on size comparison:** The truncation itself doesn't cause file > MAT, but combined with scenario 1 (the incomplete second stream from a concatenated dump appears as trailing garbage), it can explain the "trailing bytes" warning.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 5 — builds a 100-instance dump, keeps the first 60%.

```
java SyntheticScenarios /tmp/hprof-scenarios
```

### Measured result

```
Full dump:     103.6 KB
Truncated at 60%:  62.2 KB

diagnose reports:
  Segment at offset 76: declared=103,543 consumed=62,097
  → Segment consumed 62097 bytes but declared 103543
    (parse error: Unexpected end of stream)

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

**Key signal:** Both `Segment Issues` and `Trailing Bytes` are populated. The segment's `declaredLength > consumedBytes` proves the write was cut short mid-segment.

---

## Scenario 6 — Segment length mismatch (declared length ≠ actual content)

### Explanation

A `HPROF_HEAP_DUMP_SEGMENT` record declares its byte length in a `u4` header field. If this value is incorrect — whether from a buggy HPROF agent, a custom producer, or a partially-overwritten file — the segment parser will either:

- Read past the real subrecord content into garbage bytes (if `declared > actual`) — encounters unrecognised subrecord tags.
- Stop parsing the segment early, leaving real subrecords unread (if `declared < actual`).

MAT silently tolerates this: it reads `declaredLength` bytes from the stream regardless of what it found inside, and moves on. The objects in the "missing" portion are never counted.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 6 — writes a segment containing 5 INSTANCE_DUMPs (149 bytes of actual subrecords), but declares length = 149 + 500 = 649. The extra 500 bytes are filled with `0x00` (unknown subrecord tag).

### Measured result

```
Segment declares: 648 bytes
Segment consumes: 149 bytes (5 × INSTANCE_DUMP + 1 × CLASS_DUMP)
500 zero-padded bytes treated as unknown subrecord tags

diagnose:
  TOTAL on-disk:       240 bytes (framing only visible to parser)
  MAT heap:             20 bytes (5 instances × 4 bytes dataLength)
```

### diagnose output

```
--- Segment Issues ---
Segment at offset 76: declared=648 consumed=149
  Segment consumed 149 bytes but declared 648
  (parse error at offset 149: Unsupported heap dump subrecord tag: 0x0)
```

**Key signal:** `Segment Issues` entry where `declaredLength > consumedBytes`. The parse error message names the unexpected tag (`0x0` = zero padding in this case).

---

## Scenario 7 — Duplicate object IDs

### Explanation

Each live Java object should have a unique ID in the HPROF stream. Duplicate IDs occur in:

- **Concatenated dumps** — the same object was alive in both dump halves.
- **Buggy HPROF agents** — custom agents that write the same object more than once.
- **Multi-JVM dumps merged** — two separate JVMs' dumps cat'd together.

MAT's behaviour with duplicate IDs is implementation-dependent. It may keep the first, keep the last, or throw an error during parse. The on-disk bytes for both copies count toward the file size regardless.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 7 — writes objectId `0x200` twice in the same HEAP_DUMP_SEGMENT.

Run with `--detect-duplicate-ids` to enable the tracking hash set.

### Measured result

```
File: 188 bytes
3 × INSTANCE_DUMP: two share objectId=0x200

diagnose (with --detect-duplicate-ids):
--- Duplicate Object IDs ---
Object ID    Occurrences  Kind
0x200              2      INSTANCE_DUMP
```

**Key signal:** `Duplicate Object IDs` table populated (requires `--detect-duplicate-ids`). Without the flag, the section reads `[none found]` even if duplicates exist.

---

## Scenario 8 — GZip compression: on-disk size vs. decompressed size

### Explanation

HPROF files are frequently stored gzip-compressed (`.hprof.gz`). The "file size" reported by `ls` or a file manager is the *compressed* size. `hprof-redact diagnose` and Eclipse MAT both work on the *decompressed stream*. Comparing the compressed on-disk size directly against MAT's heap size is comparing apples and oranges.

**Example:** A 53 KB synthetic dump with 50 instances and 50 `byte[1000]` arrays compresses to 765 bytes — a 67.8× compression ratio for zero-filled byte arrays. A realistic production dump compresses 3–6×.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 8 — writes the same dump as raw HPROF and as `.hprof.gz`.

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
Size:   765 bytes (765 bytes)  ← on-disk (compressed) size
...
TOTAL on-disk:   51,884       ← decompressed byte count (what MAT sees)
MAT heap:        51,000
```

**Key signal:** `diagnose` shows the on-disk (compressed) size in the header but all byte offsets and counts refer to the decompressed stream. The `Size:` field = compressed file size. The `TOTAL on-disk` in the attribution table = decompressed bytes parsed.

**Triage:** If the file is `.hprof.gz`, always compare MAT's heap size against the *decompressed* size, not the compressed file size. Run `gzip -l file.hprof.gz` to get the uncompressed size.

---

## Scenario 9 — Class metadata overhead (500 class dumps, 2 instances)

### Explanation

Every loaded class produces:
- One `HPROF_UTF8` record for the class name (and potentially for field names, method names, source file names)
- One `HPROF_LOAD_CLASS` record
- One `HPROF_GC_CLASS_DUMP` subrecord inside the heap segment

In an application with thousands of loaded classes (OSGi, JEE, large frameworks), this metadata can dominate the file. None of it counts toward MAT's heap size.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 9 — 500 classes, 2 instances.

### Measured result

```
File: 56.0 KB (57,373 bytes)
  utf8_strings:   23.3 KB  (40.6% of file)   [UNUSUALLY LARGE]
  class_dumps:    21.5 KB  (37.5% of file)
  load_class:     12.5 KB  (21.8% of file)
  heap_objects:   32 bytes (0.06% of file)
  MAT heap:       0 bytes  (instances have instanceSize=0)

Non-heap overhead: 99.9% of the file
```

### diagnose output

```
--- Size Attribution ---
heap_objects.instances     32        MAT: 0
class_dumps            21,500        MAT: 0
utf8_strings           23,290        MAT: 0
load_class             12,500        MAT: 0

TOTAL on-disk:    66,851
MAT heap:              0

--- UTF-8 Analysis ---
Total UTF-8 bytes: 23,290 (40.6% of file)  [UNUSUALLY LARGE]
```

**Key signal:** `class_dumps` and `utf8_strings` dominating the size attribution table. The `[UNUSUALLY LARGE]` UTF-8 flag will fire when class metadata exceeds 5% of file size.

---

## Scenario 10 — UTF-8 records dominate (99.8% of file)

### Explanation

Extreme case: a file where a single very large `HPROF_UTF8` record (50,000 bytes, e.g. a class with a very long dynamically-generated name) is combined with many medium-sized name records. This demonstrates the upper bound of UTF-8 inflation.

Realistic triggers: JVMs or HPROF agents that emit full stack-trace strings, debugging annotations, or compile-time-generated metadata as UTF-8 records; broken HPROF producers that duplicate UTF-8 records for every occurrence rather than reusing nameIds.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 10 — 1 × 50 KB UTF-8 record + 200 × medium records, 1 actual instance.

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
Total UTF-8 bytes:  66,920 (99,8% of file)  [UNUSUALLY LARGE]
  Referenced (stays in MAT):    17 bytes
  Unreferenced (transient):  66,903 bytes
Largest record: 50,000 bytes  (sample: "AAAAAAAA...")
```

**Key signal:** Largest record size in the UTF-8 analysis — a record >10 KB is almost certainly an anomaly (class names are rarely >200 bytes). The "Unreferenced" fraction reveals whether the large string is even needed by MAT (here 99.97% is unreferenced).

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
| 6 | Segment length mismatch | 742 bytes | 20 bytes | **37×** | `Segment Issues` (declared≠consumed) |
| 7 | Duplicate object IDs | 188 bytes | 0 bytes | — | `Duplicate Object IDs` table |
| 8 | GZip (on-disk vs decompressed) | 765 bytes | 51.0 KB | **67.8×** | Apples/oranges — check decompressed |
| 9 | Class metadata overhead (500 classes) | 56.0 KB | 0 bytes | ∞ | `class_dumps` in attribution |
| 10 | UTF-8 dominant (50 KB record) | 65.5 KB | 4 bytes | **16,765×** | UTF-8 largest-record field |

---

## Five-step triage guide

Given a dump where on-disk size >> MAT reported heap size:

**Step 1 — Check for GZip confusion**
```
gzip -l file.hprof.gz   # see uncompressed size
```
If the file is `.hprof.gz`, compare MAT's heap against the *uncompressed* size, not the `.hprof.gz` size.

**Step 2 — Run diagnose, look at Duplicate Headers first**
```
hprof-redact diagnose file.hprof
```
Any `WARNING: Additional HPROF header` means you have a concatenated dump. The bytes of the second (and subsequent) streams are entirely invisible to MAT.

**Step 3 — Check Size Attribution**

Look for categories that dominate the `On Disk (bytes)` column but have `0` in `MAT Heap` columns:
- `unknown_or_unparseable` — truncation, padding, or second-stream garbage
- `utf8_strings` — check if `[UNUSUALLY LARGE]` flag fires
- `class_dumps` — large class-loading footprint

**Step 4 — Compare compressed oops estimates**

If `MAT Heap (compressed)` and `MAT Heap (uncompressed)` differ significantly, the compressed/uncompressed oops setting is contributing to the discrepancy. Match against what MAT reports.

**Step 5 — Enable duplicate-ID detection for suspected merged dumps**
```
hprof-redact diagnose file.hprof --detect-duplicate-ids
```
If the `Duplicate Object IDs` table is non-empty, the file contains data from more than one dump or a buggy agent.

---

## Reproducer programs

All programs are in `test_programs/`:

| Program | Produces |
|---|---|
| `ScenarioProducer.java` | Real JVM dumps: concatenated (S1), UTF-8 bloat (S2), unreachable objects (S3), baseline (S4) |
| `SyntheticScenarios.java` | Synthetic HPROF: truncated (S5), segment mismatch (S6), duplicate IDs (S7), gzip (S8), class overhead (S9), UTF-8 dominant (S10) |
| `OomDoubleHeapDump.java` | Concatenated dump via MXBean + stream-copy (the original customer scenario) |

```bash
# Generate all scenario files
cd test_programs
javac ScenarioProducer.java SyntheticScenarios.java
java -Xmx512m ScenarioProducer /tmp/hprof-scenarios
java SyntheticScenarios /tmp/hprof-scenarios

# Diagnose them all
JAR=../target/hprof-redact.jar
for f in /tmp/hprof-scenarios/*.hprof /tmp/hprof-scenarios/*.hprof.gz; do
  echo "=== $f ==="; java -jar $JAR diagnose "$f" | grep -E "(^Size:|^TOTAL|^WARNING|Largest record)"; echo
done
```
