"""Validate prepared native artifacts and bind Android builds to their source snapshot."""
import hashlib
import json
from pathlib import Path
from build_support import ROOT, sha
from audit_public import source_files


def source_snapshot():
    return {p.relative_to(ROOT).as_posix(): sha(p) for p in source_files()}


def require_equal(actual, expected, label):
    if actual != expected:
        raise RuntimeError(f"Stale or mismatched {label}; rebuild from current source")


def verify_native():
    film = json.loads((ROOT / '.build/package/assets/preview/engine.json').read_text())
    raw = json.loads((ROOT / '.build/raw-vulkan/assets/raw-vulkan/engine.json').read_text())
    current = {p.relative_to(ROOT).as_posix(): sha(p) for p in (ROOT / 'app/native').rglob('*') if p.is_file()}
    require_equal(current, film['spatialAdapters'], 'film native source')
    current_raw = {p.relative_to(ROOT).as_posix(): sha(p) for p in (ROOT / 'raw-vulkan').rglob('*') if p.is_file() and 'build' not in p.relative_to(ROOT / 'raw-vulkan').parts}
    current_raw.update({p.relative_to(ROOT).as_posix(): sha(p) for p in (ROOT / 'gpu-runtime').iterdir() if p.is_file()})
    require_equal(current_raw, raw['sources'], 'RAW/runtime source')
    for path, expected in [
        ('.build/package/jniLibs/arm64-v8a/libfilcamera_film.so', film['librarySha256']),
        ('.build/raw-vulkan/jniLibs/arm64-v8a/librawvulkan.so', raw['librarySha256']),
        ('.build/raw-vulkan/jniLibs/arm64-v8a/libfilmcamera_gpu.so', raw['runtimeSha256'])]:
        require_equal(sha(ROOT / path), expected, path)
    require_equal(film['runtimeSha256'], raw['runtimeSha256'], 'shared GPU runtime')
    require_equal(sha(ROOT / 'app/src/main/java/io/github/c52e/filcamera/FilmStocks.kt'), film['recipeAdapterSha256'], 'profile mapping')
    assets = json.loads((ROOT / 'app/model-data/manifest.json').read_text())['assets']
    require_equal(assets, film['assets'], 'model inventory')
    for name, expected in assets.items():
        require_equal(sha(ROOT / 'app/model-data/assets' / name), expected, name)
        require_equal(sha(ROOT / '.build/package/assets/lab/assets' / name), expected, 'packaged ' + name)
    # Build scripts are part of the native configuration, not merely packaging.
    require_equal({name: sha(ROOT / name) for name in film['buildTools']}, film['buildTools'], 'native build tools')


def verify_apk(archive):
    snapshot = json.loads(archive.read('assets/build-source.json'))['sourceFiles']
    require_equal(source_snapshot(), snapshot, 'APK/source snapshot')
    film = json.loads(archive.read('assets/preview/engine.json'))
    raw = json.loads(archive.read('assets/raw-vulkan/engine.json'))
    for name, expected in [('libfilcamera_film.so', film['librarySha256']),
                           ('librawvulkan.so', raw['librarySha256']),
                           ('libfilmcamera_gpu.so', raw['runtimeSha256'])]:
        require_equal(hashlib.sha256(archive.read('lib/arm64-v8a/' + name)).hexdigest(), expected, name)
    require_equal(film['runtimeSha256'], raw['runtimeSha256'], 'APK shared runtime')
    for group in [film['spatialAdapters'], raw['sources'], film['buildTools']]:
        for name, expected in group.items():
            require_equal(snapshot.get(name), expected, name)
    require_equal(snapshot['app/src/main/java/io/github/c52e/filcamera/FilmStocks.kt'], film['recipeAdapterSha256'], 'APK profile mapping')
    for name, expected in film['assets'].items():
        require_equal(snapshot['app/model-data/assets/' + name], expected, name)
        require_equal(hashlib.sha256(archive.read('assets/lab/assets/' + name)).hexdigest(), expected, 'APK ' + name)
    return snapshot
