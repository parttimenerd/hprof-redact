# Why Your HPROF File Is Larger Than What MAT Reports

Eclipse MAT's "heap size" figure and the on-disk file size are measuring fundamentally different things. This document catalogues every known scenario where the two diverge significantly, explains the root cause, and lists what `hprof-redact diagnose` reports for each.

---

## How MAT counts "heap size"

MAT's parser reads the HPROF binary stream and accumulates a heap-size figure by summing only three categories of subrecords found inside `HPROF_HEAP_DUMP` or `HPROF_HEAP_DUMP_SEGMENT` records:

| Subrecord | MAT formula |
|---|---|
| `HPROF_GC_INSTANCE_DUMP` | `dataLength` (trusts the JVM-written value) |
| `HPROF_GC_OBJ_ARRAY_DUMP` | `alignUp(idSize + refSize + 4 + N * refSize, objectAlign)` |
| `HPROF_GC_PRIM_ARRAY_DUMP` | `alignUp(idSize + refSize + 4 + N * elemSize, objectAlign)` |

Everything else in the file — metadata, strings, class definitions, GC roots, framing bytes, second headers, trailing garbage — is silently skipped and not counted.

The reference size (`refSize`) is either `idSize` (uncompressed oops) or `4` (compressed oops); the default `objectAlign` is 8. MAT auto-detects oops compression from a `HPROF_HEAP_SUMMARY` record when present.

---

## Scenario 1 — Concatenated dump (two complete HPROF streams in one file)

**Root cause:** The JVM's `-XX:+HeapDumpOnOutOfMemoryError` handler opens the dump file in append mode. If a file already exists at `-XX:HeapDumpPath`, the new dump is appended rather than overwriting, producing two complete HPROF streams end-to-end.

**Common triggers:**
- The same process crashes with OOM more than once without clearing the dump file between runs.
- An operator runs `jmap -dump` manually while the JVM is configured to also produce an OOM dump; both write to the same path.
- A container-orchestration system restarts the JVM in the same working directory without cleaning up.
- A long-lived JVM hits OOM, is restarted in-place, and hits OOM again.

**Effect on file size:** `N × single-dump-size`. For two identical dumps the ratio approaches 2.0×.

**What MAT sees:** MAT's parser reads the first `JAVA PROFILE 1.0.x\0` header, processes that stream to completion, and stops. The second stream is silently ignored. MAT's reported heap size reflects only the first dump.

**What `diagnose` reports:**
- `Duplicate Headers` section: `WARNING: Additional HPROF header found at decompressed offset <N>`.
- Record histogram: `UNKNOWN(0x4a)` entry (byte `'J'` from the second `"JAVA PROFILE"` treated as an unknown record tag).
- `Trailing Bytes`: parse stops at the second header's first unknown tag; trailing bytes are reported.
- Size attribution: `unknown_or_unparseable` captures the unaccounted bytes.

**How to reproduce:** see `test_programs/OomDoubleHeapDump.java`.

---

## Scenario 2 — Excessive UTF-8 string data

**Root cause:** `HPROF_UTF8` records carry the name of every class, method, field, and source file referenced by the heap dump. In dumps from large enterprise applications or platforms with heavy dynamic class generation (e.g., Groovy, AspectJ, OSGi, JEE class loaders), the cumulative UTF-8 payload can reach several gigabytes.

**Effect on file size:** UTF-8 bytes do not count toward MAT's heap size at all. A dump could have 10 GB of string data and 5 GB of actual heap objects; MAT reports "5 GB", the file is 15 GB.

**What MAT sees:** MAT decodes all UTF-8 records into memory during parsing (transient ~peak), then discards unreferenced strings. Only the subset referenced by `LOAD_CLASS`, `HPROF_FRAME`, and class-dump field/static name pointers stays resident in `ClassImpl` objects for the session.

