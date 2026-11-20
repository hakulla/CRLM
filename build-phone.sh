#!/data/data/com.termux/files/usr/bin/bash
# Local construction of mobile phones: intermediates placed Termux Private ext4 Directory, Avoid /sdcard FUSE Slow down.
set -e

export PATH=/data/data/com.termux/files/usr/bin:$PATH
P="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
T=/sdcard/MobileAgent/toolchain
B="$HOME/.agent-build/$(basename "$P")"
LOCK="$B.lock"
mkdir -p "$HOME/.agent-build"

for tool in java javac aapt2 zip; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "MISSING_TOOL=$tool"
    exit 91
  fi
done

mkdir -p "${LOCK%/*}" 2>/dev/null
if ! mkdir "$LOCK" 2>/dev/null; then
  MT=$(stat -c %Y "$LOCK" 2>/dev/null)
  if [ -n "$MT" ]; then AGE=$(( $(date +%s) - MT )); else AGE=0; fi
  if [ -n "$MT" ] && [ "$AGE" -gt 600 ]; then
    # A build killed by SIGKILL cannot run its trap; clear the leftover lock.
    echo "[build] Clear the residual build lock ($AGE Stays a second ago."
    rm -rf "$LOCK"
    mkdir "$LOCK" 2>/dev/null || { echo "BUILD_FAILED stage=lock: Could not create build lock"; exit 92; }
  else
    echo "BUILD_FAILED stage=lock: Another construction is under way ($LOCK)"
    exit 92
  fi
fi
ok=0
stage=init
cleanup() {
  code=$?
  rm -rf "$LOCK"
  if [ "$ok" != 1 ]; then
    echo "BUILD_FAILED stage=$stage exit=$code"
  fi
}
trap cleanup EXIT

rm -rf "$B"
mkdir -p "$B/classes" "$B/dex" "$B/src" "$B/gen" "$B/assets"
cp -r "$P/src/." "$B/src/"
cp "$P/AndroidManifest.xml" "$B/"
cp -r "$P/res" "$B/res"

# The APK carries its own source seed; a fresh install can rebuild itself offline.
(cd "$P" && zip -qr "$B/assets/source-seed.zip" . \
  -x 'build/*' 'app.apk' '*.idsig' 'debug.keystore' '.git/*' 'assets/source-seed.zip' 'bootstrap.zip')

# Resources link first: R.java comes from aapt2, so javac must not run before it.
stage=resources
echo "[stage] resources"
S=$(date +%s)
aapt2 compile --dir "$B/res" -o "$B/res.zip"
aapt2 link -o "$B/unsigned.apk" -I "$T/android.jar" \
  --manifest "$B/AndroidManifest.xml" --auto-add-overlay \
  -A "$B/assets" --java "$B/gen" "$B/res.zip"
echo "[build] resources $(( $(date +%s) - S ))s"

stage=javac
echo "[stage] javac"
S=$(date +%s)
javac -nowarn -encoding UTF-8 -source 8 -target 8 -classpath "$T/android.jar:$P/deps/javaparser-core-3.26.2.jar" \
  -d "$B/classes" $(find "$B/src" "$B/gen" -name '*.java')
echo "[build] javac $(( $(date +%s) - S ))s"

stage=d8
echo "[stage] d8"
S=$(date +%s)
java -cp "$T/d8.jar" com.android.tools.r8.D8 \
  --lib "$T/android.jar" --min-api 24 --output "$B/dex" \
  $(find "$B/classes" -name '*.class') "$P/deps/javaparser-core-3.26.2.jar"
echo "[build] d8 $(( $(date +%s) - S ))s"

stage=package
echo "[stage] package"
cd "$B/dex"
zip -q -u "$B/unsigned.apk" classes.dex
if [ -d "$P/lib" ]; then
  (cd "$P" && zip -q -r "$B/unsigned.apk" lib)
fi
APK="$B/unsigned.apk"
if command -v zipalign >/dev/null 2>&1; then
  zipalign -f 4 "$B/unsigned.apk" "$B/aligned.apk"
  APK="$B/aligned.apk"
else
  echo "[build] zipalign Not installed, Skip Alignment"
fi

stage=sign
echo "[stage] sign"
java -jar "$T/apksigner.jar" sign \
  --ks "$T/crlm-debug.keystore" \
  --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias mobileagent \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$B/app.apk" "$APK"

stage=verify
echo "[stage] verify"
java -jar "$T/apksigner.jar" verify "$B/app.apk" >/dev/null
SIZE=$(stat -c %s "$B/app.apk" 2>/dev/null || wc -c < "$B/app.apk")
if [ "$SIZE" -lt 100000 ]; then
  echo "BUILD_FAILED stage=verify: APK Too small. $SIZE"
  exit 93
fi

stage=manifest
echo "[stage] manifest"
VERSION_MANIFEST=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$B/AndroidManifest.xml" | head -1)
BADGING=$(aapt2 dump badging "$B/app.apk" 2>/dev/null | head -1)
echo "$BADGING"
case "$BADGING" in
  *"versionName='$VERSION_MANIFEST'"*) ;;
  *) echo "BUILD_FAILED stage=manifest: APK Version inconsistent with inventory ($VERSION_MANIFEST)"; exit 94 ;;
esac
DEXCOUNT=$(unzip -l "$B/app.apk" 2>/dev/null | grep -cE 'classes[0-9]*\.dex')
echo "[build] dex_files=$DEXCOUNT"
if [ "$DEXCOUNT" -lt 1 ]; then
  echo "BUILD_FAILED stage=manifest: APK Not in there. classes.dex"
  exit 95
fi
java -jar "$T/apksigner.jar" verify --print-certs "$B/app.apk" 2>/dev/null | head -3 || true
cp "$B/app.apk" "$P/app.apk.tmp"
mv -f "$P/app.apk.tmp" "$P/app.apk"

stage=done
ok=1
echo "BUILD_OK $P/app.apk $SIZE"
