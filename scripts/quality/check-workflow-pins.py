#!/usr/bin/env python3
"""Fail when a GitHub workflow uses a mutable third-party action or unpinned Inno Setup."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
USES = re.compile(r"\buses:\s*[\"']?(?P<target>[^@\s\"']+)@(?P<ref>[^\s\"'#]+)")
SHA = re.compile(r"[0-9a-fA-F]{40}")
INNO_VERSION = "6.7.1"


def main() -> int:
    failures: list[str] = []
    checked = 0
    for workflow in sorted(WORKFLOWS.glob("*.y*ml")):
        text = workflow.read_text(encoding="utf-8")
        for line_number, line in enumerate(text.splitlines(), start=1):
            match = USES.search(line)
            if match:
                target = match.group("target")
                ref = match.group("ref")
                if not target.startswith("./"):
                    checked += 1
                    if not SHA.fullmatch(ref):
                        failures.append(f"{workflow.relative_to(ROOT)}:{line_number}: mutable uses ref {target}@{ref}")
            if "choco install innosetup" in line.lower() and f"--version={INNO_VERSION}" not in line:
                failures.append(f"{workflow.relative_to(ROOT)}:{line_number}: Inno Setup must be pinned to {INNO_VERSION}")
    if failures:
        print("WORKFLOW SUPPLY-CHAIN PIN GATE FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print(f"WORKFLOW SUPPLY-CHAIN PIN GATE SUCCESS (external uses={checked}, Inno={INNO_VERSION})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