**What `diagnose` reports:**
- UTF-8 analysis: `[UNUSUALLY LARGE]` flag if UTF-8 bytes > 5% of file size, plus a breakdown of referenced vs. unreferenced bytes.
- Record histogram: `HPROF_UTF8` will dominate the total-bytes column.

---

## Scenario 3 — Large primitive arrays not counted by MAT

**Root cause:** MAT's heap-size formula for primitive arrays uses `alignUp(idSize + refSize + 4 + N * elemSize, objectAlign)`, which reflects the JVM's in-memory representation, not the on-disk bytes. For an `int[]` of N elements: on-disk payload is `N * 4` bytes, but MAT's formula produces a slightly different value (differs only by alignment padding). This effect is small per array.

However, if the dump is produced with `-XX:-UseCompressedOops`, `idSize = refSize = 8`. MAT's formula then uses `refSize = 8` (or `4` if it auto-detects compressed oops), leading to a discrepancy when oops settings disagree.

More importantly: if the dump was taken with `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath` and the underlying JVM is `SapMachine`/`Azul`/`Eclipse OpenJ9`, the HPROF format details may differ subtly. MAT may miscalculate the `dataLength` for instance dumps, attributing less heap than is actually on disk.

**Effect on file size:** Typically a few percent difference, rarely 2×.

**What `diagnose` reports:**
- Size attribution: separate `matHeapSizeWithCompressedOops` and `matHeapSizeWithoutCompressedOops` columns for every category.
- Top-N primitive arrays: per-array disk bytes vs. MAT estimates in both compressed/uncompressed modes.

---

## Scenario 4 — GC class dumps, roots, and framing overhead

**Root cause:** The following record types are written to every HPROF but are never counted by MAT's heap size:

| Category | Typical contribution |
|---|---|
| `HPROF_GC_CLASS_DUMP` subrecords | One per loaded class; grows with class count |
| `HPROF_GC_ROOT_*` subrecords | One per GC root reference |
| Top-level record framing | 9 bytes per top-level record |
| Subrecord tag bytes | 1 byte per heap-dump subrecord |
| `HPROF_LOAD_CLASS` records | One per loaded class |
| `HPROF_FRAME` / `HPROF_TRACE` | One per stack frame / stack trace |

In a typical production dump these sum to 5–15% of the file. In a dump from an application with thousands of loaded classes and deep stack traces, this can approach 30%.

**What `diagnose` reports:**
- Size attribution table with `class_dumps`, `gc_roots`, `segment_framing`, `load_class`, `frames_traces_threads` columns all broken out separately.

---

## Scenario 5 — Truncated or partially written dump (file too small vs. MAT)

**Root cause:** The JVM was killed mid-write (OOM kill by the OS, signal, disk full), or the dump was transferred incompletely. The resulting file is incomplete.

**Effect on file size:** File is *smaller* than a complete dump would be, not larger. MAT may still parse the partial first segment and report a plausible (but incorrect) heap size.

**What `diagnose` reports:**
- `Trailing Bytes` section: `WARNING: (unknown count) trailing bytes after last well-formed record … Reason: Unexpected end of stream`.
- Possibly a `Segment Issues` entry if a `HEAP_DUMP_SEGMENT` length was declared but not fully written.

---

## Scenario 6 — Segment length mismatch

**Root cause:** The HPROF record format records a `u4 length` field for every top-level record. If the JVM crashes or is interrupted while writing a `HPROF_HEAP_DUMP_SEGMENT`, the declared length may exceed the number of subrecord bytes actually written. The parser will read `length` bytes as part of that segment even though the remainder is garbage.

**What `diagnose` reports:**
- `Segment Issues`: one entry per mismatched segment with `declaredLength`, `consumedBytes`, and the decompressed offset.

---

## Scenario 7 — Unreachable objects included (MAT option "Keep unreachable objects")

**Root cause:** By default MAT excludes unreachable objects from its heap-size calculation and its UI. When "Keep unreachable objects" is enabled in the MAT parser, MAT includes those objects in the heap graph but still reports only the sum of the three counted categories, so the net effect on the reported size is small.

