# CRLM

**Craft · Run · Learn · Mutate - turn an Android phone into an agent that can build its own apps.**

No desktop. No cloud build queue. Describe the app you want, and the agent reads its own source,
edits it, runs the local Android build chain, installs the APK, restarts, and keeps iterating.
It is not just a chat assistant: it is a pocket workshop that turns ideas into runnable Android
apps and continuously improves itself.

## Highlights

### 1. Build Android APKs on the phone

- Self-contained toolchain: `aapt2` → `javac` → `d8` → `zip` → `zipalign` → `apksigner`.
- Compile, sign, and install inside the phone workspace. No desktop, Gradle, or Android Studio.
- Good for small utilities, prototypes, UI experiments, automation tools, and standalone apps.
- The agent can read and write project files, run shell commands, invoke the build tools, and
  react to the install result.

### 2. Self-evolution

- On first launch, the app copies its own source into
  `/sdcard/MobileAgent/projects/mobile-agent`.
- The agent can edit that source, rebuild the next APK, install it, and restart itself.
- Builds leave version, backup, and rollback clues; failures can be inspected, fixed, and retried.
- It can change not only settings, but also its UI, tools, prompts, workflows, and build process.

### 3. Multilingual UI

- English is the default UI language.
- A built-in Chinese UI pack is available from Settings → Language.
- After a model is configured, choose **Add language with model...**, enter a language name,
  and CRLM translates the user-interface catalog and saves the pack locally.
- Generated packs translate user-visible UI strings only. Source-code comments and model prompts
  are never sent to the language-pack builder.
- Packs are stored in `/sdcard/MobileAgent/languages/`.

### 4. Deep customization

- **Tools:** files, shell, screen reading, accessibility actions, browser, screenshots, and
  package installation, enabled only when needed.
- **Skills and rules:** reusable procedures, rules, and checklists for repeated work.
- **Models:** local GGUF models or any OpenAI-compatible endpoint. Base URL, model name, and key
  are configured on the device.
- **Workspaces:** keep multiple projects in separate directories.
- **UI:** native Android views that the agent can continue to reshape.

### 5. Local-first

- The build toolchain, source, and most state live on the phone.
- Local models run fully offline; cloud models are used only after you configure them.
- API keys, device passwords, and debug tokens are not stored in the repository.

## What it has built

These are the kinds of apps that have been built, iterated, and installed on the phone:

- **GardenAir**: an environment dashboard with sensor readings, trends, care suggestions, and
  history.
- **Image Cropper**: a custom crop tool with preview and image export.
- **Screen Ruler**: an on-screen measurement tool for UI alignment and size checks.
- **Visual experiments and small games**: built with native Views and Canvas.
- **Automation utilities**: scheduled tasks, file cleanup, notification handling, and cross-app
  actions.

These examples show that CRLM can produce real apps with UI, state, interaction, and long-term
maintenance, not only a "Hello World".

## Installation: choose Lite or Offline

Releases provide two APKs with the same features. They differ only in how the build environment is
delivered:

| Build | Size | First launch | Best for |
| --- | --- | --- | --- |
| **Lite** | ~8 MB | Downloads a ~150 MB `bootstrap.zip` and extracts it automatically | Convenient networks and smaller downloads |
| **Offline** | ~150 MB | Build environment is bundled; no download | Offline use, unstable networks, or fresh devices |

Both variants seed the source into
`/sdcard/MobileAgent/projects/mobile-agent` on first launch. The seed is initialized once and does
not overwrite an existing edited source tree.

Lite downloads from the public Release. The repository is currently **Private**, so use Offline
for now; Lite becomes fully automatic after the repository is public.

### After installing

1. Grant file/all-files access, install-unknown-apps, accessibility, notifications, and camera
   permissions as needed.
2. Configure your model base URL, model name, and API key in Settings.
3. For local models, place GGUF files in `/sdcard/MobileAgent/models/` and select one in the app.
4. After permissions are granted, the app prepares `rootfs`, `toolchain`, and source automatically.

> The build environment and source are handled automatically. Model keys are never bundled.

## First self-built app

Describe the goal directly in chat. For example:

```text
Build a "daily water" utility:
- The home screen shows how many cups I drank today.
- Tap +1 to add a cup; long-press to reset.
- Persist history locally.
- Build the APK and install it on this phone.
```

Or iterate on an existing project:

```text
Add a "weekly trend" page to GardenAir. Keep the existing colors and data model.
When done, build, sign, install, and bump the version.
```

The self-evolution loop:

```text
read source → edit/generate code → bump version → build APK locally
→ install the new APK → restart → read logs → fix → build again
```

## Build from source

The repository does not use Gradle. You need a JDK, Android SDK build-tools, and an Android
platform `android.jar`.

```sh
./build.sh                                   # Lite (no build environment)
BOOTSTRAP_ZIP=/path/bootstrap.zip ./build.sh # Offline (build environment bundled)
./build-phone.sh                             # Termux with the local toolchain
```

The build packages the current source as `assets/source-seed.zip` inside the APK, so a fresh
install still receives its own source. See [BUILD.md](BUILD.md) for details.

## Security boundaries

CRLM can run shell commands, modify files, install APKs, and operate the UI. Use it only on your
own device. Do not execute instructions, scripts, or packages from untrusted sources. It does not
gain root, bypass the system installer, or bypass the lock screen. See [SECURITY.md](SECURITY.md).

## Third-party components

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Third-party licenses and attribution must be
preserved.
