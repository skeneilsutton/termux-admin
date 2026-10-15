#!/usr/bin/env python3
"""Build and sign the core and recovery helper without device access."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(argv, env, cwd=ROOT, capture=False):
    return subprocess.run([str(x) for x in argv], cwd=cwd, env=env,
                          check=True, capture_output=capture)


def certificate(java, keystore, alias, password, env):
    result = run([java / 'bin/keytool', '-exportcert', '-alias', alias,
                  '-keystore', keystore, '-storepass:file', password], env, capture=True)
    return hashlib.sha256(result.stdout).hexdigest()


def sign(source, target, bt, args, env, expected):
    aligned = target.with_suffix('.aligned.apk')
    try:
        run([bt / 'zipalign', '-f', '4', source, aligned], env)
        run([bt / 'apksigner', 'sign', '--ks', args.keystore,
             '--ks-key-alias', args.alias, '--ks-pass', 'file:' + str(args.password_file),
             '--out', target, aligned], env)
        result = run([bt / 'apksigner', 'verify', '--print-certs', target], env, capture=True)
        found = re.findall(rb'Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)', result.stdout)
        if found != [expected.encode()]:
            raise RuntimeError('APK signing certificate differs from the embedded pin')
        # A distribution APK must not be debuggable.
        badging = run([bt / 'aapt2', 'dump', 'badging', target], env, capture=True).stdout
        if b'application-debuggable' in badging:
            raise RuntimeError('Debuggable APK refused')
        return {'apk': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
                'certificate_sha256': expected}
    finally:
        aligned.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, default=os.environ.get('ANDROID_SDK_ROOT'))
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--helper-build-tools', type=Path)
    parser.add_argument('--helper-android-jar', type=Path)
    parser.add_argument('--helper-java-home', type=Path, required=True)
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--out', type=Path, default=ROOT / '.dist/apks')
    args = parser.parse_args()
    if not args.sdk or not args.java_home:
        parser.error('Set ANDROID_SDK_ROOT and JAVA_HOME or pass --sdk and --java-home')
    args.sdk = args.sdk.resolve(); args.java_home = args.java_home.resolve()
    args.keystore = args.keystore.resolve(); args.password_file = args.password_file.resolve()
    args.out = args.out.resolve()
    for secret in (args.keystore, args.password_file):
        if secret.is_relative_to(ROOT):
            parser.error('Keep signing secrets outside the source repository')
        if not secret.is_file() or secret.stat().st_mode & 0o077:
            parser.error('Signing secrets must be private files with mode 0600 or stricter')
    bt = args.sdk / 'build-tools' / args.build_tools
    helper_bt = (args.helper_build_tools or args.sdk / 'build-tools/36.0.0').resolve()
    jar = (args.helper_android_jar or args.sdk / 'platforms/android-36/android.jar').resolve()
    helper_java = args.helper_java_home.resolve()
    required = [args.java_home / 'bin/java', args.java_home / 'bin/keytool',
                helper_java / 'bin/javac', bt / 'zipalign', bt / 'apksigner', bt / 'aapt2',
                helper_bt / 'aapt2', helper_bt / 'd8', helper_bt / 'zipalign',
                helper_bt / 'apksigner', helper_bt / 'core-lambda-stubs.jar', jar,
                args.sdk / 'platforms/android-30/android.jar']
    missing = [str(p) for p in required if not p.is_file()]
    if missing: parser.error('Missing build inputs: ' + ', '.join(missing))
    env = dict(os.environ, JAVA_HOME=str(args.java_home), ANDROID_HOME=str(args.sdk),
               ANDROID_SDK_ROOT=str(args.sdk), TERMUX_APP_VERSION_NAME='0.118.3-admin.2',
               TERMUX_APK_VERSION_TAG='admin.2', TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS='1',
               PATH=str(args.java_home / 'bin') + os.pathsep + os.environ['PATH'])
    pin = certificate(args.java_home, args.keystore, args.alias, args.password_file, env)
    args.out.mkdir(parents=True, exist_ok=True)
    # A failed rebuild must not leave an older success receipt over mixed output.
    (args.out / 'manifest.json').unlink(missing_ok=True)
    run(['./gradlew', '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx1536m',
         '-PtermuxAdminCertSha256=' + pin, ':app:assembleRelease'], env)
    records = []
    for source in sorted((ROOT / 'app/build/outputs/apk/release').glob('termux-app_admin.2_*.apk')):
        target = args.out / source.name
        records.append(sign(source, target, bt, args, env, pin))
        with zipfile.ZipFile(target) as archive:
            dex = [n for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex', n)]
            if not any(pin.encode() in archive.read(n) for n in dex):
                raise RuntimeError('Built core does not contain its expected certificate pin')
    if not records:
        raise RuntimeError('No core APKs found')
    work = ROOT / 'recovery/build'
    if work.exists(): shutil.rmtree(work)
    (work / 'classes').mkdir(parents=True)
    helper_env = dict(env, JAVA_HOME=str(helper_java),
                      PATH=str(helper_java / 'bin') + os.pathsep + os.environ['PATH'])
    run([helper_java / 'bin/javac', '-source', '8', '-target', '8', '-Xlint:-options',
         '-bootclasspath', str(helper_bt / 'core-lambda-stubs.jar') + os.pathsep + str(jar),
         '-d', work / 'classes', *sorted((ROOT / 'recovery/android/src').rglob('*.java'))], helper_env)
    run([helper_bt / 'aapt2', 'link', '-I', jar, '--manifest',
         ROOT / 'recovery/android/AndroidManifest.xml', '-o', work / 'unsigned.apk'], helper_env)
    run([helper_bt / 'd8', '--lib', jar, '--min-api', '26', '--output', work,
         *sorted((work / 'classes').rglob('*.class'))], helper_env)
    with zipfile.ZipFile(work / 'unsigned.apk', 'a') as archive:
        archive.write(work / 'classes.dex', 'classes.dex')
    records.append(sign(work / 'unsigned.apk', args.out / 'termux-admin-recovery.apk',
                        helper_bt, args, helper_env, pin))
    receipt = {'version': '0.118.3-admin.2', 'artifacts': records,
               'device_testing': 'Not installed or tested by this build'}
    (args.out / 'manifest.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('Verified signed core and helper APKs. Receipt: ' + str(args.out / 'manifest.json'))


if __name__ == '__main__':
    main()
