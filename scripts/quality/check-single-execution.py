#!/usr/bin/env python3
"""Fail when a heavy pull-request gate runs more than once per PR.

Audit finding C2: three workflows (pr-ci, M19, M20) each ran a complete ``./mvnw clean verify``,
``product-facts.py``, ``check-jacoco.py`` and the Linux sandbox toolchain installation on the same
PR. The M19 and M20 workflows were retired because ``pr-ci.yml`` already carried every one of their
assertions (see docs/audit/archive/2026-09/S23-SUIVI.md, "Lot 3"). This gate keeps it that way.

The four controls it owns are the expensive ones that a duplicated workflow would copy:

* a Maven ``verify`` (``mvnw`` or ``mvnw.cmd``);
* ``scripts/docs/product-facts.py``;
* ``scripts/quality/check-jacoco.py``;
* ``scripts/ci/install-linux-sandbox-toolchain.sh``.

Rules (line-based, comments, quoted ``paths:`` filter entries and ``--self-test`` lines ignored: a gate's own
self-test, such as ``check-jacoco.py --self-test``, is not an execution of the control):

1. Among the workflows that start on ``pull_request`` or ``push``, only ``pr-ci.yml`` may run them.
   A second PR workflow that needs one of them must extend ``pr-ci.yml`` instead.
2. ``pr-ci.yml`` runs each exactly once per operating system: one ``mvnw`` verify and one
   ``mvnw.cmd`` verify, one JaCoCo gate per OS (the Windows one carries ``--skip-scope``), one
   ``product-facts.py``, one toolchain installation. Zero is a failure too: a gate whose only
   executor vanished is a gate that no longer runs.

Workflows started by hand only (``workflow_dispatch``: m0-java-ci, historical-qualification,
release) are replay tools, not PR gates, and are not in scope. What this gate does not prove: that
the single executor is reached on every event or free of an ``if:`` (the audit-remediation gates
read the job structure of ``pr-ci.yml``), nor that other scripts launched from a workflow do not
run Maven themselves.

Self-test: scripts/quality/test_check_single_execution.py.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - après l'ajout du dossier du script au chemin

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ".github/workflows"
PR_CI = "pr-ci.yml"

MAVEN_VERIFY = re.compile(r"(?:^|[\s/\\])mvnw(?P<cmd>\.cmd)?(?=\s).*\bverify\b")
PRODUCT_FACTS = re.compile(r"scripts/docs/product-facts\.py")
JACOCO = re.compile(r"scripts/quality/check-jacoco\.py")
SANDBOX_TOOLCHAIN = re.compile(r"scripts/ci/install-linux-sandbox-toolchain\.sh")
SELF_TEST = re.compile(r"(?:^|\s)--self-test\b")
AUTOMATIC_EVENTS =re.compile(r"^  (?:pull_request|pull_request_target|push)\s*:")


def command_lines(text: str) -> list[tuple[int, str]]:
    """Numbered lines that can execute something: no comment, no quoted ``paths:`` entry."""
    lines: list[tuple[int, str]] = []
    for number, raw in enumerate(text.splitlines(), start=1):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        item = stripped.removeprefix("- ").lstrip()
        if item[:1] in ("'", '"'):
            continue
        lines.append((number, item))
    return lines


def starts_automatically(text: str) -> bool:
    """True when the top-level ``on:`` block lists ``pull_request`` or ``push``."""
    inside = False
    for raw in text.splitlines():
        if re.match(r"^on\s*:", raw):
            inside = True
            if re.search(r"\b(?:pull_request|pull_request_target|push)\b", raw):
                return True
            continue
        if inside:
            if raw.strip() and not raw.startswith((" ", "\t", "#")):
                return False
            if AUTOMATIC_EVENTS.match(raw):
                return True
    return False


def controls_of(text: str) -> dict[str, list[int]]:
    found: dict[str, list[int]] = {
        "maven-verify-unix": [], "maven-verify-windows": [], "product-facts": [],
        "jacoco-linux": [], "jacoco-windows": [], "sandbox-toolchain": [],
    }
    for number, line in command_lines(text):
        if SELF_TEST.search(line):
            continue  # a gate's own self-test is not an execution of the control it guards
        match = MAVEN_VERIFY.search(line)
        if match:
            found["maven-verify-windows" if match.group("cmd") else "maven-verify-unix"].append(number)
        if PRODUCT_FACTS.search(line):
            found["product-facts"].append(number)
        if JACOCO.search(line):
            found["jacoco-windows" if "--skip-scope" in line else "jacoco-linux"].append(number)
        if SANDBOX_TOOLCHAIN.search(line):
            found["sandbox-toolchain"].append(number)
    return found


def check(root: Path) -> tuple[list[str], int, int]:
    directory = root / WORKFLOWS
    failures: list[str] = []
    files = sorted(list(directory.glob("*.yml")) + list(directory.glob("*.yaml"))) if directory.is_dir() else []
    automatic = 0
    pr_ci_seen = False
    for path in files:
        text = path.read_text(encoding="utf-8")
        if not starts_automatically(text):
            continue
        automatic += 1
        found = controls_of(text)
        if path.name == PR_CI:
            pr_ci_seen = True
            for name, lines in found.items():
                if len(lines) != 1:
                    where = f" (lines {', '.join(map(str, lines))})" if lines else ""
                    failures.append(
                        f"{WORKFLOWS}/{PR_CI}: `{name}` must run exactly once, found {len(lines)}{where}")
            continue
        for name, lines in found.items():
            for number in lines:
                failures.append(
                    f"{WORKFLOWS}/{path.name}:{number}: `{name}` runs in a second pull-request workflow; "
                    f"it already runs once in {PR_CI} (audit C2): extend {PR_CI} instead")
    if not pr_ci_seen:
        failures.append(f"{WORKFLOWS}/{PR_CI} not found or not started by pull_request/push: "
                        f"the single executor of the heavy gates is missing")
    return failures, len(files), automatic


def main() -> int:
    failures, total, automatic = check(gate_cli.parse_root(__doc__, DEFAULT_ROOT))
    if gate_cli.report_failures("SINGLE EXECUTION GATE", failures):
        return 1
    print(f"SINGLE EXECUTION GATE SUCCESS (workflows={total}, started on pull_request or push={automatic}, "
          f"controls=4, each run once per OS in {PR_CI})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
