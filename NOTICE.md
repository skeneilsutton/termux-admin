# Source and attribution

This independent experimental fork starts at [Termux v0.118.3](https://github.com/termux/termux-app/tree/5b657c6adf4304e5198951ce815fe0205dcac29c). Upstream files retain their notices. Native admin integration, recovery code, tests, build tools, and fork documentation were added or modified for this project. New first-party additions are GPL-3.0-only. See `COPYING` for the full license and `LICENSE.md` for upstream exceptions.

The terminal emulator/view code retains its Apache-2.0 exceptions. `termux-shared/LICENSE.md` lists its own exceptions. Do not replace those notices with a blanket license claim.

The framework discovery adapter in `Compat.java` follows the explicit ActivityManager broadcast/callback approach studied in [Shizuku v13.6.0](https://github.com/RikkaApps/Shizuku/tree/v13.6.0), licensed under Apache-2.0. A copy of that license is in `licenses/Apache-2.0.txt`. Shizuku is a protocol reference, not a runtime dependency; the worker and owner controls run in this fork.

API and Boot are fetched from the official Termux repositories at the commits in `upstream-lock.json`. Their source and notices remain in the fetched checkouts. The generated API build compiles its exact pinned shared-library source revision in place of the unavailable JitPack artifact; `tools/build-plugins.py` contains the build adaptations. Preserve the `termux-api-shared` source and its notices alongside API source for any API binary distribution. Plugin APKs must share the core's signer. The build never copies installed plugin APKs from a private device.

The core embeds upstream Termux bootstrap archives from `bootstrap-2025.03.28-r1+apt-android-7`, with checksums in `app/build.gradle`. Package source/build definitions are maintained in [termux-packages](https://github.com/termux/termux-packages). Review the corresponding package source and notices when preparing a binary distribution.

The Termux name and upstream links identify the base project. This fork is not an official Termux release. Report fork issues to [skeneilsutton/termux-admin](https://github.com/skeneilsutton/termux-admin/issues). See `SECURITY.md` for private vulnerability reports.
