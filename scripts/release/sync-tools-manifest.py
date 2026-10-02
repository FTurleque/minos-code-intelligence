#!/usr/bin/env python3
"""Rewrite (or check) the ARG lines of docker/Dockerfile.mcp.release from embedded-tools.json.

embedded-tools.json is the single description of the tools MINOS ships; the Docker release image is one of
its consumers. Docker needs the values as literal ``ARG`` lines (they are build inputs and part of the layer
cache key), so this script is the only thing allowed to change them:

    python scripts/release/sync-tools-manifest.py            # check: exit 1 when a line differs
    python scripts/release/sync-tools-manifest.py --write    # rewrite the lines that differ

``scripts/quality/check-tools-manifest.py`` fails the build when the two disagree.
"""
from __future__ import annotations

import argparse
import importlib.util
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GATE_PATH = ROOT / "scripts" / "quality" / "check-tools-manifest.py"


def load_gate():
    spec = importlib.util.spec_from_file_location("check_tools_manifest", GATE_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sync(root: Path, write: bool) -> list[str]:
    gate = load_gate()
    failures: list[str] = []
    catalog = gate.load_catalog(root, failures)
    if catalog is None:
        return failures
    expected = gate.expected_docker_args(catalog)
    path = root / gate.DOCKERFILE
    lines = path.read_text(encoding="utf-8").splitlines(keepends=True)
    changed = False
    for index, line in enumerate(lines):
        match = gate.ARG_LINE.match(line.rstrip("\r\n"))
        if not match or match.group("name") not in expected:
            continue
        value = expected[match.group("name")][0]
        if match.group("value") != value:
            failures.append(f"{gate.DOCKERFILE}:{index + 1}: ARG {match.group('name')} is {match.group('value')}, "
                            f"embedded-tools.json says {value}")
            ending = line[len(line.rstrip("\r\n")):]
            lines[index] = f"ARG {match.group('name')}={value}{ending}"
            changed = True
    present = {gate.ARG_LINE.match(line.rstrip("\r\n")).group("name")
               for line in lines if gate.ARG_LINE.match(line.rstrip("\r\n"))}
    for name in sorted(set(expected) - present):
        failures.append(f"{gate.DOCKERFILE}: ARG {name} is missing; add it where its tool is installed")
    if write and changed:
        path.write_text("".join(lines), encoding="utf-8", newline="")
    return failures if not write else [failure for failure in failures if "is missing" in failure]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--write", action="store_true", help="rewrite the ARG lines that differ")
    args = parser.parse_args(argv)
    failures = sync(args.root.resolve(), args.write)
    for failure in failures:
        print(f"SYNC TOOLS MANIFEST: {failure}", file=sys.stderr)
    if failures:
        return 1
    print("SYNC TOOLS MANIFEST SUCCESS (the Dockerfile ARG lines match embedded-tools.json)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
