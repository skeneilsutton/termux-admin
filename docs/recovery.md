# Recovery, updates, and removal

Ordinary SSH runs as Termux. The independent watcher runs as Android shell UID 2000. The recovery loop runs as Termux and temporarily uses its own paired TLS ADB identity to restart the watcher. A shell worker already running over mobile data can recover warm failures. A full reboot still needs first unlock and briefly connected Wi-Fi.

## Diagnose before repair

Check pinned SSH first. Once connected, run `~/bin/termux-admin status` and `~/bin/termux-admin shell -- /system/bin/id -u`. If shell access is ready, inspect the watcher PID, registered worker PID, listener on the configured SSH port, and bounded logs under `/data/local/tmp/termux-admin-recovery`. App-side recovery logs are in `~/.local/state/phone-recovery`.

If SSH works but the bridge is unavailable, unlock the phone, connect trusted Wi-Fi, and run `~/bin/phone-recover --once`. It checks bridge readiness, obtains a bounded debugging lease, tries paired local TLS endpoints, starts the watcher, and cleans up debugging and its private ADB server. Failure is an instruction to inspect state, not to rerun an uncertain user command.

If SSH is unavailable, use the verified independent USB or paired host ADB route. Inspect the installed core/helper, owner state, app-private storage, SSH keys/configuration, and watcher files. Open Termux and Boot if needed. Do not recreate SSH or ADB identities or clear app data as a generic repair step.

After a repair, verify the actual installed hashes, real UID 2000, owner readback, wireless-debugging state, and absence of legacy TCP ADB listeners. A background command's launch status does not prove that its worker stayed alive. Never unlink live lock files to force a restart.

## Update or roll back

Before an APK change, preserve a verified private backup of Termux home/prefix, plugin preferences, SSH authorization/host keys, phone-local ADB identity, signing identity, and recovery scripts. Keep the known-good APKs and their checksums in private rollback storage. Ensure free space for the backup and update.

Use the same signer and compatible version code across the Termux shared-UID family. An APK update can stop the app and SSH. Establish an independent recovery route before installing it. The renamed release helper is `com.termux.admin.recovery`; the predecessor's private helper is a different package. Do not silently replace or remove that helper on an already managed phone.

Install only reviewed non-debuggable APKs and scripts. Verify the installed signer, owner/shared UID, files, shell access, SSH, and cleanup afterward. Do not infer deployment from build output or `adb install` alone. Android can refuse a downgrade; do not use uninstall/reinstall or a debug rescue APK as an automatic workaround. Inspect the platform refusal and choose an explicitly authorized rollback path.

## Remove this setup

Back up the phone's Termux data and establish independent access first. Stop the app recovery loop and remove the specific boot script you installed. Use the stored private loop PID and verify its command before terminating it. Disable wireless debugging with the helper and stop the private filesystem ADB server. Stop the verified watcher and worker through authenticated ADB. Check that they stay stopped before removing their recovery directory. Keep lock files until all holders have exited.

Undo policy changes according to your setup receipt, including uninstall protection and any restrictions you enabled. The fork intentionally has no automatic policy-reset or factory-reset command. It also has no direct relinquish-owner command in this release.

For a phone that Termux owns, the supported CLI removal path is an explicitly authorized transfer to another installed, eligible DPC with ownership-transfer metadata:

```sh
~/bin/termux-admin owner transfer-to TARGET_PACKAGE/TARGET_RECEIVER
```

Do not substitute a guessed receiver. Validate the destination, obtain consent for that ownership change, and read back the new owner before uninstalling Termux. Android's [transferOwnership documentation](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#transferOwnership(android.content.ComponentName,android.content.ComponentName,android.os.PersistableBundle)) describes the platform checks and policy transfer. If no appropriate transfer target exists, stop and plan removal using the device's supported management/reset workflow. A factory reset destroys data and requires separate user approval. `dpm remove-active-admin` is not a generic production-owner removal method.

Once Termux is no longer owner and removal is authorized, uninstall the intended core and plugins, remove `com.termux.admin.recovery`, and remove the shell recovery directory and host configuration you created. Keep unrelated phone apps, VPN routes, SSH entries, and backups. Verify the final owner/admin state and debugging settings through the independent route.
