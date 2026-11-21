# Security

## Supported versions

| Version | Supported |
| --- | --- |
| 1.3.x | Yes |
| Older versions | No; upgrade to the latest release |

## Capability surface

CRLM can read and write workspace files, execute shell commands, build and install APKs, read screen
content, simulate taps/input, access the camera when a camera feature is opened, and access the
network. These are real device capabilities, so the following boundaries are part of the design:

- Device control is opt-in. Build/install commands and system installer confirmations remain under
  user control.
- The local redaction pass runs before model requests; turning off the UI confirmation does not
  remove that fallback.
- The app never receives root, does not bypass the system installer, and does not bypass the lock
  screen.
- The optional runtime filesystem is app-private. `targetSdk 28` is used to allow execution of
  binaries inside the app's own directory; this is a compatibility trade-off, not a security
  guarantee.
- API keys, account credentials, device passwords, and signing keys must never be committed.
- The Offline bootstrap contains a public debug keystore used for self-built updates. It is not a
  production key. Anyone with that keystore can sign an APK for the same package, which is why it
  must not be used as a production identity.

## Threat model and known risks

1. **Prompt injection**: web pages, messages, and files may contain instructions that a model treats
   as commands. The agent has real local side effects.
2. **Irreversible operations**: deleting or overwriting files, uninstalling or replacing
   applications, and editing the app itself can make the app unusable.
3. **Model data flow**: if screen reading or vision is enabled, screen text and screenshots may be
   sent to the configured model provider or MCP server.
4. **Repository hygiene**: APKs, keystores, tokens, personal links, device identifiers, and local
   runtime data must not enter the repository.
5. **Android restrictions**: Android 13+ may require "Allow restricted settings" before a sideloaded
   accessibility service can be enabled. Reinstalling or self-updating can clear accessibility and
   app-op grants.
6. **Known functional limitation**: the built-in WebView browser timed out in the 1.3.3 Android 14
   tablet regression. Do not rely on it for security-sensitive browsing.

## Reporting a vulnerability

Private vulnerability reporting is available only for public repositories. Once the repository is
public, maintainers should enable it in **Settings → Security and quality → Advanced Security**.
The repository includes a custom [vulnerability report form](.github/VULNERABILITY_REPORT.yml).
While the repository is private, contact the maintainer privately through GitHub instead of opening
a public issue.

Include:

- App version and build variant (Lite or Offline)
- Android version and device model (only if necessary)
- Reproduction steps and a minimal proof of concept
- The affected component and potential impact
- A diagnostic excerpt with API keys, tokens, personal data, and company information removed

Reports will be acknowledged as soon as possible. Please allow time for a fix and coordinated
disclosure before publishing details.

## Out of scope

- Attacks that require a rooted, compromised, or physically unlocked device
- Vulnerabilities in third-party model providers, MCP servers, web pages, or upstream packages
- Misuse of the deliberately public debug keystore as a production signing identity
- Issues caused by granting broad permissions to untrusted instructions or content

## Release verification

Release assets include `CRLM-<version>-SHA256SUMS.txt`. Verify downloads before installing. The
source archive and repository are available for inspection under AGPL-3.0-only.
