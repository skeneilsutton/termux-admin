# Commands

The app installs `~/bin/termux-admin`. Use that full path or add `~/bin` to PATH. The app must have started at least once.

```sh
~/bin/termux-admin status
~/bin/termux-admin shell -- /system/bin/id -u
~/bin/termux-admin shell -- /system/bin/sh -c 'echo out; echo err >&2; exit 7'
```

Status reports `device_owner`, `active_admin`, `host_uid`, `shell_uid`, and `shell_ready`. Derive the app UID from status or `id -u`; do not hardcode it. A ready shell reports UID 2000.

The shell command preserves binary streams and the direct child's exit code. It accepts an argument vector after `--`. Shell syntax only applies if you explicitly invoke `/system/bin/sh -c`. There are four concurrent command slots. Busy or revoked workers refuse new submissions.

| Owner command | Values |
| --- | --- |
| `owner user-control-disabled add PACKAGE...` | Add packages to user-control protection |
| `owner user-control-disabled remove PACKAGE...` | Remove packages from that protection |
| `owner suspend BOOL PACKAGE` | `true` or `false` |
| `owner hide BOOL PACKAGE` | `true` or `false` |
| `owner block-uninstall BOOL PACKAGE` | `true` or `false` |
| `owner restriction NAME BOOL` | `DISALLOW_SAFE_BOOT`, `DISALLOW_FACTORY_RESET`, or `DISALLOW_ADD_USER` |
| `owner camera-disabled BOOL` | `true` or `false` |
| `owner screen-capture-disabled BOOL` | `true` or `false` |
| `owner backup-enabled BOOL` | `true` or `false` |
| `owner transfer-to PACKAGE/RECEIVER` | Explicitly authorized transfer to an eligible DPC |

Policies report `observed`, `partial`, `rejected`, or `unknown`, with readback and refused packages where relevant. A nonzero exit does not always mean nothing changed. Transfer changes which app manages the phone; it is not a connectivity test.

Owner requests execute inside the Termux app after caller authorization. Shell commands execute in a separate UID 2000 process. Device ownership does not grant shell UID, root, unrestricted Android APIs, or a bypass of Android's privacy indicators.

CLI exit codes are 64 for syntax errors, 77 for refusal or a policy result that was not fully observed, and 70 for an unavailable or uncertain operation. A normally completed shell command returns its child's exit code, which can have the same number. Inspect stderr and policy JSON when interpreting a failure.

`UNKNOWN` and `STREAM_FAILED` mean execution or captured output is uncertain. Never repeat the command automatically. Inspect the intended change through a separate read-only command first. Cancellation terminates the direct child; descendant processes and interactive PTYs have no guarantee.
