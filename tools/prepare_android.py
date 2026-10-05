"""Gradle entry point for native integrity, runtime notices and source identity."""
import argparse
import json
from build_support import ROOT
from build_integrity import verify_native, source_snapshot
from build import prepare_notices
from runtime_notices import generate


def prepare(variant):
    verify_native()
    prepare_notices()
    generate(variant)
    output = ROOT / f'.build/runtime-notices/{variant}/build-source.json'
    output.write_text(json.dumps(dict(schema=1, sourceFiles=source_snapshot()), indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--variant', required=True, choices=['debug', 'release'])
    prepare(parser.parse_args().variant)
