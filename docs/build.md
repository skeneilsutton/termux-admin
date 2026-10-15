# Build and signing

The build tools target macOS and Linux hosts. Use Python 3.9 or later, Git, JDK 11 for the Termux core, and JDK 21 for the helper and plugin builds. Install Android SDK platform 30, Build Tools 30.0.3, and NDK 22.1.7171670 for the core. Final alignment/signing uses Build Tools 35.0.0, since the older zipalign can reject valid APK layouts. The helper needs platform 36 and Build Tools 36.0.0. API needs platform 35; Boot needs platform 34. Plugin signing also uses Build Tools 35.0.0.

The core retains its pinned Gradle 7.2 and Android Gradle Plugin 4.2.2. Modernizing that toolchain is separate work. Gradle downloads dependencies and four upstream bootstrap archives whose checksums remain in `app/build.gradle`. Toolchain age and a pinned dependency do not establish absence of vulnerabilities.

## Create a private signing identity

Keep the keystore and its password file outside the repository. Use a private directory and mode 0600 for both files. Back up the key securely. Losing it prevents normal same-signer updates.

```sh
umask 077
mkdir -p "$HOME/.local/share/termux-admin-signing"
keytool -genkeypair -storetype PKCS12 \
  -keystore "$HOME/.local/share/termux-admin-signing/release.p12" \
  -alias termux-admin -keyalg RSA -keysize 3072 -validity 10000
chmod 600 "$HOME/.local/share/termux-admin-signing/release.p12"
```

The command prompts for a password. Save that password in a mode-0600 `storepass` file in the same private directory without putting it in shell history or command arguments. This preparation used a temporary validation key. That key is not a proposed public release signer and must not replace an installed phone's key.

## Build the core and helper

Set `JAVA_HOME` to JDK 11 and `ANDROID_SDK_ROOT` to your SDK. Supply JDK 21 explicitly for the helper.

```sh
python3 tools/build.py \
  --helper-java-home /path/to/jdk-21 \
  --keystore "$HOME/.local/share/termux-admin-signing/release.p12" \
  --alias termux-admin \
  --password-file "$HOME/.local/share/termux-admin-signing/storepass"
```

`--java-home`, `--sdk`, `--helper-build-tools`, and `--helper-android-jar` override the defaults. The tool derives a SHA-256 certificate pin from the selected key, supplies it to Gradle, signs the APKs, verifies their actual certificates, and refuses debuggable output. It builds all four architecture splits plus a universal core APK and the neutral `com.termux.admin.recovery` helper. Output and its checksum receipt go to `.dist/apks/`.

The public upstream debug keystore has been removed. Debug APKs are unsigned. Do not distribute a debug or rescue build. Direct Gradle use requires `-PtermuxAdminCertSha256` with the intended production certificate digest; it does not sign or verify the final APK for you.

## Build matching plugins from source

The build does not use APKs copied from a private phone. It fetches the exact API and Boot commits in `upstream-lock.json` into the ignored `vendor/` directory. It refuses a dirty or mismatched checkout and signs each plugin with the core's key.

API's upstream shared-library AAR at `7bceab88e2` was unavailable from JitPack during validation. The builder fetches that exact source revision using `termux-api-shared` in the lock file, then compiles its `termux-shared`, `terminal-view`, and `terminal-emulator` modules as project dependencies in a separate generated workspace. It adds AGP 8 namespaces, preserves legacy resource-ID behavior, removes obsolete library publication tasks, and leaves the fetched upstream checkouts untouched. The adapter is recorded in the build receipt. API runtime validation remains required before binary distribution.

```sh
python3 tools/build-plugins.py \
  --sdk "$ANDROID_SDK_ROOT" --java-home /path/to/jdk-21 \
  --keystore "$HOME/.local/share/termux-admin-signing/release.p12" \
  --alias termux-admin \
  --password-file "$HOME/.local/share/termux-admin-signing/storepass"
```

Use `--plugin boot` or `--plugin api` for one plugin. Boot is needed for the documented automatic cold-recovery loop. API is optional for the admin CLI and shell worker. The builder checks the existing core receipt before accepting the plugin signer.

## Validate and export

```sh
python3 tools/check.py --java-home /path/to/jdk-11
python3 tools/package-source.py
```

The source export uses reviewed repository files, includes a per-file SHA-256 manifest, and excludes Git history, vendor caches, generated APKs, logs, and signing files. The scan checks selected secret formats and private setup references. Review the actual archive before publication. If you later distribute plugin APKs, also provide source archives for their pinned commits and dependency notices. Preserve corresponding source information for the bootstrap packages embedded in the core.
