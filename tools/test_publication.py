"""Regression coverage for source selection and transactional delivery."""
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile
from source_selection import select_files, write_inventory, MANIFEST
from delivery_transaction import delivery_transaction


class SourceSelectionTest(unittest.TestCase):
    def test_git_ignored_and_tracked_ignored_files_are_excluded(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root/'main.py').write_text('pass')
            (root/'local-helper.ps1').write_text('placeholder')
            subprocess.run(['git','-C',str(root),'add','local-helper.ps1'],check=True)
            (root/'.gitignore').write_text('local-helper.ps1\nprivate-*.txt\n')
            (root/'private-example.txt').write_text('placeholder')
            (root/'nested').mkdir()
            (root/'nested/.gitignore').write_text('local.txt\n')
            (root/'nested/local.txt').write_text('placeholder')
            names={p.relative_to(root).as_posix() for p in select_files(root)}
            self.assertIn('main.py',names)
            self.assertNotIn('local-helper.ps1',names)
            self.assertNotIn('private-example.txt',names)
            self.assertNotIn('nested/local.txt',names)

    def test_export_without_git_uses_inventory_not_extra_local_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/'main.py').write_text('pass')
            stream=io.BytesIO()
            with zipfile.ZipFile(stream,'w') as archive: write_inventory(archive,root,[root/'main.py'])
            with zipfile.ZipFile(stream) as archive:
                (root/MANIFEST).write_bytes(archive.read('FILCamera/'+MANIFEST))
            (root/'local-helper.ps1').write_text('placeholder')
            self.assertEqual(['main.py'],[p.name for p in select_files(root)])

    def test_inventory_traversal_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp);(root/MANIFEST).write_text(json.dumps(dict(schema=1,files=['../outside.py'])))
            with self.assertRaises(RuntimeError):list(select_files(root))

    def test_missing_inventory_fails_closed(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(RuntimeError):list(select_files(Path(tmp)))


class DeliveryTransactionTest(unittest.TestCase):
    def test_validation_failure_keeps_previous(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'debug';output.mkdir();(output/'old').write_bytes(b'previous')
            with self.assertRaisesRegex(RuntimeError,'validation failed'):
                with delivery_transaction(output) as stage:
                    (stage/'new').write_bytes(b'new')
                    raise RuntimeError('validation failed')
            self.assertEqual(b'previous',(output/'old').read_bytes())
            self.assertFalse((output/'new').exists())
            self.assertFalse(list(Path(tmp).glob('.debug.pending-*')))

    def test_publish_failure_rolls_back(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'debug';output.mkdir();(output/'old').write_bytes(b'previous')
            replace=os.replace
            def fail_stage(source,destination):
                if '.pending-' in Path(source).name: raise OSError('rename blocked')
                return replace(source,destination)
            with patch('delivery_transaction.os.replace',side_effect=fail_stage):
                with self.assertRaises(OSError):
                    with delivery_transaction(output) as stage:(stage/'new').write_text('new')
            self.assertEqual(b'previous',(output/'old').read_bytes())
            self.assertEqual(1, len(list(Path(tmp).glob('.debug.pending-*'))))

    def test_crash_backup_recovered_before_validation(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'debug';backup=Path(tmp)/'.debug.previous';backup.mkdir();(backup/'old').write_text('previous')
            with self.assertRaises(RuntimeError):
                with delivery_transaction(output):
                    self.assertTrue((output/'old').is_file())
                    raise RuntimeError('later validation failure')
            self.assertTrue((output/'old').is_file())

    def test_windows_transient_access_error_is_retried(self):
        from delivery_transaction import replace_with_retry
        error = PermissionError('temporary sharing conflict')
        error.winerror = 5
        with patch('delivery_transaction.os.replace', side_effect=[error, error, None]) as replace, patch('delivery_transaction.time.sleep') as sleep:
            replace_with_retry('source', 'destination')
            self.assertEqual(3, replace.call_count)
            self.assertEqual(2, sleep.call_count)

    def test_windows_persistent_error_is_bounded(self):
        from delivery_transaction import replace_with_retry
        error = PermissionError('sharing conflict')
        error.winerror = 32
        with patch('delivery_transaction.os.replace', side_effect=error) as replace, patch('delivery_transaction.time.sleep'):
            with self.assertRaises(PermissionError): replace_with_retry('source', 'destination')
            self.assertEqual(8, replace.call_count)

    def test_success_replaces_whole_directory(self):
        with tempfile.TemporaryDirectory() as tmp:
            output=Path(tmp)/'debug';output.mkdir();(output/'old').write_text('previous')
            with delivery_transaction(output) as stage:(stage/'new').write_text('new')
            self.assertEqual(['new'],[p.name for p in output.iterdir()])
            self.assertTrue((Path(tmp)/'.debug.previous/old').is_file())


if __name__=='__main__': unittest.main()
