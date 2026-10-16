#!/usr/bin/env python3
"""Create a deterministic SHA-256 manifest for a staged MINOS distribution."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--output", required=True, type=Path)
    # AUD-DEP-06: the Authenticode state is written, never presumed; release-signature.ps1 decides it.
    parser.add_argument("--authenticode-required", required=True, choices=("0", "1"))
    parser.add_argument("--authenticode-state", required=True, choices=AUTHENTICODE_STATES)
    return parser.parse_args()


AUTHENTICODE_STATES = ("unsigned", "signed")


def authenticode(required: str, state: str) -> dict:
    """The additive `authenticode` field: a required signature is `signed`, and only a required one can be."""
    if (required == "1") != (state == "signed"):
        raise RuntimeError(f"authenticode state '{state}' contradicts required={required}")
    return {"required": required == "1", "state": state}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    try:
        root = args.distribution.resolve()
        output = args.output.resolve()
        if not root.is_dir():
            raise RuntimeError(f"distribution not found: {root}")
        if output.parent != root:
            raise RuntimeError("release manifest must be written at the distribution root")

        files = []
        for path in sorted(root.rglob("*"), key=lambda item: item.as_posix().lower()):
            if not path.is_file() or path.resolve() == output:
                continue
            relative = path.relative_to(root).as_posix()
            files.append(
                {
                    "path": relative,
                    "sha256": sha256(path),
                    "size": path.stat().st_size,
                }
            )
        if not files:
            raise RuntimeError("distribution contains no files")

        manifest = {
            "schemaVersion": 1,
            "product": "MINOS Code Intelligence",
            "version": args.version,
            "commit": args.commit,
            "hashAlgorithm": "SHA-256",
            "sbom": "supply-chain/minos.cdx.json",
            "thirdPartyNotices": "supply-chain/THIRD-PARTY-NOTICES.txt",
            "files": files,
            "authenticode": authenticode(args.authenticode_required, args.authenticode_state),
        }
        output.write_text(
            json.dumps(manifest, indent=2, sort_keys=True, ensure_ascii=False) + "\n",
            encoding="utf-8",
            newline="\n",
        )
        print(f"M21 RELEASE MANIFEST SUCCESS (files={len(files)})")
        return 0
    except Exception as exception:
        print(f"M21 RELEASE MANIFEST FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
