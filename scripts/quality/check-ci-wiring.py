#!/usr/bin/env python3
"""Fail when the CI wiring that makes MINOS' controls binding has come undone (AUD-DEP-09, AUD-TST-07).

Three rules, all anchored on the structure of the workflows and never on a sentence (ADR 0043 section 3):

1. Every status check listed in .github/required-checks.json resolves to exactly one job, in the workflow the list
   names, and that job can report on a pull request: its workflow starts on ``pull_request`` for every branch the
   ruleset protects, without ``paths:`` or ``paths-ignore:`` (a required check of a filtered workflow stays pending
   on every pull request outside the filter). A job-level ``if:`` is fine: a skipped job counts as passed.
   A target that cannot be found is a failure, never "nothing to verify".
2. Every gate self-test is run by a step of the ``invariants`` job of pr-ci.yml: each scripts/**/test_*.py (history
   excluded) and each script that declares ``--self-test`` (called with that option).
3. No workflow calls scripts/quality/verify-ruleset.py without ``--from-json``: reading a ruleset needs an
   administration token that a pull request must never receive.

What this does not prove: that the ruleset configured on GitHub contains these checks (that is
scripts/quality/verify-ruleset.py, run by hand), nor that a step runs on every event.

Self-test: scripts/quality/test_check_ci_wiring.py.
"""
from __future__ import annotations

import argparse
import itertools
import json
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ".github/workflows"
MANIFEST = ".github/required-checks.json"
PR_CI = "pr-ci.yml"
INVARIANTS_JOB = "invariants"

TOP_LEVEL_KEY = re.compile(r"^[A-Za-z_\"'][^\s:]*\s*:")
JOB_HEADER = re.compile(r"^  ([\w-]+):\s*$")
JOB_FIELD = re.compile(r"^    (name|uses):\s*(.+?)\s*$")
EVENT_HEADER = re.compile(r"^  ([\w-]+):\s*(.*?)\s*$")
INLINE_LIST = re.compile(r"\[(.*)\]")
MATRIX_ENTRY = re.compile(r"^\s+([\w-]+):\s*\[(.*)\]\s*$")
MATRIX_REFERENCE = re.compile(r"\$\{\{\s*matrix\.([\w-]+)\s*\}\}")
SELF_TEST_OPTION = re.compile(r"""add_argument\(\s*["']--self-test["']""")
VERIFY_RULESET = "scripts/quality/verify-ruleset.py"


def code_lines(text: str) -> list[tuple[int, str]]:
    """Numbered lines that are not blank and not comments; line endings normalised."""
    return [(number, raw.rstrip("\r")) for number, raw in enumerate(text.splitlines(), start=1)
            if raw.strip() and not raw.lstrip().startswith("#")]


def top_level_block(lines: list[tuple[int, str]], key: str) -> list[tuple[int, str]]:
    """The lines nested under the top-level ``key:`` (its own line included)."""
    block: list[tuple[int, str]] = []
    inside = False
    for number, line in lines:
        if re.match(rf"^{re.escape(key)}\s*:", line) or re.match(rf"^[\"']{re.escape(key)}[\"']\s*:", line):
            inside = True
            block.append((number, line))
            continue
        if inside:
            if TOP_LEVEL_KEY.match(line):
                break
            block.append((number, line))
    return block


