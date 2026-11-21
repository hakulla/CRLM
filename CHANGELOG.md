# Changelog

All notable changes to CRLM are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project uses semantic versioning for
application releases.

## [Unreleased]

### Added

- AGPL-3.0-only project license, NOTICE, privacy policy, contributing guide, code of conduct,
  issue/PR templates, architecture and release documentation.
- CI build workflow for the Lite APK.
- Third-party license texts for llama.cpp and the LLVM libc++ runtime.

### Changed

- General documentation and repository metadata prepared for public distribution.

## [1.3.3] - 2026-11-21

### Fixed

- On-device builds now package `assets` and generate `source-seed.zip`; self-built APKs keep their
  language packs and source.
- Self-build signing consistently uses `crlm-debug.keystore`, and the bootstrap toolchain carries
  the same signing certificate as the release APK.
- Added build verification for the APK, source seed, and Chinese language pack.
- Repaired the native local-model JNI symbol/GNU-hash metadata so `liblocal_llm.so` loads again.
- Fixed MCP discovery dispatch so `mcp_discover` reaches its registered executor.
- Language-pack generation retries failed batches and preserves already translated strings.
- Localized automation schedule/outcome text and corrected the screenshot tool result text.
- Replaced the device-specific browser User-Agent with a generic mobile value.
- Generalized the README visual-experiment example.
- Added missing third-party license notices.

### Known issue

- The built-in WebView browser timed out on the Android 14 tablet used for regression testing.
  Other tools were unaffected.

## [1.3.2] - 2026-11-21

### Fixed

- Expanded Chinese coverage for persistent status labels, dialogs, menus, and secondary pages.
- Refreshed runtime translations after layout changes.

## [1.3.1] - 2026-11-21

### Added

- Runtime multilingual UI with English default, built-in Chinese, and model-generated local packs.
- Language pack generation that translates the UI catalog only.

## [1.3.0] - 2026-11-20

### Added

- Initial CRLM release: local Android APK build chain, source-seed self-evolution, on-device model
  configuration, workspaces, skills, automation, MCP, and device tools.
