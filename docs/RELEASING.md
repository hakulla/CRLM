# Releasing

CRLM releases are built manually because release artifacts contain a self-build toolchain and a
signing key. Do not put private production keys in the repository or in CI.

## Prerequisites

- JDK 17 or newer
- Android SDK platform 36 and build-tools 36.1.0
- The release bootstrap archive
- A clean checkout and a clean `git status`

## Version and build

1. Update `android:versionCode` and `android:versionName` in `AndroidManifest.xml`.
2. Build the Lite APK:

   ```sh
   ./build.sh
   ```

3. Build the Offline APK:

   ```sh
   BOOTSTRAP_ZIP=/path/to/bootstrap.zip ./build.sh
   ```

4. Verify both APKs:

   ```sh
   apksigner verify --print-certs build/MobileAgent.apk
   aapt2 dump badging build/MobileAgent.apk
   unzip -l build/MobileAgent.apk | grep -E 'assets/(source-seed.zip|lang/zh.json|bootstrap.zip)'
   ```

5. Confirm the APK signing certificate matches the `crlm-debug.keystore` in the bootstrap
   toolchain. A mismatch makes self-built APKs unable to update the installed app.

## Source and checksums

Create a source archive from the release commit without local secrets or build products:

```sh
zip -qr CRLM-<version>-source.zip . \
  -x 'build/*' '*.apk' '*.idsig' '*.keystore' '.git/*' 'toolchain-mac/*' \
  'bootstrap.zip' 'assets/source-seed.zip' 'app.apk'
```

Generate checksums for the APKs, bootstrap, and source archive:

```sh
shasum -a 256 CRLM-<version>-lite.apk CRLM-<version>-offline.apk \
  CRLM-<version>-source.zip bootstrap.zip > CRLM-<version>-SHA256SUMS.txt
```

## Release checklist

- [ ] Version numbers match the manifest and release title.
- [ ] Lite and Offline APKs build from a clean checkout.
- [ ] APK signature matches the bootstrap toolchain key.
- [ ] `assets/source-seed.zip` is present and contains the current source.
- [ ] No API keys, tokens, personal data, device identifiers, or company source are present.
- [ ] Third-party notices and license texts are current.
- [ ] A fresh-device smoke test covers permissions, model setup, self-build, and self-install.
- [ ] SHA256SUMS matches every uploaded asset.
- [ ] AGPL source availability is satisfied: source archive and repository are available to users.
- [ ] Release notes list known limitations and the debug signing key caveat.

## Publish

```sh
git tag v<version>
git push origin v<version>
gh release create v<version> --title "CRLM <version>" --notes-file release-notes.md --latest \
  CRLM-<version>-lite.apk CRLM-<version>-offline.apk \
  CRLM-<version>-source.zip bootstrap.zip CRLM-<version>-SHA256SUMS.txt
```

Do not make the repository public until the copyright/employment ownership review is complete and
the project license has been approved by the rights holder.
