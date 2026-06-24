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

## Scenario 11 — Object-ID and record framing overhead (idSize=8, many small objects)

### Explanation

This is the **most commonly misdiagnosed scenario**. When a JVM heap contains hundreds of millions of small objects — linked-list nodes, tree entries, hash-map entries, cache elements — the HPROF file grows substantially larger than what Eclipse MAT reports as heap size, even with no anomalies.

The cause is the fixed **25-byte per-object framing** in every `HPROF_GC_INSTANCE_DUMP` subrecord that MAT does not count:

| Field | Size | MAT counts it? |
|---|---|---|
| subtag byte | 1 byte | No |
| objectId | 8 bytes (idSize=8) | No |
| stackTraceSerial | 4 bytes | No |
| classId | 8 bytes (idSize=8) | No |
| dataLength field | 4 bytes | No |
| **instance data payload** | dataLength bytes | **Yes — this is all MAT counts** |

**Per-object overhead not counted by MAT: 25 bytes**

At 500 million objects this amounts to **12.5 GB** of on-disk bytes that are invisible to MAT's heap-size figure. For a 20 GB heap with ~600 million objects, this overhead alone accounts for ~15 GB — bringing the file to ~35–38 GB while MAT reports ~20 GB, producing the characteristic **~1.8–2.0× ratio**.

Additionally, every reference field in an instance payload is stored as **8 bytes** in the HPROF file (the full uncompressed object address). At runtime, compressed oops stored references as 4 bytes. MAT's `INSTANCE_DUMP` formula counts `dataLength` as-is, which includes the expanded 8-byte references. For `OBJ_ARRAY_DUMP`, however, MAT explicitly uses `refSize=4`, so the 4 extra bytes per array element are **not** counted.

**When to expect this ratio:**
A 20 GB JVM heap with compressed oops and ~600M small instances (avg payload ≤ 32 bytes) will produce an HPROF file of **35–40 GB** — a ratio of ~1.75–2.0×. This is not anomalous; it is the expected, correct file size.

**How to confirm it is not a concatenated dump:**
Run `hprof-redact diagnose`. If the `=== Problems Detected ===` section shows only `OBJECT_ID_OVERHEAD` (INFO) and no `CONCATENATED_DUMP` (ERROR), the size is explained entirely by framing overhead. No duplicate headers will be present.

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 11 — 100,000 empty instances (0-byte payload, idSize=8): the most extreme case where MAT sees 0 bytes but disk has 2.5 MB.

Scenario 13 — 200,000 instances (16-byte payload) + 10 large arrays: demonstrates the realistic ~1.6× ratio.

```
java SyntheticScenarios /tmp/hprof-scenarios
```

### Measured results

**Scenario 11 — pure framing (no payload):**

```
instances: 100,000 @ 0 bytes payload each
total disk: 2.4 MB  MAT sees: 0 bytes  (ratio: ∞, all framing)
framing overhead = 100% of file
```

**Scenario 13 — realistic mix (200k instances @ 16 bytes payload + 10 × 500 KB arrays):**

```
instances: 200,000 @ 16 bytes payload each
arrays:    10 @ 500 KB each (= 5 MB array data)
disk instances: 8.0 MB   MAT instances: 3.2 MB  (ratio 2.56×)
disk arrays:    5.0 MB   MAT arrays:    5.0 MB  (ratio 1.00×)
total disk: 12.9 MB      MAT total: 8.2 MB      (overall ratio 1.61×)
```

### diagnose output (key section — scenario 13)

```
=== Problems Detected ===
[INFO]  HPROF record framing overhead: 5,000,000 bytes (100% of the file/MAT gap)
        With idSize=8, every INSTANCE_DUMP subrecord carries 25 bytes of framing (1 subtag +
        8 objectId + 4 stackTrace + 8 classId + 4 dataLength) that Eclipse MAT does not
        include in its heap-size calculation — it counts only the instance data payload
        (dataLength). ... This file has ~200,000 instance subrecords: framing = 5,000,000
        bytes, obj-array reference expansion = 0 bytes, combined 5,000,000 bytes. This
        accounts for 100% of the 5,000,317-byte gap. ...
        No action is required; this is the expected and correct file size.
```

**Key signal:** `OBJECT_ID_OVERHEAD` (INFO) present; no `CONCATENATED_DUMP` (ERROR); `LARGE_UNREACHABLE_RATIO` is suppressed. The size attribution shows `heap_objects.instances` dominating the disk column.

---

## Scenario 12 — Reference expansion in OBJ_ARRAY_DUMP (idSize=8)

### Explanation

When `idSize=8` but the JVM uses compressed oops (default for heaps < 32 GB), each element in an `OBJ_ARRAY_DUMP` is stored as **8 bytes** on disk (the full uncompressed address). Eclipse MAT's formula for object arrays uses `refSize=4` (compressed), so it counts only 4 bytes per element. The other 4 bytes per element are invisible to MAT.

