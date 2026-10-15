#!/usr/bin/env python3
"""Run local regressions and check publication files. Does not contact a phone."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def publication_files():
    if not (ROOT / '.git').exists():
        manifest = json.loads((ROOT / 'SOURCE-MANIFEST.json').read_text())
        files = []
        for name in manifest:
            p = ROOT / name
            if Path(name).is_absolute() or '..' in Path(name).parts:
                raise RuntimeError('Source manifest contains an unsafe path')
            if not p.is_file(): raise RuntimeError('Source file is missing: ' + name)
            files.append(p)
        return sorted(files)
    raw = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others',
                                   '--exclude-standard'], cwd=ROOT)
    return sorted({ROOT / os.fsdecode(p) for p in raw.split(b'\0') if p and (ROOT / os.fsdecode(p)).exists()})


def scan():
    forbidden = {'.apk', '.dex', '.class', '.p12', '.jks', '.keystore', '.pem', '.idsig', '.pyc'}
    patterns = [rb'-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----',
                rb'gh[pousr]_[A-Za-z0-9]{30,}', rb'github_pat_[A-Za-z0-9_]{30,}',
                rb'sk-or-v1-[A-Za-z0-9]{30,}',
                rb'(?<![A-Za-z0-9_/])/(?:Users|home)/' + rb'[^\s/\x22\x27]+/',
                rb'com\.[a-z0-9_]+\.phonerecovery',
                rb'com\.[a-z0-9_]+\.termuxadmin\.(?:test|probe)',
                rb'\.local/share/' + rb'phone-setup']
    failures = []
    for p in publication_files():
        relative = p.relative_to(ROOT)
        if p.is_symlink():
            failures.append(str(relative) + ': review symbolic link before export'); continue
        if p.suffix in forbidden or p.name in {'adbkey', 'storepass', 'local.properties', 'migration-state.json'}:
            failures.append(str(relative) + ': private or generated file'); continue
        if any(re.search(pattern, p.read_bytes()) for pattern in patterns):
            failures.append(str(relative) + ': potential secret or private setup reference')
    if failures:
        raise RuntimeError('\n'.join(failures))
    print('Publication scan passed for ' + str(len(publication_files())) + ' files')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', type=Path, default=os.environ.get('JAVA_HOME'))
    parser.add_argument('--scan-only', action='store_true')
    args = parser.parse_args()
    scan()
    spec = importlib.util.spec_from_file_location('manifest', ROOT / 'tests/admin/check_native_manifest.py')
    manifest = importlib.util.module_from_spec(spec); spec.loader.exec_module(manifest)
    manifest.check(ROOT / 'app/src/main/AndroidManifest.xml')
    admin = ROOT / 'app/src/main/java/com/termux/admin'
    if any('"restore"' in p.read_text() or 'prepareRestore' in p.read_text() for p in admin.glob('*.java')):
        raise RuntimeError('Archive import must not be exposed in this release')
    if args.scan_only: return
    if not args.java_home: parser.error('Set JAVA_HOME or pass --java-home for JVM regression tests')
    subprocess.run(['python3', '-m', 'unittest', 'discover', '-s', 'tests/admin', '-p', 'test_*.py'], cwd=ROOT, check=True)
    with tempfile.TemporaryDirectory(prefix='termux-admin-tests-') as tmp:
        names = ['RegistrationChallenge', 'ProcessLease', 'RuntimeEnvironment']
        sources = [p for n in names for p in (admin / (n + '.java'), ROOT / 'tests/admin' / (n + 'Test.java'))]
        subprocess.run([str(args.java_home / 'bin/javac'), '-d', tmp, *map(str, sources)], check=True)
        for name in names:
            subprocess.run([str(args.java_home / 'bin/java'), '-cp', tmp, 'com.termux.admin.' + name + 'Test'], check=True)
    print('Local regressions and native manifest checks passed')


if __name__ == '__main__':
    main()
