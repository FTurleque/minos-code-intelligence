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


SETUP_BUILDING_INVOCATION = re.compile(
    r"scripts[\\/]release[\\/](?P<script>publish-windows-release\.ps1|build-local-windows-candidate\.ps1)")


def find_iscc_propagation_violations(workflow: Path, text: str) -> list[str]:
    """Every setup-building invocation in an Inno-installing workflow must carry the qualified compiler.

    build-windows-installer.ps1 only honours a pinned compiler when it is actually told about one;
    with neither -IsccPath nor -RequiredIsccVersion it falls back to searching PATH and the Inno
    Setup install locations. A release/qualification workflow that installs a pinned Inno Setup and
    then omits those arguments on any build-capable call therefore silently reintroduces the
    ambiguity for that build -- which is exactly how the smoke setup once ended up compiled by the
    Chocolatey shim while the production setup used the qualified binary.

    Invocations passing -PublishOnly are exempt: that mode uploads already-built, already-verified
    artifacts and compiles nothing.
    """
    failures: list[str] = []
    if not any("choco install innosetup" in line.lower() for line in text.splitlines()):
        return failures

    for line_number, line in enumerate(text.splitlines(), start=1):
        match = SETUP_BUILDING_INVOCATION.search(line)
        if not match:
            continue
        if "-PublishOnly" in line:
            continue
        missing = [flag for flag in ("-IsccPath", "-RequiredIsccVersion") if flag not in line]
        if missing:
            failures.append(
                f"{workflow.relative_to(ROOT)}:{line_number}: {match.group('script')} can compile a "
                f"setup here but the invocation does not pass {', '.join(missing)}; the build would "
                f"fall back to ambiguous ISCC.exe resolution."
            )
    return failures


def find_windows_qualification_coverage_violations() -> list[str]:
    """windows-installer.yml must re-qualify Windows when the files defining release compilation change.

    The Windows qualification workflow is what actually proves the candidate builds, installs,
    handshakes over MCP and uninstalls. If the real publication workflow (or the supply-chain gate
    that constrains it) can be edited without triggering it, that proof silently stops covering the
    thing being changed.
    """
    failures: list[str] = []
    workflow = WORKFLOWS / "windows-installer.yml"
    if not workflow.is_file():
        return [f"{workflow.relative_to(ROOT)}: Windows qualification workflow not found"]

    trigger_paths = _trigger_path_filters(workflow.read_text(encoding="utf-8"))
    required_coverage = (
        ".github/workflows/release-windows.yml",
        "scripts/quality/check-workflow-pins.py",
    )
    for trigger in ("pull_request", "push"):
        if trigger not in trigger_paths:
            failures.append(
                f"{workflow.relative_to(ROOT)}: no '{trigger}' paths filter found; cannot verify that "
                f"Windows qualification covers the release workflow."
            )
            continue
        for required in required_coverage:
            if required not in trigger_paths[trigger]:
                failures.append(
                    f"{workflow.relative_to(ROOT)}: '{trigger}' paths filter does not include "
                    f"'{required}'; changing it would skip Windows qualification."
                )

    if "pull_request" in trigger_paths and "push" in trigger_paths:
        if trigger_paths["pull_request"] != trigger_paths["push"]:
            failures.append(
                f"{workflow.relative_to(ROOT)}: pull_request and push paths filters differ; a change "
                f"qualified on a PR must be re-qualified on the branch it lands on."
            )
    return failures


def _trigger_path_filters(text: str) -> dict[str, list[str]]:
    """Extract {trigger: [path globs]} from a workflow's `on:` block without a YAML dependency."""
    filters: dict[str, list[str]] = {}
    lines = text.splitlines()
    in_on_block = False
    trigger: str | None = None
    paths_indent: int | None = None
    for raw in lines:
        stripped = raw.strip()
        if not stripped or stripped.startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip(" "))
        if indent == 0:
            in_on_block = stripped in ("on:", '"on":', "'on':")
            trigger = None
            paths_indent = None
            continue
        if not in_on_block:
            continue
        if paths_indent is not None:
            if indent > paths_indent and stripped.startswith("- "):
                filters[trigger].append(stripped[2:].strip().strip("'\""))
                continue
            paths_indent = None
        if indent == 2 and stripped.endswith(":"):
            trigger = stripped[:-1]
            continue
        if trigger is not None and stripped == "paths:":
            paths_indent = indent
            filters.setdefault(trigger, [])
    return filters


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

    # Package metadata proves what was installed, not what executed. The build must assert the
    # engine version reported by the compiler it actually ran.
    if "Assert-IsccEngineVersion" not in text:
        failures.append(
            f"{script.relative_to(ROOT)}: does not assert the engine version reported by the "
            f"compiler it executes; installed-package metadata alone cannot prove which binary "
            f"produced the setup."
        )
    if not re.search(r"IsNullOrWhiteSpace\(\$IsccPath\)\s*-ne\s*\[string\]::IsNullOrWhiteSpace\(\$RequiredIsccVersion\)", text):
        failures.append(
            f"{script.relative_to(ROOT)}: does not reject a half-qualified invocation; -IsccPath and "
            f"-RequiredIsccVersion must be required together so neither the binary nor its version "
            f"can go unproven."
        )

    helper = ROOT / "scripts" / "release" / "iscc-provenance.ps1"
    tests = ROOT / "scripts" / "release" / "test-iscc-provenance.ps1"
    for required in (helper, tests):
        if not required.is_file():
            failures.append(f"{required.relative_to(ROOT)}: required Inno Setup provenance file not found")
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
        failures.extend(find_iscc_propagation_violations(workflow, text))
    failures.extend(find_windows_qualification_coverage_violations())
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
