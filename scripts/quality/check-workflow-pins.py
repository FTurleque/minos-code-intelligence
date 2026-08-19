#!/usr/bin/env python3
"""Fail when a GitHub workflow uses a mutable third-party action, an unpinned Inno Setup,
an ambiguously-resolved Inno Setup compiler, or interpolates a GitHub Actions expression
directly inside a shell run: block instead of routing it through env:."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
WORKFLOWS = ROOT / ".github" / "workflows"
USES = re.compile(r"\buses:\s*[\"']?(?P<target>[^@\s\"']+)@(?P<ref>[^\s\"'#]+)")
SHA = re.compile(r"[0-9a-fA-F]{40}")
INNO_VERSION = "6.7.1"

RUN_KEY = re.compile(r"^(?P<indent>[ \t]*)run:[ \t]*(?P<rest>.*)$")
BLOCK_SCALAR = re.compile(r"^[|>][+-]?\d*$")
GHA_EXPRESSION = re.compile(r"\$\{\{\s*(?P<inner>.*?)\s*\}\}")

# GitHub-runtime-controlled, closed-enum values that cannot carry attacker-influenced text no
# matter what a workflow input, PR title/branch, tag, or external event contains. These are exempt
# from the run: interpolation check; everything else must cross into the shell through env:.
SAFE_RUN_EXPRESSIONS = re.compile(r"^steps\.[A-Za-z0-9_-]+\.(outcome|conclusion)$")

ISCC_AMBIGUOUS_SEARCH_MARKERS = ("Get-Command ISCC.exe",)
ISCC_ENV_EXPORT = re.compile(r"ISCC_PATH=.*>>\s*\$env:GITHUB_ENV")


def _has_unsafe_interpolation(text: str) -> bool:
    matches = list(GHA_EXPRESSION.finditer(text))
    if not matches:
        return False
    return any(not SAFE_RUN_EXPRESSIONS.match(match.group("inner")) for match in matches)


def find_run_block_violations(workflow: Path, text: str) -> list[str]:
    """Flag any `${{ ... }}` interpolated directly inside a run: shell block.

    Untrusted/external GitHub Actions values (workflow inputs, PR/ref/tag metadata, step
    outputs derived from them) must cross into the shell only through `env:`, never through
    direct expression interpolation into the script text -- interpolation happens before the
    shell parses the command, so a value containing a quote or shell metacharacter could break
    out of its surrounding literal. Whole-line comments are ignored to avoid flagging prose that
    merely mentions the `${{ }}` syntax (e.g. this file's own workflow-hardening comments).
    """
    failures: list[str] = []
    lines = text.splitlines()
    index = 0
    in_block = False
    block_content_indent: int | None = None
    while index < len(lines):
        line = lines[index]
        line_number = index + 1
        if in_block:
            if line.strip() == "":
                index += 1
                continue
            indent = len(line) - len(line.lstrip(" \t"))
            if block_content_indent is None:
                block_content_indent = indent
            elif indent < block_content_indent:
                in_block = False
                continue  # reprocess this line outside the block
            stripped = line.strip()
            if not stripped.startswith("#") and _has_unsafe_interpolation(line):
                failures.append(
                    f"{workflow.relative_to(ROOT)}:{line_number}: "
                    f"'${{{{ ... }}}}' interpolated directly inside a run: block -- route it "
                    f"through env: instead: {stripped}"
                )
            index += 1
            continue

        match = RUN_KEY.match(line)
        if match:
            rest = match.group("rest").strip()
            if rest == "" or BLOCK_SCALAR.match(rest):
                in_block = True
                block_content_indent = None
                index += 1
                continue
            if not rest.startswith("#") and _has_unsafe_interpolation(rest):
                failures.append(
                    f"{workflow.relative_to(ROOT)}:{line_number}: "
                    f"'${{{{ ... }}}}' interpolated directly inside a run: block -- route it "
                    f"through env: instead: {rest}"
                )
        index += 1
    return failures


def find_inno_setup_provenance_violations(workflow: Path, text: str) -> list[str]:
    """A workflow that installs a pinned Inno Setup must also prove it uses exactly that binary.

    A textual `--version=X.Y.Z` pin on `choco install innosetup` does not, by itself, guarantee
    the compiler that later runs ISCC.exe is the one just installed: a stray pre-existing
    ISCC.exe reachable via PATH/chocolatey shim/another Inno Setup major version could still be
    picked up first. This check requires that any workflow installing Inno Setup via choco also
    (a) exports a resolved ISCC_PATH via GITHUB_ENV in the same step, rather than searching PATH
    or multiple install locations there, and (b) forwards that resolved path to the build script
    via -IsccPath.
    """
    failures: list[str] = []
    lines = text.splitlines()
    if not any("choco install innosetup" in line.lower() for line in lines):
        return failures

    install_block_lines: list[tuple[int, str]] = []
    in_install_step = False
    step_indent: int | None = None
    for index, line in enumerate(lines):
        stripped = line.strip()
        if stripped.lower().startswith("- name:") and "inno setup" in stripped.lower():
            in_install_step = True
            step_indent = len(line) - len(line.lstrip(" \t"))
            continue
        if in_install_step:
            indent = len(line) - len(line.lstrip(" \t"))
            if stripped and stripped.startswith("- ") and indent <= (step_indent or 0):
                in_install_step = False
                continue
            install_block_lines.append((index + 1, line))

    block_text = "\n".join(content for _, content in install_block_lines)
    if not block_text:
        # choco install innosetup exists but not inside a recognizably-named step: fail closed,
        # the invariant cannot be verified.
        failures.append(
            f"{workflow.relative_to(ROOT)}: 'choco install innosetup' found outside an "
            f"identifiable Inno Setup install step (expected a step name containing "
            f"'Inno Setup'); cannot verify compiler provenance."
        )
        return failures

    for marker in ISCC_AMBIGUOUS_SEARCH_MARKERS:
        if marker in block_text:
            failures.append(
                f"{workflow.relative_to(ROOT)}: Inno Setup install step still searches for "
                f"ISCC.exe ambiguously ('{marker}') instead of using the deterministic path the "
                f"choco package just installed."
            )

    if not ISCC_ENV_EXPORT.search(block_text):
        failures.append(
            f"{workflow.relative_to(ROOT)}: Inno Setup install step does not export a resolved "
            f"ISCC_PATH via $env:GITHUB_ENV; downstream steps cannot prove which compiler they use."
        )

    if "-IsccPath" not in text:
        failures.append(
            f"{workflow.relative_to(ROOT)}: workflow installs Inno Setup but no step forwards "
            f"-IsccPath to a release/installer build script; the resolved compiler is never "
            f"actually enforced downstream."
        )

    return failures


def check_build_windows_installer_script() -> list[str]:
    """The installer build script must not let an explicit -IsccPath be second-guessed."""
    failures: list[str] = []
    script = ROOT / "scripts" / "release" / "build-windows-installer.ps1"
    if not script.is_file():
        failures.append(f"{script.relative_to(ROOT)}: script not found")
        return failures
    text = script.read_text(encoding="utf-8")
    if "$IsccPath" not in text:
        failures.append(
            f"{script.relative_to(ROOT)}: does not accept an -IsccPath parameter; the release/CI "
            f"path cannot pin the compiler it uses."
        )
        return failures
    if not re.search(r"IsNullOrWhiteSpace\(\$IsccPath\)", text):
        failures.append(
            f"{script.relative_to(ROOT)}: does not branch on whether -IsccPath was supplied; "
            f"an explicit release-qualified path could still be silently overridden by a search."
        )
    # The ambiguous multi-candidate search must only run when -IsccPath was NOT supplied. A
    # reasonable static proxy: the candidate-array search ($IsccCandidates) must textually appear
    # after an IsNullOrWhiteSpace($IsccPath) guard, i.e. inside its negative branch.
    guard_match = re.search(r"IsNullOrWhiteSpace\(\$IsccPath\)", text)
    candidates_match = re.search(r"\$IsccCandidates\s*=", text)
    if guard_match and candidates_match and candidates_match.start() < guard_match.start():
        failures.append(
            f"{script.relative_to(ROOT)}: ambiguous ISCC candidate search appears before the "
            f"-IsccPath guard; an explicit release-qualified path could be shadowed."
        )
    return failures


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
            if "choco install innosetup" in line.lower() and f"--version={INNO_VERSION}" not in line and "$env:INNO_SETUP_VERSION" not in line:
                failures.append(f"{workflow.relative_to(ROOT)}:{line_number}: Inno Setup must be pinned to {INNO_VERSION}")
            if "choco install innosetup" in line.lower() and "$env:INNO_SETUP_VERSION" in line:
                if not re.search(rf"INNO_SETUP_VERSION:\s*['\"]?{re.escape(INNO_VERSION)}\b", text):
                    failures.append(
                        f"{workflow.relative_to(ROOT)}:{line_number}: 'choco install innosetup' "
                        f"reads $env:INNO_SETUP_VERSION but no env: INNO_SETUP_VERSION: '{INNO_VERSION}' "
                        f"pin was found in this workflow."
                    )
        failures.extend(find_run_block_violations(workflow, text))
        failures.extend(find_inno_setup_provenance_violations(workflow, text))
    failures.extend(check_build_windows_installer_script())
    if failures:
        print("WORKFLOW SUPPLY-CHAIN PIN GATE FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print(f"WORKFLOW SUPPLY-CHAIN PIN GATE SUCCESS (external uses={checked}, Inno={INNO_VERSION})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
