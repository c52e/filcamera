"""Optimize a verified upstream SPIR-V include without changing its compiler provenance."""
import argparse
from pathlib import Path
import re
import struct
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--optimizer", required=True)
    parser.add_argument("--validator", required=True)
    args = parser.parse_args()
    words = [int(word, 16) for word in re.findall(r"0x[0-9a-fA-F]+", args.input.read_text())]
    if not words or words[0] != 0x07230203:
        raise RuntimeError("Invalid SPIR-V include")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=args.output.parent) as directory:
        source = Path(directory) / "input.spv"
        output = Path(directory) / "optimized.spv"
        source.write_bytes(struct.pack("<" + "I" * len(words), *words))
        subprocess.run([args.optimizer, "--target-env=vulkan1.1", "-O", str(source), "-o", str(output)], check=True)
        subprocess.run([args.validator, "--target-env", "vulkan1.1", str(output)], check=True)
        data = output.read_bytes()
    optimized = struct.unpack("<" + "I" * (len(data) // 4), data)
    args.output.write_text("{" + ",".join(hex(word) for word in optimized) + "};\n", encoding="utf-8")


if __name__ == "__main__":
    main()
