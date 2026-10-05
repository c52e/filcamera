"""Export an audited source archive without Git history, build outputs or secrets."""
import argparse
from pathlib import Path
import zipfile
from audit_public import ROOT, audit, source_files
from source_selection import write_inventory


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "artifacts/FILCamera-source.zip")
    args = parser.parse_args()
    failures = audit()
    if failures:
        raise SystemExit("\n".join(failures))
    files = list(source_files())
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", zipfile.ZIP_DEFLATED) as archive:
        for file in files:
            archive.write(file, "FILCamera/" + file.relative_to(ROOT).as_posix())
        write_inventory(archive, ROOT, files)
    print(f"Exported {len(files)} files to {args.output}")


if __name__ == "__main__":
    main()
