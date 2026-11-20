# Security

## Capability surface

Mobile Agent can read and write workspace files, execute shell commands, build and install APKs,
read screen content, simulate taps/input, and access the network. These are real device capabilities,
so the following boundaries are part of the design:

- Device control is opt-in. Build/install commands and system installer confirmations remain under
  user control.
- The local redaction pass runs before model requests; turning off the UI confirmation does not
  remove that fallback.
- The app never receives root, does not bypass the system installer, and does not bypass the lock
  screen.
- The optional runtime filesystem is app-private. `targetSdk 28` is used to allow execution of
  binaries inside the app's own directory.
- API keys, account credentials, device passwords, and signing keys must never be committed.

## Known risks

1. **Prompt injection**: web pages, messages, and files may contain instructions that a model
   treats as commands. The agent has real local side effects.
2. **Irreversible operations**: deleting or overwriting files, uninstalling or replacing
   applications, and editing the app itself can make the app unusable.
3. **Model data flow**: if screen reading or vision is enabled, screen text and screenshots may be
   sent to the configured model provider.
4. **Repository hygiene**: APKs, keystores, tokens, personal links, and local runtime data must not
   enter the repository.

## Reporting

Report vulnerabilities through a private channel provided by the repository maintainer, not a
public issue. Include the app version, Android version, reproduction steps, and a minimal
diagnostic excerpt with personal data removed.