This is similar to but distinct from the instance-payload reference expansion: for `INSTANCE_DUMP`, MAT counts `dataLength` directly (which already includes 8-byte refs), so the expansion IS counted for instance fields. For `OBJ_ARRAY_DUMP`, the formula explicitly applies `refSize=4` regardless.

**Example:** A `Node[]` array holding 500M references:
- On disk: 500M × 8 = 4 GB of reference data
- MAT counts: 500M × 4 = 2 GB

### Reproducer

`test_programs/SyntheticScenarios.java` scenario 12 — 50,000 instances referenced by one large OBJ_ARRAY.

### Measured result

```
elements: 50,000  array disk: 400,025 bytes  ref-expansion: 200,000 bytes
total: 1.6 MB   ratio: ~1.24× (references only; rest of file is instances)
```

### diagnose output

```
[INFO]  HPROF record framing overhead: 1,450,000 bytes (100% of the file/MAT gap)
        ... This file has ~50,000 instance subrecords: framing = 1,250,000 bytes,
        obj-array reference expansion = 200,000 bytes, combined 1,450,000 bytes. ...
```

The `compressedRefExpansionBytes` is shown in the attribution table under "Overhead not counted by MAT".

---

## Interpreting the size ratio: what each range suggests

When you observe `file_size / MAT_heap_size ≈ R`, the following heuristics apply. Run `hprof-redact diagnose` to get the exact breakdown.

| Ratio R | Most likely explanation | How to confirm with `diagnose` |
|---|---|---|
| **1.05–1.10×** | Normal overhead (UTF-8, class dumps, GC roots, framing). No anomaly. | Size attribution shows no dominant non-heap category. |
| **1.10–1.30×** | Slightly elevated UTF-8/class metadata, or live=false dump with moderate unreachable objects. | Check UTF-8 analysis for `[UNUSUALLY LARGE]` flag; compare `prim_arrays` on-disk vs. MAT formula. |
| **~1.5–2.0× with `OBJECT_ID_OVERHEAD` flagged** | **Expected for object-heavy heaps** — per-instance framing not counted by MAT. 600M small instances on a 20 GB heap produce ~35–38 GB. **Normal; no action needed.** | `OBJECT_ID_OVERHEAD` (INFO) present, no `CONCATENATED_DUMP` (ERROR), framing explains ≥70% of gap. |
| **~1.5–2.5× and gap ≈ half the file, no framing explanation** | **Concatenated dump** (two successive OOM dumps appended to the same file). The second stream is as large as the first. | `Duplicate Headers` warning + `UNKNOWN(0x4a)` in record histogram + `Trailing Bytes` warning. |
| **2×+ with "Keep unreachable objects" already on, no framing explanation** | Still concatenated. Unreachable objects cannot explain it if MAT already counted them. The ~2× gap remains entirely from the second stream being invisible. | Same as above — duplicate header at offset ≈ half the file size. |
| **3–7× compared to a `live=true` dump** | Unreachable objects dominate. The JVM had not GC'd recently before the dump. A `live=true` re-dump would be far smaller. | Cannot be distinguished from a live dump by `diagnose` alone; take a `jmap -dump:live` separately. |
| **>10× or gap >> half the file** | GZip confusion (comparing compressed on-disk size against MAT's decompressed heap figure), extreme UTF-8 bloat, or a file that is mostly class metadata with very few heap objects. | Check file extension (`.hprof.gz` vs `.hprof`). Run `gzip -l` to get decompressed size. Check `utf8_strings` and `class_dumps` rows in attribution. |

### Why a ~1.85× ratio may be either framing overhead OR a concatenated dump

**If `diagnose` shows `OBJECT_ID_OVERHEAD` but NOT `CONCATENATED_DUMP`:**
The ratio is explained by per-instance HPROF framing. A heap with ~600M small instances and a 20 GB runtime heap will produce ~36 GB of HPROF data at 25 bytes framing per object. This is normal.

**If `diagnose` shows `CONCATENATED_DUMP`:**
A second HPROF stream was appended to the file. MAT parses only the first stream, making the file appear ~2× the heap size. Fix: delete the dump file between JVM restarts.
3. `Trailing Bytes` — the second stream's content past its own `HEAP_DUMP_END` reported as garbage.

**Recommended remediation:**
- Delete or rename the dump file between JVM restarts.
- Point `-XX:HeapDumpPath` at a *directory* instead of a fixed filename; the JVM will create `java_pid<N>.hprof` inside it, never colliding with a previous file.
- Run `hprof-redact diagnose` as the first step in any dump analysis workflow — it flags a concatenation in seconds.

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
  echo "=== $f ==="; java -jar $JAR diagnose "$f" | grep -E "(^Size:|^TOTAL|^WARNING|Largest record)"; echo
done
```
