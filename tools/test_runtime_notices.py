"""Packaging checks for inherited licenses, nested notices and failure behavior."""
import json
import tempfile
import unittest
import zipfile
import io
from pathlib import Path
from unittest.mock import patch
import runtime_notices as notices


class RuntimeNoticesTest(unittest.TestCase):
    def test_nested_jar_notices(self):
        jar = io.BytesIO()
        with zipfile.ZipFile(jar, 'w') as z:
            z.writestr('META-INF/NOTICE', 'Original copyright\n')
        aar = io.BytesIO()
        with zipfile.ZipFile(aar, 'w') as z:
            z.writestr('classes.jar', jar.getvalue())
        self.assertEqual([('classes.jar!/META-INF/NOTICE', 'Original copyright\n')], list(notices.embedded(aar.getvalue())))

    def run_fixture(self, license_xml, inherited=False):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            dependencies = root / '.build/dependencies'
            dependencies.mkdir(parents=True)
            assets = root / '.build/package/assets'
            assets.mkdir(parents=True)
            (assets / 'third-party-notices.txt').write_text('Base notice', encoding='utf-8')
            pom = root / 'module.pom'
            parent = root / 'parent.pom'
            pom.write_text('<project>' + ('' if inherited else license_xml) + '</project>', encoding='utf-8')
            parent.write_text('<project>' + license_xml + '</project>', encoding='utf-8')
            rows = [dict(coordinate='sample:module:1', pom=str(pom), parentPoms=[str(parent)] if inherited else [], artifacts=[])]
            (dependencies / 'release-resolved.json').write_text(json.dumps(rows), encoding='utf-8')
            with patch.object(notices, 'ROOT', root):
                notices.generate('release')
            output = (root / '.build/runtime-notices/release/runtime-dependencies.json').read_text()
            self.assertNotIn(str(root), output)
            return json.loads(output)

    def test_inherited_license(self):
        result = self.run_fixture('<licenses><license><name>Apache 2.0</name><url>https://www.apache.org/licenses/LICENSE-2.0.txt</url></license></licenses>', True)
        self.assertEqual(1, len(result['dependencies'][0]['parentPoms']))

    def test_missing_license_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'Review new/missing'):
            self.run_fixture('')

    def test_unknown_license_fails(self):
        with self.assertRaisesRegex(RuntimeError, 'Review new/missing'):
            self.run_fixture('<licenses><license><name>Different license</name><url>https://example.org/license</url></license></licenses>')


if __name__ == '__main__':
    unittest.main()
