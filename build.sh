#!/bin/bash
set -euo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export PATH="$JAVA_HOME/bin:$PATH"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$ANDROID_HOME/build-tools/36.1.0"
ANDROID_JAR="$ANDROID_HOME/platforms/android-36/android.jar"
BUILD="$ROOT/build"
VERSION_CODE=$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
VERSION_NAME=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$ROOT/AndroidManifest.xml" | head -1)
if [ -z "$VERSION_CODE" ] || [ -z "$VERSION_NAME" ]; then
  echo "missing versionCode/versionName in AndroidManifest.xml" >&2
  exit 2
fi

rm -rf "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/assets"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/gen" "$BUILD/assets"
if [ -d "$ROOT/assets" ]; then
  cp -R "$ROOT/assets/." "$BUILD/assets/"
fi
rm -f "$BUILD/res.zip" "$BUILD/base.apk" "$BUILD/aligned.apk" \
  "$BUILD/MobileAgent.apk" "$BUILD/MobileAgent.apk.idsig"

# Ship the app's own source as a seed so a fresh install can edit and rebuild itself.
(cd "$ROOT" && zip -qr "$BUILD/assets/source-seed.zip" . \
  -x 'build/*' 'app.apk' 'app.apk.tmp' '*.keystore' '*.idsig' '.git/*' \
  'assets/source-seed.zip' 'bootstrap.zip' 'assets/bootstrap.zip' 'toolchain-mac/*')
if [ -n "${BOOTSTRAP_ZIP:-}" ]; then
  test -f "$BOOTSTRAP_ZIP" || { echo "missing BOOTSTRAP_ZIP=$BOOTSTRAP_ZIP" >&2; exit 2; }
  cp "$BOOTSTRAP_ZIP" "$BUILD/assets/bootstrap.zip"
fi

"$BT/aapt2" compile --dir "$ROOT/res" -o "$BUILD/res.zip"
"$BT/aapt2" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" \
  --manifest "$ROOT/AndroidManifest.xml" --auto-add-overlay \
  -A "$BUILD/assets" \
  --java "$BUILD/gen" \
  --min-sdk-version 24 \
  --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" "$BUILD/res.zip"

javac -encoding UTF-8 -source 8 -target 8 \
  -classpath "$ANDROID_JAR:$ROOT/deps/javaparser-core-3.26.2.jar" \
  -d "$BUILD/classes" $(find "$ROOT/src" "$BUILD/gen" -name '*.java' -print)

"$BT/d8" --lib "$ANDROID_JAR" --min-api 24 --output "$BUILD/dex" \
  $(find "$BUILD/classes" -name '*.class' -print) \
  "$ROOT/deps/javaparser-core-3.26.2.jar"
zip -q -j -u "$BUILD/base.apk" "$BUILD/dex/classes.dex"
if [ -d "$ROOT/lib" ]; then
  (cd "$ROOT" && zip -q -r "$BUILD/base.apk" lib)
fi
"$BT/zipalign" -f 4 "$BUILD/base.apk" "$BUILD/aligned.apk"

KEYSTORE="$ROOT/debug.keystore"
for candidate in "$ROOT/toolchain-mac/crlm-debug.keystore" "$ROOT/crlm-debug.keystore"; do
  if [ -f "$candidate" ]; then
    KEYSTORE="$candidate"
    break
  fi
done
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair -keystore "$KEYSTORE" \
    -storepass android -keypass android -alias crlm \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=CRLM Debug, OU=Debug, O=CRLM, L=Local, ST=Local, C=CN"
  chmod 600 "$KEYSTORE"
fi
ALIAS="${CRLM_KEY_ALIAS:-crlm}"
if ! keytool -list -alias "$ALIAS" -keystore "$KEYSTORE" -storepass android >/dev/null 2>&1; then
  if keytool -list -alias mobileagent -keystore "$KEYSTORE" -storepass android >/dev/null 2>&1; then
    ALIAS=mobileagent
  fi
fi

"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-key-alias "$ALIAS" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD/MobileAgent.apk" "$BUILD/aligned.apk"
"$BT/zipalign" -c 4 "$BUILD/MobileAgent.apk"
"$BT/apksigner" verify --verbose "$BUILD/MobileAgent.apk"
