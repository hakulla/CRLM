# Privacy

CRLM is designed to run locally on the user's own Android device. This document describes the data
the app can access and where it goes. It is a project document, not legal advice.

## Summary

- No analytics, advertising, or telemetry is built into CRLM.
- Files, conversations, models, and settings stay on the device by default.
- Network requests happen only when the user configures a model service, enables web/MCP tools, or
  downloads a release/bootstrap.
- API keys and install passwords are stored in app-private storage and are not written to the
  workspace mirror, repository, logs, or release artifacts.

## Data the app may access

Depending on the permissions and tools the user enables:

- **Workspace:** `/sdcard/MobileAgent` contains projects, source, models, language packs,
  screenshots, and task data.
- **Screen and accessibility:** the current screen tree and screenshots when screen reading or
  device control is enabled.
- **Camera:** only when the user opens a camera-related feature.
- **Files:** files the user selects or the agent is allowed to read/write.
- **Network:** model requests, MCP requests, web fetch/search, image search, and package downloads.

## Where data is stored

- App-private preferences: API keys, model service entries, install passwords, and the debug-bridge
  token.
- Shared workspace: projects, source, conversations, models, language packs, screenshots, and
  logs.
- Android system storage: installed APKs, WebView data, and OS caches.

The debug bridge, when enabled, writes a token and status file under
`/sdcard/MobileAgent/agent-bridge/debug/`. Disabling it deletes the token and state. Do not leave it
enabled on a shared device.

## Network destinations

CRLM does not choose a model provider for the user. When configured, prompts, files, screen text,
or images may be sent to the provider or MCP server selected by the user. Those services have their
own privacy and retention terms. Local GGUF models do not send data off the device.

Lite builds download the bootstrap from the project's GitHub Release. Package installation and
web/MCP tools may contact the destinations the user specifies.

## Retention and deletion

- Delete conversations, projects, models, language packs, screenshots, and workspace files from the
  app or the file system.
- Uninstalling the app removes app-private preferences and toolchain files; the shared
  `/sdcard/MobileAgent` workspace may remain until deleted.
- Disable the debug bridge to delete its token and state.
- Screen capture is session-based; stop it when finished.

## Security

Local data is protected by the Android app sandbox and the device's own encryption and lock screen.
CRLM does not add a separate encryption layer for workspace files. Do not use the app on an
untrusted device or share a workspace containing secrets.

## Children

CRLM is a developer tool with shell, file, and device-control capabilities. It is not directed at
children and should not be used by children without adult supervision.

## Changes

Material privacy changes will be documented in [CHANGELOG.md](CHANGELOG.md). For questions or
concerns, open a GitHub issue without including personal data or credentials.
