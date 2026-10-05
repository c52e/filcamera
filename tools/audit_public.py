"""Audit publishable source, localization, model integrity and optional APK assets."""
import argparse
import hashlib
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
import json

ROOT = Path(__file__).resolve().parents[1]
EXCLUDED = {".git", ".gradle", ".kotlin", ".build", "build", "__pycache__", ".idea", ".vscode", "artifacts", "private", "captures"}
TEXT_SUFFIXES = {".kt", ".kts", ".cpp", ".h", ".hpp", ".comp", ".py", ".xml", ".md", ".txt", ".json", ".cmake", ".properties", ".yml"}


def source_files():
    from source_selection import select_files
    yield from select_files(ROOT)


def audit():
    errors = []
    private = re.compile(r"[A-Za-z]:[/\\]Users[/\\]|/(?:home|Users)/[A-Za-z0-9_.-]+/|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{25,}")
    for file in source_files():
        relative = file.relative_to(ROOT)
        if file.suffix.lower() in {".dng", ".jpg", ".jpeg", ".apk", ".aab", ".jks", ".keystore", ".hprof", ".log"}:
            errors.append(f"Private/binary material needs exclusion: {relative}")
        if file.suffix in TEXT_SUFFIXES:
            text = file.read_text(encoding="utf-8")
            if private.search(text):
                errors.append(f"Possible private path/credential: {relative}")
            if file.suffix in {".kt", ".kts", ".cpp", ".h", ".hpp", ".comp", ".py"} and re.search(r"[\u4e00-\u9fff]", text):
                errors.append(f"Chinese text outside language resources: {relative}")
    def resources(qualifier):
        return {node.attrib["name"]: node.text or "" for node in ET.parse(ROOT / f"strings/src/main/res/{qualifier}/strings.xml").getroot()}
    english, chinese = resources("values"), resources("values-zh")
    if set(english) - {"app_name"} != set(chinese):
        errors.append("English/Chinese resource keys differ")
    for key in chinese:
        if sorted(re.findall(r"%\d+\$[a-z]", english.get(key, ""))) != sorted(re.findall(r"%\d+\$[a-z]", chinese[key])):
            errors.append(f"Translation placeholders differ: {key}")
    model = ROOT / "app/model-data"
    inventory = json.loads((model / "manifest.json").read_text())["assets"]
    actual = {p.relative_to(model / "assets").as_posix() for p in (model / "assets").rglob("*") if p.is_file()}
    if actual != set(inventory):
        errors.append("Uninventoried/missing model assets")
    for name, expected in inventory.items():
        if hashlib.sha256((model / "assets" / name).read_bytes()).hexdigest() != expected:
            errors.append(f"Model checksum mismatch: {name}")
    return errors


def audit_apk(path):
    errors = []
    with zipfile.ZipFile(path) as apk:
        names = apk.namelist()
        for name in names:
            if name.startswith("assets/") and (name.endswith((".dng", ".jpg", ".png", ".f32", ".fclinear", ".rgba16f")) or "/inputs/" in name):
                errors.append(f"Unexpected photograph/test fixture in APK: {name}")
            if name.endswith(".so") and re.search(rb"[A-Za-z]:[/\\]Users[/\\]|/(?:home|Users)/[A-Za-z0-9_.-]+/", apk.read(name)):
                errors.append(f"Developer path embedded in native library: {name}")
        for name in ["assets/third-party-notices.txt", "assets/runtime-dependencies.json", "assets/build-source.json", "assets/CHANGELOG.txt", "assets/preview/engine.json", "assets/lab/manifest.json"]:
            if name not in names:
                errors.append(f"Missing packaged notice/identity: {name}")
        if "assets/lab/manifest.json" in names and "assets/preview/engine.json" in names:
            capture = json.loads(apk.read("assets/lab/manifest.json"))
            preview = json.loads(apk.read("assets/preview/engine.json"))
            if capture.get("assets") != preview.get("assets") or capture.get("inputs") != []:
                errors.append("Capture/preview inventories differ or private inputs are present")
    return errors


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    failures = audit() + (audit_apk(args.apk) if args.apk else [])
    if failures:
        print("\n".join(failures), file=sys.stderr)
        sys.exit(1)
    print("Public source audit passed; model hashes and EN/ZH resources match.")