def strip_quotes(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        return value[1:-1]
    return value


def pull_request_trigger(text: str) -> dict | None:
    """``{"branches": [...] | None, "filtered": bool}`` for the pull_request trigger, None when absent."""
    block = top_level_block(code_lines(text), "on")
    if not block:
        return None
    head = block[0][1].split(":", 1)[1].strip()
    if head:  # on: [push, pull_request]  /  on: pull_request
        events = [item.strip() for item in re.sub(r"[\[\]]", "", head).split(",")]
        return {"branches": None, "filtered": False} if "pull_request" in events else None
    sections: dict[str, list[str]] = {}
    current = None
    for _, line in block[1:]:
        header = EVENT_HEADER.match(line)
        if header and not line.startswith("   "):
            current = header.group(1)
            sections[current] = [header.group(2)] if header.group(2) else []
        elif current is not None:
            sections[current].append(line)
    if "pull_request" not in sections:
        return None
    body = sections["pull_request"]
    filtered = any(re.match(r"^\s{4}(paths|paths-ignore)\s*:", line) for line in body)
    branches: list[str] | None = None
    for index, line in enumerate(body):
        match = re.match(r"^\s{4}branches\s*:\s*(.*)$", line)
        if not match:
            continue
        inline = INLINE_LIST.search(match.group(1))
        if inline:
            branches = [strip_quotes(item) for item in inline.group(1).split(",") if item.strip()]
        else:
            branches = []
            for following in body[index + 1:]:
                item = re.match(r"^\s{6}-\s*(.+?)\s*$", following)
                if not item:
                    break
                branches.append(strip_quotes(item.group(1)))
    return {"branches": branches, "filtered": filtered}


def jobs_of(text: str) -> dict[str, dict]:
    """``{job id: {"names": [...], "uses": bool, "lines": [...]}}`` with matrix names expanded."""
    block = top_level_block(code_lines(text), "jobs")
    jobs: dict[str, dict] = {}
    current = None
    for number, line in block[1:]:
        header = JOB_HEADER.match(line)
        if header:
            current = header.group(1)
            jobs[current] = {"raw_name": None, "uses": False, "lines": [], "matrix": {}}
            continue
        if current is None:
            continue
        jobs[current]["lines"].append((number, line))
        field = JOB_FIELD.match(line)
        if field and field.group(1) == "name" and jobs[current]["raw_name"] is None:
            jobs[current]["raw_name"] = strip_quotes(field.group(2))
        elif field and field.group(1) == "uses":
            jobs[current]["uses"] = True
        entry = MATRIX_ENTRY.match(line)
        if entry and not line.startswith("    name"):
            jobs[current]["matrix"][entry.group(1)] = [strip_quotes(v) for v in entry.group(2).split(",") if v.strip()]
    for job_id, job in jobs.items():
        raw = job["raw_name"] or job_id
        keys = sorted(set(MATRIX_REFERENCE.findall(raw)))
        expanded: list[str] = []
        axes = [job["matrix"].get(key, []) for key in keys]
        if keys and all(axes):
            for combination in itertools.product(*axes):
                values = dict(zip(keys, combination))
                expanded.append(MATRIX_REFERENCE.sub(lambda m: values[m.group(1)], raw))
        else:
            expanded.append(raw)
        job["names"] = expanded
    return jobs


def check_required_checks(root: Path) -> tuple[list[str], int]:
    failures: list[str] = []
    manifest_path = root / MANIFEST
    if not manifest_path.is_file():
        return [f"{MANIFEST}: missing, the required checks are not declared"], 0
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        rulesets = manifest["rulesets"]
    except (ValueError, KeyError) as error:
        return [f"{MANIFEST}: unreadable ({error})"], 0
    resolved = 0
    for ruleset, definition in rulesets.items():
        branches = definition.get("branches", [])
        for check in definition.get("checks", []):
            context = check.get("context", "")
            source = check.get("source", "")
            if source == "application":
                resolved += 1
                continue
            label = f"{MANIFEST}: '{context}' ({ruleset})"
            workflow = check.get("workflow", "")
            path = root / WORKFLOWS / workflow
            if not workflow or not path.is_file():
                failures.append(f"{label}: workflow '{workflow}' not found in {WORKFLOWS}")
                continue
            text = path.read_text(encoding="utf-8")
            jobs = jobs_of(text)
            if source == "reusable":
                matches = [job_id for job_id, job in jobs.items() if job["uses"]
                           and any(context.startswith(name + " / ") for name in job["names"])]
            else:
                matches = [job_id for job_id, job in jobs.items() if context in job["names"]]
            if len(matches) != 1:
                failures.append(f"{label}: expected exactly one job of {workflow} to report it, found {len(matches)}")
                continue
            trigger = pull_request_trigger(text)
            if trigger is None:
                failures.append(f"{label}: {workflow} does not start on pull_request, the check would never report")
                continue
            if trigger["filtered"]:
                failures.append(f"{label}: {workflow} filters pull_request by paths/paths-ignore, the check would stay "
                                f"pending on every pull request outside the filter")
                continue
            uncovered = [b for b in branches if trigger["branches"] is not None and b not in trigger["branches"]]
            if uncovered:
                failures.append(f"{label}: {workflow} does not start on pull_request for {', '.join(uncovered)}")
                continue
            resolved += 1
    return failures, resolved


def invariants_lines(root: Path) -> list[str] | None:
    path = root / WORKFLOWS / PR_CI
    if not path.is_file():
        return None
    jobs = jobs_of(path.read_text(encoding="utf-8"))
    if INVARIANTS_JOB not in jobs:
        return None
    return [line for _, line in jobs[INVARIANTS_JOB]["lines"]]


def gate_self_tests(root: Path) -> list[str]:
    """Repository-relative paths of the self-tests that must be wired, sorted."""
    found: set[str] = set()
    scripts = root / "scripts"
    if not scripts.is_dir():
        return []
    for path in scripts.rglob("*.py"):
        relative = path.relative_to(root).as_posix()
        if "/history/" in relative or "__pycache__" in relative:
            continue
        if path.name.startswith("test_"):
            found.add(relative)
        elif SELF_TEST_OPTION.search(path.read_text(encoding="utf-8")):
            found.add(relative)
    return sorted(found)


def check_self_tests_are_wired(root: Path) -> tuple[list[str], int]:
    lines = invariants_lines(root)
    if lines is None:
        return [f"{WORKFLOWS}/{PR_CI}: no '{INVARIANTS_JOB}' job found, the gate self-tests have no executor"], 0
    failures: list[str] = []
    wanted = gate_self_tests(root)
    for relative in wanted:
        option_script = not Path(relative).name.startswith("test_")
        wired = any(relative in line and (not option_script or "--self-test" in line) for line in lines)
        if not wired:
            how = f"'{relative} --self-test'" if option_script else f"'{relative}'"
            failures.append(f"{WORKFLOWS}/{PR_CI}: no step of the '{INVARIANTS_JOB}' job runs {how}")
    return failures, len(wanted)


def check_no_ruleset_reading_in_ci(root: Path) -> list[str]:
    failures: list[str] = []
    directory = root / WORKFLOWS
    files = sorted(list(directory.glob("*.yml")) + list(directory.glob("*.yaml"))) if directory.is_dir() else []
    for path in files:
        for number, line in code_lines(path.read_text(encoding="utf-8")):
            if VERIFY_RULESET in line and "--from-json" not in line:
                failures.append(f"{WORKFLOWS}/{path.name}:{number}: runs {VERIFY_RULESET} without --from-json, "
                                f"which needs an administration token a pull request must not receive")
    return failures


def check(root: Path) -> tuple[list[str], int, int]:
    failures, resolved = check_required_checks(root)
    wiring_failures, wired = check_self_tests_are_wired(root)
    failures += wiring_failures
    failures += check_no_ruleset_reading_in_ci(root)
    return failures, resolved, wired


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    arguments = parser.parse_args()
    failures, resolved, wired = check(arguments.root)
    if failures:
        for failure in failures:
            print(f"CI WIRING GATE FAILED: {failure}", file=sys.stderr)
        return 1
    print(f"CI WIRING GATE SUCCESS (required checks resolved={resolved}, gate self-tests wired in "
          f"'{INVARIANTS_JOB}'={wired})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
