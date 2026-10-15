# Termux Admin

An experimental Termux fork with device-owner controls and a private Android shell worker. This fork is independent of the Termux project.

This is a source-only prerelease. Build and limited Android 16 emulator checks passed. Complete setup and recovery testing on an unrooted physical phone remain pending. See [releases](https://github.com/skeneilsutton/termux-admin/releases) for source archives and checksums.

```sh
~/bin/termux-admin status
~/bin/termux-admin shell -- /system/bin/id -u
```

The shell command returns `2000` when the worker is ready. Ordinary Termux commands still run as the app UID. Device ownership provides Android policy controls. Neither identity provides root.

## Start here

Give your setup agent this repository and [AGENTS.md](AGENTS.md). The agent should read [setup](docs/setup.md), [build and signing](docs/build.md), and [recovery and removal](docs/recovery.md) before changing the phone. A person still needs to unlock the device, approve debugging, and handle Android's enrollment requirements.

This source includes the native module, the recovery helper, recovery scripts, and build tools. It does not include signing keys, APKs, phone backups, private migration tools, or an archive importer. Existing Termux installations with a different signer need a separate migration plan.

## What it does

- Changes selected device-owner policies with readback, including app suspension, hiding, uninstall protection, camera, screenshots, and backup service.
- Runs Android shell commands with separate binary stdin, stdout, stderr, and child exit codes. Commands run once. An uncertain result never triggers a replay.
- Recovers the worker, SSH listener, and Termux host through an independent UID 2000 watcher.
- Bootstraps privileged recovery using a paired phone-local TLS ADB client and a temporary wireless-debugging lease. The ADB control server uses a private filesystem socket.

Code running inside Termux and its signed shared UID can use these powers. Only install trusted packages and scripts. Keep `allow-external-apps` disabled unless you intend to grant permitted external callers this access.

## Evidence and limits

The predecessor build was tested on a Samsung Galaxy A16, SM-A165M, running Android 16, and an Android 16 emulator. Those tests covered ownership, binary streams, refusal paths, warm recovery, and reboot recovery after first unlock and connected Wi-Fi. They do not establish support for other Android versions or vendors.

This release preparation changes the recovery helper's package name, certificate configuration, and maintenance entry point. Local Android 16 emulator checks passed enrollment, native owner/shell commands, and the renamed helper's control requests. The complete recovery workflow still needs testing on a physical phone. See [the audit](docs/audit.md) for the fixture and remaining checks. Cold recovery still requires first unlock and briefly connected Wi-Fi. Mobile-only cold recovery remains unresolved. Cancellation covers the direct child, without a guarantee for all descendants or interactive PTYs.

## Documentation

| Document | Purpose |
| --- | --- |
| [Build](docs/build.md) | Toolchains, private signing identity, core/helper/plugin builds |
| [Setup](docs/setup.md) | Agent workflow, enrollment, SSH, pairing, and acceptance checks |
| [Commands](docs/commands.md) | CLI syntax, policy readback, and uncertain outcomes |
| [Recovery](docs/recovery.md) | Boot limits, diagnosis, updates, rollback, and removal |
| [Audit](docs/audit.md) | Review scope, changes, evidence, and unresolved risks |
| [Release checklist](docs/release.md) | Source publication and binary release requirements |
| [Security policy](SECURITY.md) | Private vulnerability reports and supported scope |

The source starts at Termux v0.118.3, commit `5b657c6adf4304e5198951ce815fe0205dcac29c`. See [upstream pins](upstream-lock.json), [the original README](README.upstream.md), and [notices](NOTICE.md). The repository remains GPL-3.0-only with the upstream exceptions described in [LICENSE.md](LICENSE.md).
