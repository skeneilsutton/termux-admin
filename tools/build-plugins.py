#!/usr/bin/env python3
"""Fetch pinned plugin source, build release APKs, and sign with the core's key."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from build import ROOT, certificate, run, sign


def checkout(name, upstream, env):
    source = ROOT / 'vendor' / name
    if not source.exists():
        source.mkdir(parents=True)
        run(['git', 'init', '-q', source], env)
        run(['git', '-C', source, 'fetch', '--depth=1', upstream['url'], upstream['commit']], env)
        run(['git', '-C', source, 'checkout', '--detach', 'FETCH_HEAD'], env)
    current = run(['git', '-C', source, 'rev-parse', 'HEAD'], env, capture=True).stdout.decode().strip()
    dirty = run(['git', '-C', source, 'status', '--porcelain'], env, capture=True).stdout
    if current != upstream['commit'] or dirty:
        raise RuntimeError('Source must be clean and match upstream-lock.json: ' + name)
    return source


def api_workspace(source, shared_source):
    """Build API's exact pinned shared-library source instead of its missing AAR."""
    work = ROOT / 'vendor/api-build'
    if work.exists(): shutil.rmtree(work)
    ignore = shutil.ignore_patterns('.git', '.gradle', 'build', '.cxx', '.externalNativeBuild', 'local.properties')
    shutil.copytree(source, work, ignore=ignore)
    app = work / 'app/build.gradle'
    text = app.read_text()
    dependency = 'implementation "com.termux.termux-app:termux-shared:7bceab88e2"'
    if text.count(dependency) != 1: raise RuntimeError('Unexpected API shared-library declaration')
    app.write_text(text.replace(dependency, 'implementation project(":termux-shared")'))
    settings = work / 'settings.gradle'
    settings.write_text(settings.read_text() + '\ninclude ":termux-shared", ":terminal-view", ":terminal-emulator"\n')
    properties = work / 'gradle.properties'
    properties.write_text(properties.read_text() + '\nmarkwonVersion=4.6.2\nndkVersion=22.1.7171670\n'
                          'android.nonFinalResIds=false\nandroid.nonTransitiveRClass=false\n')
    for name, namespace in [('termux-shared', 'com.termux.shared'),
                            ('terminal-view', 'com.termux.view'),
                            ('terminal-emulator', 'com.termux.terminal')]:
        module = work / name
        shutil.copytree(shared_source / name, module, ignore=ignore)
        gradle = module / 'build.gradle'
        text = gradle.read_text().replace("apply plugin: 'maven-publish'", '')
        text = text.replace('android {', "android {\n    namespace '" + namespace + "'", 1)
        # Maven publication tasks are not needed for an in-tree project dependency.
        if 'task sourceJar(' in text: text = text[:text.index('task sourceJar(')]
        gradle.write_text(text)
        manifest = module / 'src/main/AndroidManifest.xml'
        manifest.write_text(re.sub(r'\s+package="[^"]+"', '', manifest.read_text(), count=1))
    return work


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--java-home', type=Path, required=True)
    parser.add_argument('--build-tools', default='35.0.0')
    parser.add_argument('--keystore', type=Path, required=True)
    parser.add_argument('--alias', required=True)
    parser.add_argument('--password-file', type=Path, required=True)
    parser.add_argument('--plugin', choices=['api', 'boot', 'all'], default='all')
    parser.add_argument('--out', type=Path, default=ROOT / '.dist/apks')
    args = parser.parse_args()
    args.sdk = args.sdk.resolve(); args.java_home = args.java_home.resolve()
    args.keystore = args.keystore.resolve(); args.password_file = args.password_file.resolve()
    args.out = args.out.resolve()
    for p in (args.keystore, args.password_file):
        if p.is_relative_to(ROOT) or not p.is_file() or p.stat().st_mode & 0o077:
            parser.error('Keep mode-0600 signing files outside this repository')
    env = dict(os.environ, JAVA_HOME=str(args.java_home), ANDROID_HOME=str(args.sdk),
               ANDROID_SDK_ROOT=str(args.sdk), PATH=str(args.java_home / 'bin') + os.pathsep + os.environ['PATH'])
    pin = certificate(args.java_home, args.keystore, args.alias, args.password_file, env)
    lock = json.loads((ROOT / 'upstream-lock.json').read_text())
    receipt = args.out / 'manifest.json'
    if not receipt.exists(): parser.error('Build the core and helper first')
    manifest = json.loads(receipt.read_text())
    if any(item['certificate_sha256'] != pin for item in manifest['artifacts']):
        parser.error('Plugin key differs from the built core key')
    for item in manifest['artifacts']:
        path = args.out / item['apk']
        if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != item['sha256']:
            parser.error('An existing artifact differs from its core build receipt')
    plugins = ['boot', 'api'] if args.plugin == 'all' else [args.plugin]
    failures = []
    for plugin in plugins:
        name = 'termux-' + plugin; upstream = lock[name]
        source = checkout(name, upstream, env)
        workspace = api_workspace(source, checkout('termux-api-shared', lock['termux-api-shared'], env)) if plugin == 'api' else source
        try:
            run(['./gradlew', '--no-daemon', '--max-workers=2', '-Dorg.gradle.jvmargs=-Xmx1536m', ':app:assembleRelease'], env, cwd=workspace)
        except subprocess.CalledProcessError:
            failures.append(name)
            continue
        apks = list((workspace / 'app/build/outputs/apk/release').glob('*.apk'))
        if len(apks) != 1: raise RuntimeError('Expected one release APK: ' + name)
        target = args.out / (name + '.apk')
        record = sign(apks[0], target, args.sdk / 'build-tools' / args.build_tools, args, env, pin)
        record['source_commit'] = upstream['commit']
        if plugin == 'api':
            record['shared_library_source_commit'] = lock['termux-api-shared']['commit']
            record['build_adapter'] = 'AGP 8 namespace/project/resource configuration in tools/build-plugins.py'
        manifest['artifacts'] = [item for item in manifest['artifacts'] if item['apk'] != target.name] + [record]
        receipt.write_text(json.dumps(manifest, indent=2) + '\n')
    if failures: raise RuntimeError('Plugin builds incomplete: ' + ', '.join(failures))
    print('Verified same-signer plugin APKs. Receipt: ' + str(receipt))


if __name__ == '__main__':
    main()
