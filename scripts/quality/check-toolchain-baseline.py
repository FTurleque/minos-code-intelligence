#!/usr/bin/env python3
"""Fail when the Gradle baseline of the IntelliJ plugin is written with two different versions.

docs/TOOLCHAIN_POLICY.md says a baseline change is deliberate. The Gradle wrapper of `minos-intellij` is
what the pull-request CI builds the plugin with, and Dependabot raises it; the policy, AGENTS.md and the
release workflows (which install Gradle explicitly) each wrote the version again. Two raises of the
wrapper (9.6.1 to 9.8.0, then 9.8.1) left the policy and the release workflows on 9.6.1: a release was
built with a version the pull-request CI no longer qualified.

The wrapper is the reference. Rules:

1. `minos-intellij/gradle/wrapper/gradle-wrapper.properties` names one Gradle distribution version.
2. `docs/TOOLCHAIN_POLICY.md` states that version (`Gradle: **X**`), and so does `AGENTS.md`
   (`Gradle **X**`).
3. Every `gradle-version:` of a workflow is that version; a workflow that builds with `./gradlew` needs
   none and stays in agreement by construction.

A raise of the wrapper therefore fails this gate until the policy and the release workflows follow: the
change of baseline is made in one pull request, in the open. What this gate does not prove: that the
version is qualified on Linux and Windows (the plugin jobs do), nor that prose elsewhere repeats it.

Self-test: scripts/quality/test_check_toolchain_baseline.py.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - après l'ajout du dossier du script au chemin

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
WRAPPER = "minos-intellij/gradle/wrapper/gradle-wrapper.properties"
POLICY = "docs/TOOLCHAIN_POLICY.md"
AGENTS = "AGENTS.md"
WORKFLOWS = ".github/workflows"

DISTRIBUTION = re.compile(r"^distributionUrl=.*/gradle-(?P<version>\d+(?:\.\d+)*)-(?:bin|all)\.zip\s*$", re.MULTILINE)
POLICY_VERSION = re.compile(r"Gradle: \*\*(?P<version>[^*]+)\*\*")
AGENTS_VERSION = re.compile(r"Gradle \*\*(?P<version>[^*]+)\*\*")
WORKFLOW_VERSION = re.compile(r"^\s*gradle-version:\s*['\"]?(?P<version>[^'\"\s#]+)['\"]?\s*(?:#.*)?$")


def read(root: Path, relative: str) -> str | None:
    path = root / relative
    return path.read_text(encoding="utf-8") if path.is_file() else None


def stated_versions(text: str, pattern: re.Pattern[str]) -> list[str]:
    return [match.group("version").strip() for match in pattern.finditer(text)]


def check(root: Path) -> tuple[list[str], str, int]:
    """(failures, wrapper version, number of places compared with it)."""
    failures: list[str] = []
    wrapper = read(root, WRAPPER)
    if wrapper is None:
        return [f"{WRAPPER} is missing: the Gradle baseline has no reference"], "", 0
    distributions = stated_versions(wrapper, DISTRIBUTION)
    if len(distributions) != 1:
        return [f"{WRAPPER} must name exactly one Gradle distribution, found {len(distributions)}"], "", 0
    version = distributions[0]
    compared = 0
    for relative, pattern, form in ((POLICY, POLICY_VERSION, "Gradle: **X**"), (AGENTS, AGENTS_VERSION, "Gradle **X**")):
        text = read(root, relative)
        if text is None:
            failures.append(f"{relative} is missing")
            continue
        stated = stated_versions(text, pattern)
        if not stated:
            failures.append(f"{relative} does not state the Gradle baseline (expected `{form}`)")
        for other in stated:
            compared += 1
            if other != version:
                failures.append(f"{relative} states Gradle {other}, the wrapper is {version}")
    workflows = root / WORKFLOWS
    for workflow in sorted(workflows.glob("*.y*ml")) if workflows.is_dir() else []:
        for number, line in enumerate(workflow.read_text(encoding="utf-8").splitlines(), start=1):
            match = WORKFLOW_VERSION.match(line)
            if not match:
                continue
            compared += 1
            if match.group("version") != version:
                failures.append(f"{WORKFLOWS}/{workflow.name}:{number} installs Gradle {match.group('version')}, "
                                f"the wrapper is {version}")
    return failures, version, compared


def main() -> int:
    return gate_cli.run(
        __doc__, DEFAULT_ROOT, check, "TOOLCHAIN BASELINE GATE",
        lambda version, compared: f"TOOLCHAIN BASELINE GATE SUCCESS (gradle={version}, places in agreement with the wrapper={compared})")


if __name__ == "__main__":
    raise SystemExit(main())
