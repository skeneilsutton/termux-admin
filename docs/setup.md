# Agent setup

This is a new-installation workflow for an eligible Android 16 test device. The predecessor was tested on one Samsung model and an emulator. The prepared source passed limited enrollment, owner/shell command, and helper checks on a disposable emulator. Its complete setup and reboot recovery under the new helper package name still need physical-device testing. Treat other Android versions and vendors as unsupported until tested.

## Before changing anything

Confirm the user's target device and intended enrollment. Record its model, Android version, existing owner, installed Termux family, signer, free space, and current backup/recovery routes. Keep serials, accounts, keys, and addresses in private receipts. Do not publish raw `dumpsys` output.

Use a connected, unlocked phone and authenticated ADB. A person must approve debugging and any phone-side prompts. Pin SSH host keys through this trusted route. All host ADB commands must use the selected serial explicitly.

```sh
adb devices -l
adb -s "$termux_device_serial" shell id -u
adb -s "$termux_device_serial" shell dpm list-owners
```

The shell identity must be 2000. Do not guess the serial or select the first device. If a Termux family or device owner already exists, stop the fresh-install path and inspect migration or transfer requirements. This release has no archive restoration facility. Android will reject an APK update whose signer differs, and removing a shared-UID family can erase its data.

Android controls device-owner eligibility. The official [testing workflow](https://developer.android.com/work/dpc/dedicated-devices#testing) and [DPC guide](https://developer.android.com/work/dpc/build-dpc) describe enrollment. A provisioned phone, accounts, or other users can prevent ADB enrollment. Inspect the actual refusal. Do not remove accounts or reset the phone automatically.

On the disposable Android 16 emulator used in this preparation, the first enrollment request reported existing accounts even though the account count was zero. The platform's account-compatibility cache had not finished initializing. After its completion was observed and owner readback still showed none, a reviewed second enrollment request succeeded. Treat an early refusal as a reason to inspect state, not proof that accounts must be deleted. Android 16's [device-policy implementation](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android16-release/services/devicepolicy/java/com/android/server/devicepolicy/DevicePolicyManagerService.java) treats an uninitialized compatibility cache as incompatible.

## Install and enroll

Build reviewed release APKs as described in `build.md`. Verify their checksum receipt, certificates, and non-debuggable manifests. Keep the signing identity private and stable.

Install the architecture-appropriate core, the recovery helper, and the same-signer Boot APK. API is optional. These commands apply only after confirming the device has no conflicting installation and the user has authorized enrollment.

```sh
adb -s "$termux_device_serial" install .dist/apks/termux-app_admin.2_arm64-v8a.apk
adb -s "$termux_device_serial" install .dist/apks/termux-admin-recovery.apk
adb -s "$termux_device_serial" install .dist/apks/termux-boot.apk
adb -s "$termux_device_serial" shell dpm set-device-owner com.termux/com.termux.admin.AdminReceiver
adb -s "$termux_device_serial" shell dpm list-owners
adb -s "$termux_device_serial" shell pm grant com.termux android.permission.WRITE_SECURE_SETTINGS
adb -s "$termux_device_serial" shell pm grant com.termux.admin.recovery android.permission.WRITE_SECURE_SETTINGS
```

Open Termux to create its environment and install the CLI. Open Termux:Boot once to enable its boot behavior. These actions may require the person holding the phone. In Termux, install `openssh`, `python`, and `android-tools`. Create SSH host keys with `ssh-keygen -A`. Configure `sshd_config` for public-key authentication only, password and keyboard-interactive authentication off, and the selected port, initially 8022. Put only the intended client's public key in mode-0600 `~/.ssh/authorized_keys`, with `~/.ssh` at mode 0700. Never copy the client's private SSH key onto the phone.

Start SSH and verify both successful key authentication and refusal without a key. Configure a host SSH alias, here `termux-admin-phone`, with its own identity and strict host-key checking. Verify `id -u` over SSH and the pinned host key. VPN routing is optional and is separate from this fork.

## Install recovery files

The phone-local scripts belong to the Termux app. The watcher and its configuration belong to Android shell. Use the trusted ADB route for the watcher and pinned SSH for app-private files.

```sh
adb -s "$termux_device_serial" shell mkdir -p /data/local/tmp/termux-admin-recovery
adb -s "$termux_device_serial" shell chmod 700 /data/local/tmp/termux-admin-recovery
adb -s "$termux_device_serial" push recovery/scripts/watch-admin.sh /data/local/tmp/termux-admin-recovery/watch-admin.sh
adb -s "$termux_device_serial" shell chmod 700 /data/local/tmp/termux-admin-recovery/watch-admin.sh
scp recovery/scripts/phone-settings.py termux-admin-phone:bin/phone-settings
scp recovery/scripts/phone-recover.py termux-admin-phone:bin/phone-recover
ssh termux-admin-phone 'chmod 700 ~/bin/phone-settings ~/bin/phone-recover'
```

If SSH uses another port, write its decimal value to `/data/local/tmp/termux-admin-recovery/ssh-port` through authenticated ADB before starting the watcher. The watcher refuses an invalid port. Do not delete lock files while any worker or watcher might hold them.

Enable wireless debugging temporarily while connected to trusted Wi-Fi. Pair the phone's own Termux ADB client using Android's pairing page and a private control socket. Pairing and connection ports differ. Run these commands in Termux and enter the fresh pairing code at the prompt.

```sh
mkdir -p "$HOME/.local/state/phone-recovery"
chmod 700 "$HOME/.local/state/phone-recovery"
adb -L "localfilesystem:$HOME/.local/state/phone-recovery/adb.sock" pair "127.0.0.1:$termux_pairing_port"
~/bin/phone-settings DISABLE
adb -L "localfilesystem:$HOME/.local/state/phone-recovery/adb.sock" kill-server
~/bin/phone-recover --once
~/bin/termux-admin status
~/bin/termux-admin shell -- /system/bin/id -u
```

This creates a phone-local ADB identity. Do not copy the host's private ADB key. The helper controls a temporary wireless-debugging lease, with verified disable on the normal cleanup path and an alarm retry for failures. Its 45-second alarm is best effort under Android power management.

Create a mode-0700 Termux:Boot script at `~/.termux/boot/20-admin-recovery` with the following content. If an existing boot script already starts SSH or recovery, review it before adding another.

```sh
#!/data/data/com.termux/files/usr/bin/sh
umask 077
sshd -o PasswordAuthentication=no -o KbdInteractiveAuthentication=no -o AuthenticationMethods=publickey
mkdir -p "$HOME/.local/state/phone-recovery"
chmod 700 "$HOME/.local/state/phone-recovery"
"$HOME/bin/phone-recover" --loop </dev/null >>"$HOME/.local/state/phone-recovery/boot.log" 2>&1 &
```

## Acceptance checks

Verify pinned key-only SSH, actual app UID, owner/admin readback, real shell UID 2000, separate streams and child exit code, installed script hashes, one watcher and one worker, and no TCP ADB listeners on 5037 or 5555. Verify wireless debugging is off through `phone-settings STATE` and Android settings readback.

Before owner-policy tests, record the prior state and restore it afterward. Plan warm failure tests and a full reboot as separate authorized actions with an independent recovery route. After reboot, unlock and briefly connect Wi-Fi. Record that cold mobile-only recovery has not been established. Store detailed receipts privately and add a redacted evidence summary to `audit.md` before calling this release device-validated.