However, the *customer-facing* discrepancy arises when the user looks at MAT's default view (which excludes unreachable objects) and compares it to the file size, which always includes everything the JVM wrote.

The JVM writes all live objects at dump time (with `jmap -dump:live` the GC runs first; without it, dead objects are also included). MAT's "Keep unreachable objects" option controls whether the *parser* builds graph nodes for them, not whether they appear on disk.

**Effect on file size vs. MAT reported size:** Can be 2–5× in applications with high object churn (e.g., after a GC pause, many objects are dead but were allocated since the last GC and are still in-file).

**What `diagnose` reports:**
- The size attribution table shows the full on-disk count regardless of reachability; it cannot distinguish live from unreachable objects without a GC-root traversal.

---

## Scenario 8 — GZip double-counting (decompressed size vs. on-disk size)

**Root cause:** HPROF files are often stored gzip-compressed (`.hprof.gz`). The "on-disk size" shown by `ls` is the compressed size. `hprof-redact diagnose` and MAT both work on the *decompressed* stream. If you compare the compressed file size against MAT's heap size you will always see a large discrepancy.

**Example:** A 36 GB dump compresses 3× to a 12 GB `.hprof.gz`. MAT reports 19 GB (decompressed heap size). The on-disk `.hprof.gz` is 12 GB. None of these numbers are the "same thing".

**What `diagnose` reports:**
- `File summary` shows on-disk size. All byte offsets in the report are decompressed-stream offsets.

---

## Scenario 9 — Multiple heap dump segments from a long GC pause (SapMachine / OpenJ9)

**Root cause:** Some JVM implementations write heap dump data in multiple `HPROF_HEAP_DUMP_SEGMENT` records as they traverse the heap. If the dump is interrupted and restarted (e.g., a JVM with a custom HPROF agent that writes incrementally), the same objects may appear in more than one segment. MAT processes all segments but may de-duplicate objects by ID; the file still contains all the bytes.

**What `diagnose` reports:**
- `Subrecord histogram` shows the total count and bytes across all segments.
- `Duplicate Object IDs` section (with `--detect-duplicate-ids`): lists object IDs that appear in more than one `INSTANCE_DUMP`, `OBJ_ARRAY_DUMP`, or `PRIM_ARRAY_DUMP`.

---

## Summary table

| Scenario | Disk >> MAT? | Detectable by `diagnose`? | Key `diagnose` section |
|---|---|---|---|
| Concatenated dump (two streams) | Yes, ~2× | Yes | Duplicate Headers |
| Excessive UTF-8 strings | Yes, up to 50%+ | Yes | UTF-8 Analysis |
| MAT oops miscalculation | Small, <10% | Partially | Size Attribution (both columns) |
| Class/root/framing overhead | 5–30% | Yes | Size Attribution |
| Truncated dump | No (file is smaller) | Yes | Trailing Bytes / Segment Issues |
| Segment length mismatch | Possible | Yes | Segment Issues |
| Unreachable objects in dump | Yes, 2–5× | No (can't distinguish) | — |
| GZip confusion | Yes (apples/oranges) | N/A (different units) | File summary note |
| Duplicate object IDs | Possible | Yes (opt-in) | Duplicate Object IDs |

---

## Quick triage guide

1. **Run `hprof-redact diagnose <file>`** and look at the `Duplicate Headers` section first. If you see a warning there, stop — you have a concatenated dump. The second stream is entirely unaccounted for in MAT.

2. **Check the Size Attribution table.** Look at `unknown_or_unparseable` and `utf8_strings`. If either dominates, you've found the gap.

3. **Compare the two MAT heap size estimates** (compressed oops vs. uncompressed). If they differ significantly, the oops mode may be the source of confusion.

4. **Check Trailing Bytes.** A truncated or partially-appended dump will show up here.

5. **Enable `--detect-duplicate-ids`** if you suspect a JVM that writes objects more than once.
