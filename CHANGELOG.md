# Release notes

## 0.118.3-admin.2, experimental source prerelease, 2026-10-15

Based on Termux v0.118.3. This prerelease contains source and documentation. No installable APKs are distributed, and this candidate has not been deployed to a physical phone.

- Adds native device-owner policy commands and a private UID 2000 shell worker to the complete Termux source tree.
- Includes the neutral recovery helper and independent watcher, with an optional configured SSH port.
- Adds portable builds with an explicit private signing identity, embedded certificate pin, signer verification, non-debuggable output checks, and source-built plugin tooling.
- Removes the private migration importer, device artifacts, public debug signer, and inherited APK upload workflows from the release tree.
- Adds agent setup, recovery, removal, audit, provenance, and source-export documentation.

Local regression/build evidence is in `docs/release-validation.json`. Limited enrollment, owner/shell, and renamed-helper checks passed on a disposable Android 16 emulator. Complete physical-device setup and reboot testing remain pending. Historical Android 16 phone results apply to the predecessor. Cold recovery requires first unlock and connected Wi-Fi. Existing-phone signer migration and mobile-only cold recovery are outside this candidate's supported workflow.
