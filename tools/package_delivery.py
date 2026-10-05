"""Create a build-specific APK/source/notice bundle without signing credentials."""
import argparse
import json
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path
from build_support import ROOT, sha
from audit_public import audit, audit_apk, source_files
from build_integrity import verify_apk, source_snapshot, require_equal
from source_selection import MANIFEST, write_inventory
from delivery_transaction import delivery_transaction


def package(variant):
    apk = ROOT / f"app/build/outputs/apk/{variant}/app-{variant}.apk"
    failures = audit() + audit_apk(apk)
    if failures:
        raise RuntimeError("\n".join(failures))
    with zipfile.ZipFile(apk) as archive:
        verified_snapshot = verify_apk(archive)
    destination = ROOT / f"artifacts/delivery/{variant}"
    with delivery_transaction(destination) as output:
        source = output / "FILCamera-source.zip"
        files = list(source_files())
        snapshot = {p.relative_to(ROOT).as_posix(): sha(p) for p in files}
        require_equal(snapshot, verified_snapshot, "delivery source")
        with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
            for file in files:
                archive.write(file, "FILCamera/" + file.relative_to(ROOT).as_posix())
            write_inventory(archive, ROOT, files)
        require_equal(source_snapshot(), verified_snapshot, "source changed while archiving")
        with zipfile.ZipFile(source) as archive:
            import hashlib
            archived = {name.removeprefix("FILCamera/"): hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if name != "FILCamera/" + MANIFEST}
            require_equal(archived, verified_snapshot, "archived source")
        shipped = output / f"FILCamera-{variant}.apk"
        shutil.copy2(apk, shipped)
        with zipfile.ZipFile(shipped) as archive:
            require_equal(verify_apk(archive), verified_snapshot, "copied APK source")
            for name in ["runtime-dependencies.json", "third-party-notices.txt"]:
                data = archive.read("assets/" + name)
                expected = (ROOT / f".build/runtime-notices/{variant}" / name).read_bytes()
                if data != expected:
                    raise RuntimeError(f"APK contains stale {name}")
                (output / name).write_bytes(data)
            engine = json.loads(archive.read("assets/preview/engine.json"))
        def git(*args):
            if not (ROOT / ".git").exists():
                return None
            result = subprocess.run(["git", "-c", f"safe.directory={ROOT.as_posix()}", "-C", str(ROOT), *args], capture_output=True, text=True)
            return result.stdout.strip() if result.returncode == 0 else None
        status = git("status", "--porcelain")
        manifest = dict(schema=1, variant=variant, commit=git("rev-parse", "--verify", "HEAD"),
                        dirty=None if status is None else bool(status), sourceFiles=snapshot,
                        artifacts={name: sha(output / name) for name in [shipped.name, source.name, "runtime-dependencies.json", "third-party-notices.txt"]},
                        engine=engine,
                        buildInstructions="FILCamera/docs/BUILD.md",
                        nativeDependencies="FILCamera/THIRD_PARTY_NOTICES.md")
        (output / "build-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
        # Scan final metadata as well as source; never export local resolver paths.
        import re
        for file in output.glob("*.json"):
            if re.search(r"[A-Za-z]:[/\\]Users[/\\]|/(?:home|Users)/", file.read_text()):
                raise RuntimeError(f"Local path in delivery: {file.name}")
    print(f"Delivery: {destination}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=["debug", "release"], required=True)
    package(parser.parse_args().variant)
