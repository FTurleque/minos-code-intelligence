#!/usr/bin/env python3
"""Validate MINOS release SBOM, notices and deterministic distribution manifest."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--distribution", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--strict-licenses", action="store_true")
    return parser.parse_args()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def license_count(component: dict) -> int:
    count = 0
    for entry in component.get("licenses", []) or []:
        if isinstance(entry, dict) and isinstance(entry.get("license"), dict):
            value = entry["license"]
            if value.get("id") or value.get("name") or value.get("url"):
                count += 1
    return count


def is_minos(component: dict) -> bool:
    group = str(component.get("group") or "").strip()
    purl = str(component.get("purl") or "").strip()
    return group == "com.minos" or purl.startswith("pkg:maven/com.minos/")


def main() -> int:
    args = parse_args()
    try:
        root = args.distribution.resolve()
        sbom_path = root / "supply-chain" / "minos.cdx.json"
        notices_path = root / "supply-chain" / "THIRD-PARTY-NOTICES.txt"
        manifest_path = root / "RELEASE-MANIFEST.json"
        version_path = root / "VERSION"
        for path in (sbom_path, notices_path, manifest_path, version_path):
            if not path.is_file():
                raise RuntimeError(f"required release evidence missing: {path}")

        sbom = json.loads(sbom_path.read_text(encoding="utf-8"))
        if sbom.get("bomFormat") != "CycloneDX" or str(sbom.get("specVersion")) != "1.6":
            raise RuntimeError("release SBOM must be CycloneDX 1.6")
        components = [item for item in (sbom.get("components") or []) if isinstance(item, dict)]
        third_party = [item for item in components if not is_minos(item)]
        if not third_party:
            raise RuntimeError("release SBOM contains no third-party components")
        unknown = [item for item in third_party if license_count(item) == 0]
        if args.strict_licenses and unknown:
            names = ", ".join(str(item.get("name") or "<unnamed>") for item in unknown[:10])
            raise RuntimeError(f"{len(unknown)} third-party components have no license metadata: {names}")

        notices = notices_path.read_text(encoding="utf-8")
        match = re.search(r"^Third-party components:\s*(\d+)\s*$", notices, re.MULTILINE)
        if not match:
            raise RuntimeError("third-party notices do not expose their component count")
        if int(match.group(1)) != len(third_party):
            raise RuntimeError(
                f"notices/SBOM component mismatch: notices={match.group(1)} sbom={len(third_party)}"
            )

        version_metadata = {}
        for line in version_path.read_text(encoding="ascii").splitlines():
            if "=" in line:
                key, value = line.split("=", 1)
                version_metadata[key.strip()] = value.strip()
        if version_metadata.get("version") != args.version:
            raise RuntimeError("VERSION file does not match requested version")
        if version_metadata.get("commit") != args.commit:
            raise RuntimeError("VERSION file does not match requested commit")

        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        if manifest.get("schemaVersion") != 1 or manifest.get("product") != "MINOS Code Intelligence":
            raise RuntimeError("release manifest schema/product mismatch")
        if manifest.get("version") != args.version or manifest.get("commit") != args.commit:
            raise RuntimeError("release manifest provenance mismatch")
        if manifest.get("sbom") != "supply-chain/minos.cdx.json":
            raise RuntimeError("release manifest SBOM pointer mismatch")
        if manifest.get("thirdPartyNotices") != "supply-chain/THIRD-PARTY-NOTICES.txt":
            raise RuntimeError("release manifest notices pointer mismatch")

        entries = manifest.get("files") or []
        if not isinstance(entries, list) or not entries:
            raise RuntimeError("release manifest contains no files")
        declared: set[str] = set()
        for entry in entries:
            relative = str(entry.get("path") or "")
            expected_hash = str(entry.get("sha256") or "").lower()
            expected_size = entry.get("size")
            if not relative or relative in declared:
                raise RuntimeError(f"duplicate/empty release manifest path: {relative!r}")
            declared.add(relative)
            path = root / Path(relative)
            if not path.is_file():
                raise RuntimeError(f"manifested file is missing: {relative}")
            if path.stat().st_size != expected_size:
                raise RuntimeError(f"manifested file size mismatch: {relative}")
            if sha256(path) != expected_hash:
                raise RuntimeError(f"manifested file hash mismatch: {relative}")

        actual = {
            path.relative_to(root).as_posix()
            for path in root.rglob("*")
            if path.is_file() and path != manifest_path
        }
        if actual != declared:
            missing = sorted(actual - declared)
            stale = sorted(declared - actual)
            raise RuntimeError(f"release manifest file-set mismatch: unlisted={missing}, stale={stale}")

        print(
            "M21 SUPPLY-CHAIN EVIDENCE SUCCESS "
            f"(components={len(third_party)}, unknownLicenses={len(unknown)}, files={len(entries)})"
        )
        return 0
    except Exception as exception:
        print(f"M21 SUPPLY-CHAIN EVIDENCE FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
