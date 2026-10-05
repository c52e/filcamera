"""Bundle resolved runtime coordinates and original embedded license/NOTICE texts."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile
from build_support import ROOT


def sha(data):
    return hashlib.sha256(data).hexdigest()


def embedded(data, prefix=""):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for name in sorted(archive.namelist()):
            if name.endswith("/"):
                continue
            if re.search(r"(?:^|/)(?:LICENSE|NOTICE|COPYING|COPYRIGHT)(?:[._-]|$)", name, re.I):
                yield prefix + name, archive.read(name).decode("utf-8", errors="strict")
            elif name.endswith(".jar"):
                yield from embedded(archive.read(name), prefix + name + "!/")


def generate(variant):
    rows = json.loads((ROOT / f".build/dependencies/{variant}-resolved.json").read_text())
    result, sections = [], []
    for row in rows:
        coordinate = row["coordinate"]
        pom_data = Path(row["pom"]).read_bytes()
        pom = ET.fromstring(pom_data)
        def values(path):
            return [node.text.strip() for node in pom.findall(path) if node.text and node.text.strip()]
        licenses = [{"name": node.findtext("{*}name", ""), "url": node.findtext("{*}url", "")}
                    for node in pom.findall("{*}licenses/{*}license")]
        parent_evidence = []
        for parent_path in row.get("parentPoms", []):
            parent_data = Path(parent_path).read_bytes()
            parent = ET.fromstring(parent_data)
            parent_evidence.append(dict(sha256=sha(parent_data), name=Path(parent_path).name))
            if not licenses:
                licenses = [{"name": n.findtext("{*}name", ""), "url": n.findtext("{*}url", "")}
                            for n in parent.findall("{*}licenses/{*}license")]
        if not licenses or any(item["url"] not in {"http://www.apache.org/licenses/LICENSE-2.0.txt", "https://www.apache.org/licenses/LICENSE-2.0.txt"} for item in licenses):
            raise RuntimeError(f"Review new/missing runtime license before packaging: {coordinate}: {licenses}")
        record = dict(coordinate=coordinate, licenses=licenses, pomSha256=sha(pom_data), parentPoms=parent_evidence,
                      projectUrls=values("{*}url"), developers=values("{*}developers/{*}developer/{*}name"), artifacts=[])
        sections.append(f"\n=== {coordinate} ===\n" + json.dumps(licenses, ensure_ascii=False))
        for file in row["artifacts"]:
            file = Path(file)
            data = file.read_bytes()
            notices = list(embedded(data))
            record["artifacts"].append(dict(name=file.name, sha256=sha(data),
                                           notices=[dict(entry=n, sha256=sha(t.encode())) for n, t in notices]))
            for name, text in notices:
                sections.append(f"--- {file.name}!/{name} ---\n{text}")
        result.append(record)
    output = ROOT / f".build/runtime-notices/{variant}"
    output.mkdir(parents=True, exist_ok=True)
    inventory = dict(schema=1, variant=variant, dependencies=result)
    (output / "runtime-dependencies.json").write_text(json.dumps(inventory, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    base = (ROOT / ".build/package/assets/third-party-notices.txt").read_text(encoding="utf-8")
    (output / "third-party-notices.txt").write_text(base + "\n\nResolved Android runtime dependencies\n" + "\n\n".join(sections), encoding="utf-8")
    print(f"{variant}: {len(result)} runtime modules, {sum(len(a['notices']) for r in result for a in r['artifacts'])} embedded notices")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--variant", choices=["debug", "release"], required=True)
    generate(parser.parse_args().variant)
