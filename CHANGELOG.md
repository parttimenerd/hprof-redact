# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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