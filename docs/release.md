# Release process

The public repository is [skeneilsutton/termux-admin](https://github.com/skeneilsutton/termux-admin). Version `v0.118.3-admin.2` is an experimental source-only prerelease. It includes no APKs. Upstream Termux history is retained through the pinned v0.118.3 baseline; fork changes are committed above that baseline.

## Review and export source

Run `tools/check.py` with JDK 11, inspect `docs/audit.md` and `docs/release-validation.json`, then create a source archive with `tools/package-source.py`. The archive includes a per-file SHA-256 manifest. Verify that its file list excludes keys, APKs, private backups, personal device identifiers, logs, and vendor caches. Review the README, upstream provenance, license text, and all added code.

The original private migration project remains separate. Never publish that directory wholesale. This source supports new installation, with existing-phone migration requiring separate work.

The dated validation receipt describes the local preparation run. Its publication field records the state at that time. The Git tag and GitHub release identify the published source revision.

For a source prerelease, push the reviewed commit, wait for the source-check workflow to pass, tag that commit, and attach the exported source archive and its SHA-256 checksum to a GitHub prerelease. Release notes must state the tested scope and remaining physical-device checks. Do not upload validation APKs or signing material. GitHub's private vulnerability reporting is the fork's security-reporting route, as described in `SECURITY.md`.

`tools/package-source.py` only creates a local archive. No tool or workflow in this repository automatically publishes it.

## Before distributing APKs

- Complete the fresh-device acceptance checks in `setup.md`, including renamed-helper, same-signer plugin, warm failure, and reboot recovery tests on an unrooted Android 16 test device.
- Keep a durable private release signer and verify its certificate before signing. An existing private signer can be retained; generating a new key is unnecessary when the existing identity is appropriate. Preserve it for future updates and back it up securely outside Git.
- Provide signed artifact checksums, certificate fingerprints, exact source revisions, plugin source, and dependency/bootstrap provenance. Review dependency advisories and third-party notices.
- Describe first-unlock/Wi-Fi recovery requirements, the trusted Termux shared UID, excluded archive importer, and outstanding platform limits in the release notes.

The temporary key used for local build validation is not a durable release identity. Do not install those validation APKs over a working phone or distribute them as updates.
