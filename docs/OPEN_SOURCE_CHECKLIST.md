# Open-source readiness checklist

This checklist records the repository-level preparation for a public release. It is a maintainer
aid, not legal advice.

## Repository and documentation

- [x] Project license selected and added: AGPL-3.0-only
- [x] NOTICE, third-party notices, and bundled license texts
- [x] README with features, install, build, security, privacy, support, and license sections
- [x] BUILD, ARCHITECTURE, RELEASING, SECURITY, PRIVACY, CHANGELOG
- [x] CONTRIBUTING and CODE_OF_CONDUCT
- [x] Issue templates, vulnerability report form, private security guidance, and PR template
- [x] CI workflow active under `.github/workflows/` and passing on `main`
- [x] Private vulnerability reporting enabled on the public repository
- [x] Repository description and topics
- [x] Secret/device-identifier scan of the current tree, history, and release assets
- [x] Generated binaries and keystores excluded from Git
- [x] Release assets, source archive, and SHA256SUMS

## Maintainer sign-off

- [x] Maintainer confirms this is not a company work product and no employer-owned code is included.
- [x] Old v1.3.1 Release and tag removed.
- [x] Old commit history rewritten to remove pre-cleanup User-Agent and README example.
- [ ] Review GPL/LGPL source-offer obligations for the Termux packages in the Offline bootstrap.
- [ ] Confirm that the bundled debug keystore is acceptable for the intended distribution model.
- [x] Repository made public under AGPL-3.0-only.
