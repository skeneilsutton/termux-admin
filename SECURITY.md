# Security policy

Termux Admin is experimental. The latest source prerelease is the review target; there is no supported binary distribution or response-time guarantee. See `docs/audit.md` for the review scope and unresolved limitations.

## Report a vulnerability

Use [GitHub's private vulnerability reporting](https://github.com/skeneilsutton/termux-admin/security/advisories/new) for this fork. Include the affected source revision, Android version, required privileges, reproduction steps, and observed impact. Remove signing keys, pairing secrets, tokens, and personal device data from reports.

Do not publish exploit details in an ordinary issue while a private report is being reviewed. Fork vulnerabilities should be reported to this repository, not upstream Termux.

Code running inside Termux and its signed shared UID has administrator access by design. Device ownership and the UID 2000 worker do not provide root. Recovery still depends on Android power management, first unlock, and network conditions described in the setup and recovery guides.
