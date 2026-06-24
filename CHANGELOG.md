# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- New `diagnose` subcommand: single-pass HPROF analysis reporting file metadata,
  record/subrecord histograms, size attribution, UTF-8 analysis, top-N classes and
  arrays, segment issues, duplicate headers, trailing bytes, and optional duplicate
  object-ID scan
- `diagnose --json` flag for machine-readable output
- `diagnose --histogram` flag: full per-class histogram sorted by on-disk bytes,
  with instance count, total on-disk bytes, framing overhead, and estimated runtime heap
- `diagnose --compact-headers` flag for JDK 25+ compact object headers (JEP 519,
  8-byte header → 17 bytes/obj net framing overhead)
- Diagnostic summary section at the top of the text report: file size, heap estimate,
  file/heap gap, total instance count, average instance size, compressed-oops inference,
  and gap breakdown with percentages (framing, obj-array ref expansion, metadata)
- Size attribution breakdown: on-disk bytes by category plus estimated runtime heap
  size with and without compressed oops
- Per-instance HPROF framing overhead tracking: net file-only overhead per object =
  25 bytes framing minus the runtime object header (13 bytes/obj with compressed oops ON,
  9 bytes/obj with compressed oops OFF, 17 bytes/obj with compact headers);
  explains why a 20 GB heap produces a 36+ GB file
- OBJ_ARRAY compressed-reference expansion tracking: 4 extra bytes per element when
  idSize=8 but runtime uses compressed oops (refSize=4)
- Problem detection with severity levels (ERROR / WARNING / INFO): concatenated dumps,
  corrupt segments, abnormally large UTF-8 sections, duplicate object IDs, and
  per-instance framing overhead as the primary cause of large file/heap ratios
- Synthetic HPROF test scenarios covering documented size-discrepancy cases

### Changed
- All Eclipse MAT-specific naming removed from the codebase; replaced with
  tool-neutral terminology ("estimated heap size", "heap analysis tools")
- `MatShallowSizeEstimator` renamed to `HeapSizeEstimator`
- `diagnose` command description updated to "disk-vs-runtime size discrepancies"
- Compressed oops status is now inferred automatically from heap size (< 32 GB → ON,
  ≥ 32 GB → OFF); removed `--assume-compressed-oops` (was a no-op) and
  `--no-compressed-oops` (manual override no longer needed)

### Fixed
- `--assume-compressed-oops` and `--no-compressed-oops` CLI flags were declared but
  never passed to the diagnostic engine (now replaced by automatic inference)

## [0.2.1] - 2026-02-24

### Added
### Changed
### Deprecated
### Removed
### Fixed
### Security

## [0.2.0] - 2026-02-16

### Added
- Nicer facade methods to HprofRedact

### Changed
- Renamed HprofFilter to HprofRedact

## [0.1.0] - 2026-02-15

### Added
- Initial release of hprof-redact
- Stream-based HPROF heap dump filtering and redaction
- Three transformer options:
  - `zero`: Zero out primitive values and string contents
  - `zero-strings`: Zero out string contents only
  - `drop-strings`: Remove string contents entirely
- Comprehensive test suite including real heap dump parsing
- GitHub Actions CI/CD workflow for building and releasing
- MIT license