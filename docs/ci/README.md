# CI

The GitHub Actions workflow is active at `.github/workflows/build.yml`. It builds the Lite APK on
Ubuntu with JDK 17, Android platform 36, and build-tools 36.1.0, then uploads the APK as a workflow
artifact.

`build.yml.example` is kept as a reference copy for users who need to recreate the workflow. GitHub
requires a credential with the `workflow` scope to change files under `.github/workflows/`.
