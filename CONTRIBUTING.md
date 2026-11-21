# Contributing to CRLM

Thanks for helping improve CRLM. This project is a local-first Android agent that can modify its
own source and install APKs, so changes need to be reviewed with extra care.

By contributing, you agree that your contribution is licensed under
**AGPL-3.0-only**, the same license as the project. Do not submit code, assets, or text that you do
not have the right to license.

## Before you start

- Read the [Code of Conduct](CODE_OF_CONDUCT.md).
- Do not submit API keys, tokens, passwords, personal data, device identifiers, customer data, or
  source code owned by an employer or another project.
- Keep all source code, comments, issue text, and documentation in English. Translation data belongs
  in `assets/lang/`.
- For security issues, use the private channel in [SECURITY.md](SECURITY.md), not a public issue.

## Development setup

Requirements:

- JDK 17 or newer
- Android SDK with platform 36 and build-tools 36.1.0 (`aapt2`, `d8`, `zipalign`, `apksigner`)
- No Gradle is required

```sh
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./build.sh
```

The CI workflow in `.github/workflows/build.yml` performs the same Lite build with a generated
debug key. Release signing is a separate maintainer step; see
[docs/RELEASING.md](docs/RELEASING.md).

## Project conventions

- Java source level is 8; do not introduce AndroidX, Gradle, or new runtime dependencies without a
  clear need.
- Keep the package name and the `/sdcard/MobileAgent` workspace paths stable unless the change
  includes a migration plan.
- Keep the JNI method names in `src/org/mobileagent/agent/LocalLlm.java` and
  `native/local_llm.cpp` in sync.
- UI strings must be user-visible and added to the catalog when they should be translatable. Do not
  translate code comments or model prompts.
- Do not commit APKs, keystores, bootstrap archives, local models, generated build outputs, or
  runtime data. Use `.gitignore` and check `git status` before committing.
- If you change the self-build signing workflow, preserve the rule that the toolchain keystore in
  the bootstrap matches the installed APK's signing certificate. Otherwise self-built APKs cannot
  update the app.

## Tests and verification

There is no Gradle test runner. At minimum:

1. Build with `./build.sh`.
2. Run the relevant checks in `test-src/` with your Android classpath.
3. On a test device, verify the affected flow, including permissions and one self-build/install
   cycle when the change touches the agent, build, or signing paths.
4. Remove or redact API keys, personal data, and local paths from logs and screenshots before
   posting them.

Do not claim a test passed if it was not run. State clearly what is untested.

## Pull requests

- Keep the change focused; describe the problem, the approach, and the verification.
- Use English commit messages and PR text.
- Reference the related issue when one exists.
- Confirm that no secrets, personal data, company source, or third-party code without a compatible
  license are included.
- Maintainers may ask for a smaller change or a security review.

## Translations

- English is the source language.
- The built-in Chinese pack is data, not source code.
- New UI strings should be added to `assets/lang/en-catalog.json`; language-pack generation uses
  that catalog.
- Do not add company names, personal data, or culture-specific product examples to the catalog.
