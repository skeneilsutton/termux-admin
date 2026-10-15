# Release preparation audit

Prepared on 2026-10-14, before source publication. No physical phone was accessed or changed during preparation. Testing used a disposable emulator. Detailed predecessor receipts remain private. See `release.md` for the publication policy.

## Scope

The review covered the native Binder caller checks, discovery receiver, certificate configuration, command worker, recovery watcher, local TLS ADB recovery, helper manifest/callbacks, build/signing workflow, publication files, and prior import findings. It compared maintained source with the native overlay and previous audit notes. This is a targeted source audit and regression run, not an exhaustive fuzzing effort or a dependency-CVE scan.

## Changes in this preparation

| Finding | Prepared change |
| --- | --- |
| Source and private migration files were mixed in one project. | A separate complete source fork contains only selected release files. Private backup tools, device receipts, original APKs, and migration scripts are excluded. |
| Native build scripts imported private inventory and signing paths. | The build takes explicit SDK/JDK/key paths, derives its certificate pin from the selected key, and verifies actual signed APK certificates and non-debuggable output. |
| Recovery relied on a helper outside the fork with a personal package name. | Required helper source is included as `com.termux.admin.recovery`. Scripts target the same neutral package. Unused private setup helpers are excluded. |
| The watcher assumed SSH port 8022. | It reads an optional shell-owned `ssh-port` file and validates the decimal port. The default remains 8022. |
| Archive validation had been hardened, but live preference restoration remained uncertain. | The public maintenance API has no `restore` operation, migration CLI, or archive importer. The fixed SSH startup operation remains shell-only. Existing-installation migration stays outside this release. |
| Upstream automation could publish or upload debug APKs. | Those workflows are removed. The replacement CI only runs local regressions and source checks, with read-only repository permission. |
| A public debug signer could be mistaken for a release identity. | The debug keystore is removed from the working source and debug signing is disabled. Production releases require a separately protected private key. |
| Build Tools 30 zipalign rejected valid APK layouts during a repeated build. | Final core/plugin alignment and signing use Build Tools 35.0.0. The same APK passed alignment with the newer tool. |
| Recovery ignored a nonzero private ADB shutdown exit code. | The recovery script now reports cleanup failure. Synthetic recovery tests cover success, failed debugging disable with server shutdown, and failed server shutdown. |
| API's pinned shared-library artifact was unavailable on JitPack. | A generated API workspace builds that exact upstream library revision from source, with explicit AGP 8 namespaces and legacy resource-ID behavior. The build adapter is documented and requires runtime validation. |

## Retained authority checks

The exported discovery receiver requires `android.permission.DUMP`. It publishes a Binder handle; each privileged transaction still checks the actual caller UID. Owner operations admit the Termux shared UID. Shell bootstrap, registration, and SSH maintenance admit UID 2000. Registration uses a fresh single-use challenge and verifies the worker's real UID. The shell worker verifies the caller's installed package UID and signer before command submission. Host death retires the old worker and requires fresh registration. Commands are not replayed after loss.

Termux code and its signed shared-UID plugins are trusted administrators. External RUN_COMMAND delegation can expose these capabilities to permitted callers. This is a trust decision that setup documentation now states explicitly.

The phone-local ADB control server uses a private filesystem socket under the app's mode-0700 state directory. The temporary TLS listener still depends on paired-key authentication and verified debugging cleanup. The helper's alarm is best effort under Doze. Callback HMACs reject forged replies; bounded unauthenticated reads limit individual peers, but flooding can still deny service until the overall deadline.

## Evidence

The predecessor's 2026-10-09/10 review reported structured archive validation on synthetic cases, ordinary-app refusal, real owner policy readback, binary command streams, independent warm recovery, and corrected reboot recovery after first unlock and connected Wi-Fi on a Samsung SM-A165M, Android 16. Those are historical results for the predecessor, not tests of this renamed release helper.

The current local run passed seven callback tests, including malformed, silent, reset, forged, oversized, and slow peers, and three recovery cleanup tests with synthetic helper/ADB processes. JVM tests passed challenge concurrency/expiry/replay, twelve-process lock contention and abrupt holder death, and runtime-path preservation with unsafe environment removal. Receiver permissions passed checks in both source XML and a built APK. All five core architecture variants contain the pin of their verified signer. The publication-file scan and a source archive extraction/checksum round trip passed. Build and source-export results are recorded in `release-validation.json`.

Release builds passed for the five core variants, recovery helper, Termux:Boot, and Termux:API. All eight APKs passed certificate verification and non-debuggable checks with one temporary validation signer. API compiled its exact pinned shared-library source using the documented workspace adapter. Plugin runtime tests remain pending. The validation key is not a durable release identity and is removed after the checks.

A disposable Android 16 AOSP emulator with enforcing SELinux passed production-APK installation, device-owner enrollment/readback, real UID 2000 commands, separate stdout/stderr with child exit code 7, and camera-policy changes with the original false value restored. The actual watcher started a registered worker. The renamed helper passed STATE, ENABLE, and DISABLE requests, with wireless debugging read back as zero after cleanup. Initial enrollment was refused before Android's account-compatibility cache initialized; owner readback remained empty, and enrollment succeeded after the cache completion was observed.

The emulator's userdebug root facility staged test files and launched the CLI as the real Termux app UID. The watcher and worker ran as UID 2000. This fixture does not validate the documented SSH setup, complete unrooted installation workflow, local TLS pairing, failure recovery, or reboot recovery. It is separate from the historical physical-phone tests. No private phone data or signing identity was copied into the emulator.

## Remaining device and release checks

- Complete the unrooted setup, new maintenance entry point, configurable watcher port, same-signer plugins, and cold recovery checks on an authorized physical Android 16 test device.
- Measure recovery after first unlock and Wi-Fi connection. Do not claim mobile-only cold recovery.
- Test broader platform adapters before supporting another Android version or vendor. Hidden framework calls and the Java child-PID adapter are platform-sensitive.
- Review dependency advisories and embedded bootstrap provenance before distributing binaries. The pinned core retains an older Android build toolchain.
- Verify corresponding source and third-party notices for every distributed binary. The local source export covers the core/helper, while plugin source remains pinned separately.

Cancellation covers the direct child. Descendant cleanup, PTYs, rare exceptional process-start cleanup, and the PID reuse race remain limitations. Android power management and network reachability can still prevent or delay recovery. The archive-import preference issue remains open in the private migration implementation; it is absent from this release's callable API.
