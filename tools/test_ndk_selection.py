"""Exercise pinned NDK selection with conflicting runner environment variables."""
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from build_support import ndk_path, NDK_VERSION

class NdkSelectionTest(unittest.TestCase):
    def make_ndk(self,path,version):
        path.mkdir(parents=True)
        (path/'source.properties').write_text(f'Pkg.Revision = {version}\n',encoding='utf-8')
        return path.resolve()

    def test_sdk_installed_version_without_override(self):
        with tempfile.TemporaryDirectory() as tmp:
            sdk=Path(tmp);expected=self.make_ndk(sdk/'ndk'/NDK_VERSION,NDK_VERSION)
            with patch.dict(os.environ,{'ANDROID_HOME':str(sdk)},clear=True):
                self.assertEqual(expected,ndk_path())

    def test_runner_override_still_rejected_when_sdk_has_correct_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            sdk=Path(tmp);self.make_ndk(sdk/'ndk'/NDK_VERSION,NDK_VERSION)
            other=self.make_ndk(sdk/'ndk'/'other','27.3.13750724')
            with patch.dict(os.environ,{'ANDROID_HOME':str(sdk),'ANDROID_NDK_HOME':str(other)},clear=True):
                with self.assertRaises(RuntimeError) as error:ndk_path()
                self.assertIn('ANDROID_NDK_HOME',str(error.exception))
                self.assertIn(str(other),str(error.exception))
                self.assertIn('27.3.13750724',str(error.exception))

    def test_explicit_pinned_selection_overrides_other_ndk_variables(self):
        with tempfile.TemporaryDirectory() as tmp:
            ndk=self.make_ndk(Path(tmp)/'pinned',NDK_VERSION)
            with patch.dict(os.environ,{'ANDROID_NDK_HOME':str(ndk),'ANDROID_NDK_ROOT':'unused-old-ndk'},clear=True):
                self.assertEqual(ndk,ndk_path())

    def test_similar_revision_does_not_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            ndk=self.make_ndk(Path(tmp)/'wrong',NDK_VERSION+'9')
            with patch.dict(os.environ,{'ANDROID_NDK_HOME':str(ndk)},clear=True):
                with self.assertRaises(RuntimeError):ndk_path()

if __name__=='__main__':unittest.main()
