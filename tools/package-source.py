#!/usr/bin/env python3
"""Export reviewed source files without Git history, build products, or vendor caches."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import tarfile
from check import ROOT, publication_files, scan


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--out', type=Path, default=ROOT / '.dist/termux-admin-source.tar.gz')
    args = parser.parse_args()
    scan()
    files = publication_files()
    args.out = args.out.resolve()
    if args.out in files: parser.error('Output must not overwrite a source file')
    args.out.parent.mkdir(parents=True, exist_ok=True)
    manifest = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    with tarfile.open(args.out, 'w:gz', format=tarfile.PAX_FORMAT) as archive:
        for p in files:
            info = archive.gettarinfo(str(p), 'termux-admin/' + str(p.relative_to(ROOT)))
            info.uid = info.gid = 0; info.uname = info.gname = ''; info.mtime = 0
            with p.open('rb') as stream: archive.addfile(info, stream)
        body = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode()
        info = tarfile.TarInfo('termux-admin/SOURCE-MANIFEST.json')
        info.size = len(body); info.mode = 0o644
        archive.addfile(info, io.BytesIO(body))
    digest = hashlib.sha256(args.out.read_bytes()).hexdigest()
    args.out.with_suffix(args.out.suffix + '.sha256').write_text(digest + '  ' + args.out.name + '\n')
    print('Exported ' + str(len(files)) + ' reviewed source files to ' + str(args.out))


if __name__ == '__main__':
    main()
