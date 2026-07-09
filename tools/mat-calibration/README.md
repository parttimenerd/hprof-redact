# MAT-heap-prediction calibration corpus

Synthetic and semi-realistic Java workloads used to calibrate `hprof-redact mat-heap`'s
coefficients against actual MAT memory usage.

These tools are for hprof-redact developers only. They are not shipped with the CLI,
and `hprof-redact` itself never invokes MAT at runtime. MAT is only executed here
during developer calibration to measure ground truth.

## Layout

```
tools/mat-calibration/
├── README.md                          # this file
├── capture-large-dump.sh              # entry point: produce a sized .hprof
├── test_programs/
│   └── HashMapHeavyDump.java          # synthetic, dimension-stressing dump
└── spring-petclinic/
    ├── EnterpriseMemoryProfile.java   # Spring @Component patched into Petclinic
    └── setup.sh                       # clone + patch + build Petclinic jar
```

## Workloads

### `HashMapHeavyDump` — synthetic, dimension-stressing

`test_programs/HashMapHeavyDump.java` allocates `HashMap<String, Node>` graphs sized
to stress each dimension `MatMemoryModel` cares about: many instances, high outbound
reference density, many strings, many object arrays (`HashMap.Entry[]`), modest class
count. Configurable via `-D` system properties — see the file header.

Use this for **fine-grained sensitivity sweeps**: change one knob, regenerate, measure
MAT's actual heap. Class count stays low here so the parser-state components dominate.

### Spring Petclinic + `EnterpriseMemoryProfile` — high class count

`spring-petclinic/EnterpriseMemoryProfile.java` is a `@Component` that gets patched
into a fresh Spring Petclinic clone. On `ApplicationReadyEvent` it populates:

1. Owner cache (Hibernate-second-level-cache shape)
2. Request-history ring buffer (observability-buffer shape)
3. Blob region (uploads / serialised payloads)
4. AOP proxy snapshots (Spring proxy + interceptor chain shape)

Use this for **realistic high-class-count, high-string-table workloads** — Spring's
class-loading already gives you ~15k classes before our patch runs.

## Quick start

```bash
# Synthetic, ~2 GB heap:
./capture-large-dump.sh hashmap        # → /tmp/mat-calibration/hashmap-large.hprof

# Realistic Spring app, ~2 GB heap:
./spring-petclinic/setup.sh            # clones + patches + builds (one time)
./capture-large-dump.sh petclinic      # → /tmp/mat-calibration/petclinic-large.hprof
```

Sizes are tunable via `PROFILE=small|medium|large` (≈300 MB / 1 GB / 2 GB target
resident heaps). `XMX` overrides the JVM max heap, `OUT` overrides the dump path.

## How calibration uses these dumps

For each dump in the corpus:

1. Run `hprof-redact mat-heap <dump>` → records the model's predicted peak.
2. Run MAT's `ParseHeapDump.sh` under `/usr/bin/time -l` (macOS) or `-v` (Linux) at
   stepped `-Xmx` values to find the actual minimum heap MAT needs.
3. Record peak RSS from the same `/usr/bin/time` invocation.
4. Fit / re-fit the `MatMemoryModel` coefficients against (predicted, actual) pairs.

The corpus is intentionally varied: HashMapHeavy isolates parser-state pressure;
Petclinic adds class-count and string-table mass. Together they let the fit detect
mis-attribution between components.

## Notes

- The `large` profile targets ~2 GB resident heap — give the JVM at least
  `XMX=3g` and your machine at least 4 GB free.
- `EnterpriseMemoryProfile` writes `/tmp/petclinic-memory-profile-ready` once
  population is done; the capture script polls for that marker before dumping.
- Dumps are full (`live=true` in `HotSpotDiagnosticMXBean#dumpHeap`) so MAT's
  reachability pass has no unreachable noise to skip.
