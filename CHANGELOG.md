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

## [1.3.5] - 2026-11-21

### Fixed

- DeepSeek Flash and DeepSeek V4 profiles now infer vision support, 1M context, and 65536 output
  tokens. Existing model profiles migrate once, so image attachments are sent to the model instead
  of being dropped.
- Image requests no longer receive a false "model does not support images" system instruction.

## [1.3.4] - 2026-11-21

### Fixed

- Corrected the `"Call "` prefix parsing that left a stray `l` (rendered like `|`) as every tool
  name in stage headers and step rows.
- Localized dynamic stage/tool/system/Token/question text in Chinese, removed source-tag keys from
  the Chinese pack, and filtered malformed generated entries at runtime.
- Replaced the misleading per-adapter call quota with task-scoped loading and automatic LRU
  eviction.
- Removed the forced final answer at the old two-round budget cutoff; open plans now extend while
  tools are active, with larger context and task budgets.
- Made `zipalign` explicitly optional for `build_apk` and prevented `pm`/`cmd package install`
  from being used instead of `install_apk`.

### Changed

- Model defaults now use the application maxima: temperature 2.0, 65536 output tokens, 1M context,
  24 tool rounds, and deep thinking on. Existing conversations migrate once to these values.
- Lite mode now honors the configured output/context maxima instead of clamping to 4k/12k.

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
