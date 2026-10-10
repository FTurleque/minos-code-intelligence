#!/usr/bin/env python3
"""Compare the branch rulesets configured on GitHub with .github/required-checks.json (AUD-DEP-09). Run by hand.

  python scripts/quality/verify-ruleset.py [--repo OWNER/REPO]
  python scripts/quality/verify-ruleset.py --from-json recorded-rulesets.json   (no network: replay a payload)

Exit codes: 0 the rulesets require exactly the declared checks; 1 a check is missing, in excess, or a ruleset is
absent or not active (each is named); 2 the real state could not be read (gh missing, not authenticated, unreadable
answer). An unreadable state is never reported as success.

The script only reads: it never modifies a ruleset. It prints names of checks, nothing else. It is NOT run by any
workflow: reading rulesets needs an administration token that a pull request must not receive
(scripts/quality/check-ci-wiring.py refuses a workflow that calls it without --from-json).

Self-test: scripts/quality/test_verify_ruleset.py.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / ".github" / "required-checks.json"


class StateUnreadable(Exception):
    """The real ruleset state could not be obtained or understood."""


def declared(manifest: dict) -> dict[str, set[str]]:
    return {name: {check["context"] for check in definition.get("checks", [])}
            for name, definition in manifest["rulesets"].items()}


def required_contexts(ruleset: dict) -> set[str]:
    contexts: set[str] = set()
    for rule in ruleset.get("rules", []):
        if rule.get("type") == "required_status_checks":
            for check in rule.get("parameters", {}).get("required_status_checks", []):
                contexts.add(check["context"])
    return contexts


def compare(manifest: dict, live: list[dict]) -> list[str]:
    """Problems found between the declared checks and the live rulesets; empty when they match."""
    problems: list[str] = []
    by_name = {ruleset.get("name"): ruleset for ruleset in live}
    for name, expected in sorted(declared(manifest).items()):
        ruleset = by_name.get(name)
        if ruleset is None:
            problems.append(f"ruleset '{name}' does not exist on GitHub")
            continue
        if ruleset.get("enforcement") != "active":
            problems.append(f"ruleset '{name}' is not active (enforcement: {ruleset.get('enforcement')})")
        actual = required_contexts(ruleset)
        for context in sorted(expected - actual):
            problems.append(f"ruleset '{name}' does not require '{context}'")
        for context in sorted(actual - expected):
            problems.append(f"ruleset '{name}' requires '{context}', which .github/required-checks.json does not declare")
    return problems


def gh_json(*arguments: str) -> object:
    try:
        completed = subprocess.run(["gh", "api", *arguments], capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as error:
        raise StateUnreadable(f"gh could not be run ({type(error).__name__})") from error
    if completed.returncode != 0:
        raise StateUnreadable(f"gh api {' '.join(arguments)} failed (exit {completed.returncode}); "
                              f"is gh installed and authenticated with access to the repository settings?")
    try:
        return json.loads(completed.stdout)
    except ValueError as error:
        raise StateUnreadable(f"gh api {' '.join(arguments)} returned an unreadable answer") from error


def default_repository() -> str:
    try:
        url = subprocess.run(["git", "remote", "get-url", "origin"], cwd=ROOT, capture_output=True, text=True,
                             check=True, timeout=30).stdout.strip()
    except (OSError, subprocess.SubprocessError) as error:
        raise StateUnreadable("the repository could not be derived from the 'origin' remote; pass --repo") from error
    match = re.search(r"github\.com[:/]([^/\s]+/[^/\s]+?)(?:\.git)?$", url)
    if match is None:
        raise StateUnreadable("the 'origin' remote is not a github.com repository; pass --repo")
    return match.group(1)


def live_rulesets(repository: str) -> list[dict]:
    summaries = gh_json(f"repos/{repository}/rulesets")
    if not isinstance(summaries, list):
        raise StateUnreadable("the list of rulesets is not a list")
    return [gh_json(f"repos/{repository}/rulesets/{summary['id']}") for summary in summaries]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--repo", help="OWNER/REPO (default: derived from the origin remote)")
    parser.add_argument("--from-json", type=Path, help="replay recorded rulesets (a JSON list) instead of calling gh")
    parser.add_argument("--manifest", type=Path, default=MANIFEST)
    arguments = parser.parse_args(argv)
    try:
        manifest = json.loads(arguments.manifest.read_text(encoding="utf-8"))
        declared(manifest)
        if arguments.from_json is not None:
            live = json.loads(arguments.from_json.read_text(encoding="utf-8"))
            if not isinstance(live, list):
                raise StateUnreadable("the recorded payload is not a list of rulesets")
        else:
            live = live_rulesets(arguments.repo or default_repository())
    except StateUnreadable as error:
        print(f"RULESET VERIFICATION UNAVAILABLE: {error}", file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"RULESET VERIFICATION UNAVAILABLE: unreadable input ({type(error).__name__})", file=sys.stderr)
        return 2
    problems = compare(manifest, live)
    if problems:
        for problem in problems:
            print(f"RULESET DIFFERS: {problem}", file=sys.stderr)
        return 1
    print(f"RULESET VERIFICATION SUCCESS ({len(declared(manifest))} rulesets require exactly the declared checks)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
