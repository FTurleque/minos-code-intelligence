#!/usr/bin/env python3
"""Generate deterministic third-party notices from the MINOS CycloneDX SBOM."""

from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, order=True)
class ComponentNotice:
    group: str
    name: str
    version: str
    purl: str
    licenses: tuple[str, ...]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sbom", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--strict", action="store_true", help="Fail if a third-party component has no license metadata.")
    return parser.parse_args()


def license_labels(component: dict) -> tuple[str, ...]:
    labels: set[str] = set()
    for entry in component.get("licenses", []) or []:
        license_value = entry.get("license") if isinstance(entry, dict) else None
        if not isinstance(license_value, dict):
            continue
        identifier = str(license_value.get("id") or "").strip()
        name = str(license_value.get("name") or "").strip()
        url = str(license_value.get("url") or "").strip()
        label = identifier or name
        if url and label:
            label = f"{label} ({url})"
        elif url:
            label = url
        if label:
            labels.add(label)
    return tuple(sorted(labels))


def is_minos_component(component: dict) -> bool:
    group = str(component.get("group") or "").strip()
    purl = str(component.get("purl") or "").strip()
    return group == "com.minos" or purl.startswith("pkg:maven/com.minos/")


def load_components(sbom: Path) -> list[ComponentNotice]:
    data = json.loads(sbom.read_text(encoding="utf-8"))
    if data.get("bomFormat") != "CycloneDX":
        raise RuntimeError("SBOM bomFormat must be CycloneDX")
    if str(data.get("specVersion")) != "1.6":
        raise RuntimeError(f"SBOM specVersion must be 1.6, got {data.get('specVersion')!r}")

    notices: list[ComponentNotice] = []
    seen: set[tuple[str, str, str, str]] = set()
    for component in data.get("components", []) or []:
        if not isinstance(component, dict) or is_minos_component(component):
            continue
        group = str(component.get("group") or "").strip()
        name = str(component.get("name") or "").strip()
        version = str(component.get("version") or "").strip()
        purl = str(component.get("purl") or "").strip()
        if not name or not version:
            raise RuntimeError(f"SBOM component missing name/version: {component!r}")
        key = (group, name, version, purl)
        if key in seen:
            continue
        seen.add(key)
        notices.append(ComponentNotice(group, name, version, purl, license_labels(component)))
    return sorted(notices)


def render(components: list[ComponentNotice]) -> str:
    lines = [
        "MINOS Code Intelligence — Third-Party Notices",
        "",
        "Generated from the CycloneDX release SBOM. This inventory records dependency",
        "coordinates and the license metadata published by their Maven components.",
        "It does not replace the authoritative license text distributed by each project.",
        "",
        f"Third-party components: {len(components)}",
        "",
    ]
    for component in components:
        coordinate = ":".join(value for value in (component.group, component.name, component.version) if value)
        lines.append(coordinate)
        lines.append(f"  PURL: {component.purl or 'UNAVAILABLE'}")
        lines.append(f"  License: {', '.join(component.licenses) if component.licenses else 'UNKNOWN'}")
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def main() -> int:
    args = parse_args()
    try:
        if not args.sbom.is_file():
            raise RuntimeError(f"SBOM not found: {args.sbom}")
        components = load_components(args.sbom)
        if not components:
            raise RuntimeError("SBOM contains no third-party components")
        unknown = [component for component in components if not component.licenses]
        if args.strict and unknown:
            preview = ", ".join(
                ":".join(value for value in (item.group, item.name, item.version) if value)
                for item in unknown[:10]
            )
            raise RuntimeError(
                f"{len(unknown)} third-party component(s) have no license metadata: {preview}"
            )
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(render(components), encoding="utf-8", newline="\n")
        print(
            "M21 THIRD-PARTY NOTICES SUCCESS "
            f"(components={len(components)}, unknownLicenses={len(unknown)})"
        )
        return 0
    except Exception as exception:
        print(f"M21 THIRD-PARTY NOTICES FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
