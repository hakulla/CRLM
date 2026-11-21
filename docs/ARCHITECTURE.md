# Architecture

CRLM is a native Android application that keeps its own source, build tools, and run state on the
device. It is intentionally not a thin client: the agent loop, file access, shell, APK build, and
installation all happen locally.

## Main components

| Component | Responsibility |
| --- | --- |
| `MainActivity` | Chat UI, settings, workspaces, permissions, language selection, and task controls. |
| `AgentEngine` | Model loop, tool dispatch, planning, approval policy, and run lifecycle. |
| `ToolRegistry` / `ToolDefs` | Tool schemas, effects, validation, and execution bindings. |
| `Capabilities` / `CapabilitiesPage` | Skills, rules, knowledge, MCP, automation, and device settings. |
| `LlmClient` / `LocalLlm` | OpenAI-compatible chat and local GGUF inference. |
| `McpManager` / `McpDirectory` / `McpTransport` | Streamable HTTP MCP discovery, schema cache, and calls. |
| `Workspace` | Resolves the shared `/sdcard/MobileAgent` tree and labelled workspaces. |
| `Rootfs` / `BootstrapInstaller` | App-private Linux userland and optional build toolchain. |
| `SourceBootstrap` | Extracts the app's source seed into `projects/mobile-agent`. |
| `AutomationStore` / `AutomationService` | Scheduled agent runs and history. |
| `LanguageManager` | Runtime UI catalog translation and language packs. |
| `LocalConfig` | Non-secret settings mirror with startup restore. |
| `DebugBridge` | Opt-in local JSON debug channel; disabled by default. |

## Runtime flow

1. The app starts, applies the selected language, and prepares the workspace.
2. `SourceBootstrap` seeds the source if `projects/mobile-agent` is missing.
3. `BootstrapInstaller` unpacks `rootfs`/`toolchain` from the Offline APK or downloads
   `bootstrap.zip` for the Lite build.
4. `Rootfs` unpacks the userland into app-private storage and probes whether execution is allowed.
5. The agent builds a task-scoped tool set, optionally loads MCP tools, and runs the model loop.
6. Tool results are written to the conversation and, when requested, to the workspace.
7. For a self-build, the build runs in the app-private `.agent-build` directory, signs the APK, and
   returns the artifact path for `install_apk`.

## Self-build pipeline

`buildApk` creates an app-private build directory and runs:

```text
copy source + manifest + res + assets
→ generate assets/source-seed.zip from the project
→ aapt2 compile/link with -A assets
→ javac
→ d8
→ zip classes.dex and lib/
→ optional zipalign
→ apksigner
→ verify manifest, classes.dex, source seed, and language pack
```

The resulting APK is copied to `app.apk` in the project directory. Signing uses the toolchain
keystore. For self-updates, the toolchain certificate must match the installed APK's certificate.

## Data layout

```text
/sdcard/MobileAgent/
  projects/                  Android projects
  projects/mobile-agent/     source seed / self-evolution project
  models/                    GGUF models
  languages/                 generated UI language packs
  screenshots/               screenshots and browser captures
  config/                    non-secret settings mirror
  agent-bridge/              optional bridge inbox/outbox/debug state
  rootfs/                    bootstrap rootfs archive
  toolchain/                 build tools and signing keystore
  .agent/                    skills, rules, knowledge, checkpoints, logs
```

App-private storage holds API keys, model service credentials, install passwords, and the debug
token. Secrets are intentionally excluded from the workspace mirror and repository.

## Model and network boundaries

- Cloud models are OpenAI-compatible endpoints configured by the user.
- Local GGUF models run through `liblocal_llm.so` and never require a network.
- MCP servers are user-configured Streamable HTTP endpoints; tools are discovered on demand.
- Web fetch/search, image search, package downloads, and bootstrap downloads use the network only
  when the matching tool or feature is used.
- The app does not include analytics or telemetry.

## Security boundaries

Device control, screen capture, camera, installation, and shell execution are explicit user
capabilities. The app does not gain root, bypass the system installer, or bypass the lock screen.
Prompt injection and destructive actions remain real risks; use the app on your own device and with
trusted content only. See [SECURITY.md](../SECURITY.md).

## Build outputs

- `build/MobileAgent.apk` - Lite or Offline APK from `build.sh`.
- `assets/source-seed.zip` inside the APK - source snapshot for fresh installs.
- `bootstrap.zip` - rootfs and toolchain for Lite or recovery.
- Release `source.zip` - the source used for the release binaries.
