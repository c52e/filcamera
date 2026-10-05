"""Select exportable files using Git, or an explicit exported-source inventory."""
import json
import os
from pathlib import Path, PurePosixPath
import subprocess

MANIFEST = 'source-files.json'
EXCLUDED = {'.git', '.gradle', '.kotlin', '.build', 'build', '__pycache__', '.idea', '.vscode', 'artifacts', 'private', 'captures'}


def permitted(relative):
    return (relative.name not in {'local.properties', 'keystore.properties', MANIFEST}
            and not relative.name.startswith('.env')
            and relative.suffix.lower() not in {'.jks', '.keystore'}
            and not any(part in EXCLUDED for part in relative.parts))


def select_files(root):
    root = root.resolve()
    if (root / '.git').exists():
        command = ['git', '-c', f'safe.directory={root.as_posix()}', '-C', str(root)]
        result = subprocess.run(command + ['ls-files', '-z', '--cached', '--others', '--exclude-standard'], capture_output=True, check=True)
        paths = sorted(set(filter(None, result.stdout.split(b'\0'))))
        if not paths:
            return
        ignored = subprocess.run(command + ['check-ignore', '--no-index', '-z', '--stdin'], input=b'\0'.join(paths) + b'\0', capture_output=True)
        if ignored.returncode not in (0, 1):
            raise RuntimeError('Cannot determine Git ignore rules')
        excluded = set(ignored.stdout.split(b'\0'))
        names = [os.fsdecode(name) for name in paths if name not in excluded]
        strict = False
    else:
        path = root / MANIFEST
        if not path.is_file():
            raise RuntimeError('Source selection requires a Git checkout or exported source-files.json')
        record = json.loads(path.read_text(encoding='utf-8'))
        if record.get('schema') != 1 or not isinstance(record.get('files'), list):
            raise RuntimeError('Invalid exported source inventory')
        names = record['files']
        strict = True
    for name in sorted(set(names)):
        if not isinstance(name, str) or '\\' in name:
            raise RuntimeError('Invalid source inventory path')
        relative = PurePosixPath(name)
        if relative.is_absolute() or '..' in relative.parts or ':' in name:
            raise RuntimeError('Source inventory path escapes project')
        if not permitted(relative):
            continue
        file = root.joinpath(*relative.parts)
        # Refuse symlinks, including links through an ancestor directory.
        if any(parent.is_symlink() for parent in [file, *file.parents] if parent != root and root in parent.parents):
            raise RuntimeError(f'Symlink is not an exportable source: {name}')
        if not file.is_file():
            if strict:
                raise RuntimeError(f'Missing exported source: {name}')
            continue
        if not file.resolve().is_relative_to(root):
            raise RuntimeError(f'Source escapes project: {name}')
        yield file


def write_inventory(archive, root, files):
    data = dict(schema=1, files=sorted(p.relative_to(root).as_posix() for p in files))
    archive.writestr('FILCamera/' + MANIFEST, json.dumps(data, indent=2) + '\n')
