#!/usr/bin/env python3
"""Fail when MINOS module ownership drifts back to compiler allowlists or shared source roots."""

from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = (
    "minos-domain",
    "minos-engine",
    "minos-runtime-local",
    "minos-storage-local",
    "minos-provider-scip",
    "minos-integration-git",
    "minos-application",
    "minos-nexus",
    "minos-cli",
    "minos-api",
    "minos-mcp",
    "minos-app",
)
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)


def fail(message: str) -> None:
    raise RuntimeError(message)


def check_pom(module: str) -> None:
    pom = ROOT / module / "pom.xml"
    if not pom.is_file():
        fail(f"{module}: missing pom.xml")
    root = ET.parse(pom).getroot()
    build = root.find("m:build", NS)
    if build is None:
        return

    if build.find("m:sourceDirectory", NS) is not None:
        fail(f"{module}: custom sourceDirectory is forbidden")
    if build.find("m:testSourceDirectory", NS) is not None:
        fail(f"{module}: custom testSourceDirectory is forbidden")

    for plugin in build.findall("m:plugins/m:plugin", NS):
        artifact = plugin.findtext("m:artifactId", default="", namespaces=NS)
        if artifact != "maven-compiler-plugin":
            continue
        configuration = plugin.find("m:configuration", NS)
        if configuration is None:
            continue
        if configuration.find("m:includes", NS) is not None:
            fail(f"{module}: maven-compiler-plugin <includes> is forbidden")
        if configuration.find("m:excludes", NS) is not None:
            fail(f"{module}: maven-compiler-plugin <excludes> is forbidden")


def check_java_layout() -> tuple[int, dict[str, int]]:
    owners: dict[str, str] = {}
    counts: dict[str, int] = {}
    total = 0

    for module in MODULES:
        source_root = ROOT / module / "src" / "main" / "java"
        module_count = 0
        if source_root.is_dir():
            for source in sorted(source_root.rglob("*.java")):
                relative = source.relative_to(source_root).as_posix()
                previous = owners.get(relative)
                if previous is not None:
                    fail(f"duplicate production source {relative}: {previous} and {module}")
                owners[relative] = module

                text = source.read_text(encoding="utf-8")
                match = PACKAGE.search(text)
                if match:
                    expected_parent = Path(*match.group(1).split("."))
                    if source.relative_to(source_root).parent != expected_parent:
                        fail(
                            f"{module}: package/path mismatch for {relative}: "
                            f"package={match.group(1)}"
                        )
                module_count += 1
                total += 1
        counts[module] = module_count

    return total, counts


def main() -> int:
    try:
        for module in MODULES:
            check_pom(module)
        total, counts = check_java_layout()
        for module in MODULES:
            print(f"M21 module-boundary {module}: sources={counts[module]}")
        print(f"M21 MODULE BOUNDARY CONSISTENCY SUCCESS (modules={len(MODULES)}, sources={total})")
        return 0
    except Exception as exception:
        print(f"M21 MODULE BOUNDARY CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
