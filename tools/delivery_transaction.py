"""Publish a complete delivery directory with locking, rollback and crash recovery."""
from contextlib import contextmanager
import os
from pathlib import Path
import shutil
import tempfile
import time


def remove_owned(path, parent):
    resolved = path.resolve()
    if path.is_symlink() or resolved.parent != parent.resolve() or not path.name.startswith('.'):
        raise RuntimeError('Refusing cleanup outside the delivery staging directory')
    if path.exists():
        shutil.rmtree(path)


@contextmanager
def locked(path):
    with path.open('a+b') as handle:
        if handle.tell() == 0:
            handle.write(b'0')
            handle.flush()
        handle.seek(0)
        if os.name == 'nt':
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == 'nt':
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def replace_with_retry(source, destination):
    # Windows scanners/indexers may hold freshly written APK/archive handles.
    # Retry only sharing/access errors; never remove the target as a workaround.
    for attempt in range(8):
        try:
            return os.replace(source, destination)
        except OSError as error:
            if getattr(error, 'winerror', None) not in (5, 32, 33) or attempt == 7:
                raise
            time.sleep(min(0.2 * 2 ** attempt, 2.0))


@contextmanager
def delivery_transaction(output):
    output = Path(output)
    parent = output.parent
    parent.mkdir(parents=True, exist_ok=True)
    backup = parent / ('.' + output.name + '.previous')
    if output.is_symlink() or backup.is_symlink():
        raise RuntimeError('Delivery paths must not be symlinks')
    with locked(parent / ('.' + output.name + '.lock')):
        # A killed process may have moved the old directory but not published the new one.
        if backup.exists() and not output.exists():
            replace_with_retry(backup, output)
        elif backup.exists():
            remove_owned(backup, parent)
        stage = Path(tempfile.mkdtemp(prefix='.' + output.name + '.pending-', dir=parent))
        verified = False
        try:
            yield stage
            verified = True
            had_previous = output.exists()
            if had_previous:
                replace_with_retry(output, backup)
            try:
                replace_with_retry(stage, output)
            except BaseException:
                if had_previous and not output.exists():
                    replace_with_retry(backup, output)
                raise
            # Keep the prior complete delivery as a recovery copy until the next run.
        except BaseException as error:
            if verified and stage.exists():
                error.add_note(f"Validated delivery retained at {stage}; previous delivery is at {output} or {backup}")
            raise
        finally:
            if not verified:
                remove_owned(stage, parent)
