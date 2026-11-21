# CI

`build.yml.example` is a ready-to-use GitHub Actions workflow for the Lite APK. GitHub requires a
credential with the `workflow` scope to create or update files under `.github/workflows/`.

To activate it:

1. Copy `docs/ci/build.yml.example` to `.github/workflows/build.yml`.
2. Push with a GitHub token or SSH key that has permission to update workflows.
3. Confirm the **Build** workflow succeeds in the repository's Actions tab.

Until then, the workflow is intentionally kept outside `.github/workflows/` so it cannot run or
be rejected by a token without the workflow scope.
