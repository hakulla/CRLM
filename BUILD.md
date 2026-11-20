# Build

## Requirements

- JDK 17 or newer (`javac`, `java`, `keytool`)
- Android SDK build-tools with `aapt2`, `d8`, `zipalign`, `apksigner`
- Android platform `android.jar`
- For native rebuilds only: Android NDK + CMake + a compatible llama.cpp source tree

No Gradle is used. The build sequence is resources → javac → d8 → package → sign.

## Desktop / macOS

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME=$HOME/Library/Android/sdk
./build.sh
```

Output: `build/MobileAgent.apk`. The script creates a debug keystore on first run if none is supplied.
Do not commit a keystore.

## Termux

`build-phone.sh` expects the Android build toolchain under `/sdcard/MobileAgent/toolchain`.
It builds in Termux's private ext4 directory, then writes `app.apk` next to the project.

## Project layout

```text
src/org/mobileagent/agent/   Java sources
test-src/                    standalone test sources
res/                         resources
native/                      JNI wrapper and CMake description
deps/                        JavaParser jar and its license
lib/arm64-v8a/               prebuilt ARM64 runtime
```

## Native libraries

The repository ships prebuilt ARM64 libraries for the JNI bridge. If you rebuild them, keep the
JNI method names in `src/org/mobileagent/agent/LocalLlm.java` and `native/local_llm.cpp` in sync.
The prebuilt `liblocal_llm.so` implements the complete current JNI contract; the included C++
wrapper is the core generation implementation and may need additional native methods for a full
from-source rebuild.

Development-only probe libraries are intentionally not part of this tree.

## Runtime paths

The app uses `/sdcard/MobileAgent` as its workspace and bridge root. Keep that path when changing
the package or rebuilding the bridge integration.

## Bootstrap variants

- `./build.sh` builds the Lite APK. It contains `assets/source-seed.zip` but no build environment.
  On first launch it downloads `bootstrap.zip` from the latest public Release.
- `BOOTSTRAP_ZIP=/path/to/bootstrap.zip ./build.sh` builds the Offline APK. The archive is copied
  to `assets/bootstrap.zip` and extracted on first launch; no network is required.

The bootstrap archive must contain:

```text
rootfs/rootfs.tar.gz
toolchain/android.jar
toolchain/d8.jar
toolchain/apksigner.jar
toolchain/crlm-debug.keystore
```

`BootstrapInstaller` writes them to `/sdcard/MobileAgent/`. The debug keystore is only for
self-built debug APKs; do not reuse it for a public release.
