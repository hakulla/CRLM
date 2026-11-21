# Open-source readiness checklist

This checklist records the repository-level preparation for a public release. It is a maintainer
aid, not legal advice.

## Repository and documentation

- [x] Project license selected and added: AGPL-3.0-only
- [x] NOTICE, third-party notices, and bundled license texts
- [x] README with features, install, build, security, privacy, support, and license sections
- [x] BUILD, ARCHITECTURE, RELEASING, SECURITY, PRIVACY, CHANGELOG
- [x] CONTRIBUTING and CODE_OF_CONDUCT
- [x] Issue templates, private security contact, and PR template
- [x] CI build workflow prepared in `docs/ci/build.yml.example`
- [ ] Activate the CI workflow under `.github/workflows/` with a workflow-scoped credential
- [ ] Enable private vulnerability reporting in GitHub after the repository is public
- [x] Repository description and topics
- [x] Secret/device-identifier scan of the current tree, history, and release assets
- [x] Generated binaries and keystores excluded from Git
- [x] Release assets, source archive, and SHA256SUMS

## Maintainer sign-off

- [ ] Confirm that the employment/contract terms allow publishing this code under AGPL-3.0-only
      and that the employer does not claim ownership of the contributed work.
- [ ] Decide whether to delete or retain the old v1.3.1 release and old commit history that contain
      the pre-cleanup User-Agent and README example.
- [ ] Review GPL/LGPL source-offer obligations for the Termux packages in the Offline bootstrap.
- [ ] Confirm that the bundled debug keystore is acceptable for the intended distribution model.
- [ ] Make the repository public only after the ownership review is complete.
