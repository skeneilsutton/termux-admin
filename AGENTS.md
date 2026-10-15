# Agent instructions

Read `README.md`, `docs/setup.md`, `docs/build.md`, and `docs/recovery.md` before installation. Report observed device state separately from source inspection and historical test results.

The phone is unrooted. Ordinary Termux, device-owner policy calls, and Android shell UID 2000 have different permissions. Check the identity required by each operation. A successful APK build does not enroll the device or establish shell readiness.

Choose one verified device serial and use explicit `adb -s` targeting. Keep debugging authorization and SSH host-key verification. The phone-local ADB server must use a socket in Termux's mode-0700 private state directory. Do not use legacy TCP ADB or the default TCP control server.

Installation on an empty test device and migration of an existing phone are different tasks. This release provides no archive importer. Never uninstall an existing Termux family, remove accounts, reset the phone, transfer ownership, or change its signer without explicit user authorization and a backup/recovery plan. Inspect an Android enrollment refusal before choosing another step.

Code in the Termux shared UID is trusted administrator code. Keep external RUN_COMMAND delegation disabled by default. API and Boot plugins must have the same signing certificate as the core. Keep private signing material outside the repository and preserve it for future updates.

Do not replay a command after `UNKNOWN`, `STREAM_FAILED`, timeout, or transport loss. Inspect the resulting state first. Restore temporary test policy changes. Do not run reboot or failure tests just to check connectivity.

Run `python3 tools/check.py` with JDK 11 configured for source changes. Use `tools/build.py` and `tools/build-plugins.py` for signed release validation. Check installed hashes, owner readback, real shell UID, recovery, and debugging cleanup before reporting deployment success. Record device receipts privately, with redacted public summaries.

Source packaging and publication are separate actions. `tools/package-source.py` only creates a local archive. No tool in this repository publishes or uploads it.
