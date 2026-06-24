# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- New `diagnose` subcommand: single-pass HPROF analysis reporting size attribution,
  anomaly detection, record/subrecord histograms, UTF-8 analysis, top-N classes and
  arrays, segment issues, duplicate headers, trailing bytes, and optional duplicate
  object-ID scan
- `diagnose --json` flag for machine-readable output
- Size attribution breakdown: on-disk bytes by category (instances, arrays, class dumps,
  GC roots, UTF-8 strings, metadata, framing overhead) plus estimated Eclipse MAT heap
  size with and without compressed oops
- HPROF framing-overhead tracking: with `idSize=8`, each `INSTANCE_DUMP` subrecord
  carries 25 bytes of framing not counted by Eclipse MAT; the report shows how much of
  the disk/MAT gap this explains so large but structurally normal files are not
  misidentified as concatenated dumps
- Problem detection with severity levels (ERROR / WARNING / INFO): concatenated dumps,
  corrupt segments, abnormally large UTF-8 sections, duplicate object IDs, and
  per-instance framing overhead as the primary cause of disk/MAT size ratios ~1.5–2.0×
- Synthetic HPROF test scenarios covering all documented size-discrepancy cases

### Changed
### Deprecated
### Removed
### Fixed
### Security

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