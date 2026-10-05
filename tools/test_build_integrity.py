"""Reject mismatched source and native bytes before publishing a delivery."""
import hashlib
import io
import json
import unittest
import zipfile
from unittest.mock import patch
import build_integrity as integrity


class BuildIntegrityTest(unittest.TestCase):
    def verify(self, changed_source=False, changed_library=False, stale_native=False):
        stock = 'app/src/main/java/io/github/c52e/filcamera/FilmStocks.kt'
        snapshot = {stock: 'stock-hash', 'app/native/bridge.cpp': 'native-hash', 'tools/build.py': 'build-hash'}
        digest = hashlib.sha256(b'library').hexdigest()
        film = dict(librarySha256=digest, runtimeSha256=digest, assets={},
                    spatialAdapters={'app/native/bridge.cpp': 'old' if stale_native else 'native-hash'},
                    buildTools={'tools/build.py': 'build-hash'}, recipeAdapterSha256='stock-hash')
        raw = dict(librarySha256=digest, runtimeSha256=digest, sources={})
        data = io.BytesIO()
        with zipfile.ZipFile(data, 'w') as z:
            z.writestr('assets/build-source.json', json.dumps(dict(sourceFiles=snapshot)))
            z.writestr('assets/preview/engine.json', json.dumps(film))
            z.writestr('assets/raw-vulkan/engine.json', json.dumps(raw))
            for name in ['libfilcamera_film.so', 'librawvulkan.so', 'libfilmcamera_gpu.so']:
                z.writestr('lib/arm64-v8a/' + name, b'changed' if changed_library else b'library')
        current = dict(snapshot)
        if changed_source:
            current['new-file.kt'] = 'new'
        with patch.object(integrity, 'source_snapshot', return_value=current), zipfile.ZipFile(data) as z:
            return integrity.verify_apk(z)

    def test_matching_build(self):
        self.assertTrue(self.verify())

    def test_changed_source(self):
        with self.assertRaisesRegex(RuntimeError, 'APK/source snapshot'):
            self.verify(changed_source=True)

    def test_changed_native_bytes(self):
        with self.assertRaisesRegex(RuntimeError, 'libfilcamera_film'):
            self.verify(changed_library=True)

    def test_stale_native_source(self):
        with self.assertRaisesRegex(RuntimeError, 'bridge.cpp'):
            self.verify(stale_native=True)


if __name__ == '__main__':
    unittest.main()
