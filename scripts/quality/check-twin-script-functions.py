#!/usr/bin/env python3
"""Fail when PowerShell functions copied between shipped, install, release or CI scripts stop being identical (AUD-QUA-09).

Shipped scripts cannot dot-source a shared library: the Windows distribution copies them one by one and the installer
runs `detect-mcp-clients.ps1` alone from a temporary folder (decision D1 of the OpenSpec change
`garder-les-fonctions-de-scripts-jumelles`). Some functions are therefore copied, among them the ACL functions of the
secret file. This gate keeps the copies identical.

Scope: the `.ps1` files under PERIMETER. Milestone scripts (`scripts/mNN/`, frozen by ADR 0043), `scripts/history/` and
the PowerShell resources of the modules are outside it.

Extraction: a definition starts at a `function <Name>` line in column 0 and ends at the first line that is `}` in
column 0, or on its own line when that line closes every brace it opens. Lines inside a here-string (`@'`...`'@`,
`@"`...`"@`) are text: they neither start nor end a definition. Block comments (`<#`...`#>`) are not recognised: a
`function` or a `}` in column 0 inside one counts as code. Bodies are compared after normalising line endings and
trailing spaces only (indentation and comments count). Nested definitions are part of their parent body.

Rules:

1. Every perimeter directory exists, and every `.ps1` in it reads as UTF-8.
2. Every column-0 definition of the perimeter can be bounded: one that reaches the next definition or the end of the
   file without its closing `}` fails, it never passes for identical.
3. Each entry of the closed list TWINS names at least two distinct files, each file exists and defines the function
   exactly once, and every copy has the same normalised body. A failure names the function and the files.
4. Two perimeter files that carry the same function with the same body are covered by one TWINS entry: an undeclared
   copy fails, so a new copy is declared (and kept identical) from the commit that creates it.
5. The gate and its self-test are steps of the `invariants` job of `.github/workflows/pr-ci.yml`, with no `if:`
   (`gate_cli.find_ci_wiring_violations`).

What this gate does not prove: what the functions do (the tests of each script do), nor that a homonym with another
body should be aligned (the divergent homonyms are listed in the design of the change, not guarded).

`--root` points the gate at another tree (default: this repository).
Self-test: scripts/quality/test_check_twin_script_functions.py.
"""
from __future__ import annotations

import re
import sys
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path; no fallback copy

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
LABEL = "TWIN SCRIPT FUNCTIONS GATE"
PERIMETER = ("docker/scripts", "scripts/install", "scripts/release", "scripts/ci")
# Every subdirectory of the perimeter is read: a copy placed in `build/` or `target/` is still a copy. Nested checkouts
# are skipped by `gate_cli.repository_files`.
SKIPPED_DIRECTORIES: frozenset[str] = frozenset()
CI_STEPS = ("scripts/quality/check-twin-script-functions.py", "scripts/quality/test_check_twin_script_functions.py")

_DOCKER_SERVICES = "docker/scripts/configure-m30-docker-services.ps1"
_RUNTIME_SETTINGS = "scripts/install/configure-runtime-settings.ps1"

# (function, files). A closed list: an entry is added with the copy it declares, removed with it.
TWINS = (
    # Secret file of the PostgreSQL password and bounded reads, shipped in docker\scripts\ and integration\.
    ("Require-Identifier", (_DOCKER_SERVICES, _RUNTIME_SETTINGS)),
    ("Read-BoundedUtf8", (_DOCKER_SERVICES, _RUNTIME_SETTINGS)),
    ("Restrict-FileToCurrentUser", (_DOCKER_SERVICES, _RUNTIME_SETTINGS)),
    ("Get-FileAccessSecurity", (_DOCKER_SERVICES, _RUNTIME_SETTINGS)),
    ("Assert-FileRestrictedToCurrentUser", (_DOCKER_SERVICES, _RUNTIME_SETTINGS)),
    ("Write-PrivateSecretFile", (_DOCKER_SERVICES, _RUNTIME_SETTINGS)),
    # Ceiling overrides kept across a restart of the Docker MCP runtime.
    ("Test-CeilingOverride", ("docker/scripts/mcp-lifecycle.ps1", "docker/scripts/prod-mcp.ps1")),
    ("Get-PreservedCeilingOverrides", ("docker/scripts/mcp-lifecycle.ps1", "docker/scripts/prod-mcp.ps1")),
    # MCP client detection and configuration, both shipped.
    ("Get-UserFolderPath", ("scripts/install/configure-mcp-clients.ps1", "scripts/install/detect-mcp-clients.ps1")),
    # Installer progress protocol, written by each configuration script the installer runs with -ProgressPath.
    ("Write-SetupProgress", ("docker/scripts/configure-docker-mcp.ps1", _DOCKER_SERVICES,
                             "scripts/install/configure-mcp-clients-setup.ps1",
                             "scripts/install/switch-mcp-backend.ps1")),
    # Native calls of the release scripts.
    ("Invoke-NativeChecked", ("scripts/release/build-windows-distribution.ps1",
                              "scripts/release/publish-windows-release.ps1")),
    # Assertions of the test scripts.
    ("Test-Case", ("scripts/release/test-iscc-provenance.ps1", "scripts/release/test-release-signature.ps1")),
    ("Assert-True", ("scripts/install/verify-mcp-backend-lifecycle.ps1",
                     "scripts/install/verify-mcp-client-backend-routing.ps1",
                     "scripts/install/verify-mcp-client-preflight.ps1")),
)

DEFINITION = re.compile(r"^function\s+(?P<name>[A-Za-z_][A-Za-z0-9_-]*)", re.IGNORECASE)


class Definition:
    """One column-0 function definition: its name, first line (1-based) and normalised body, or None if unbounded."""

    def __init__(self, name: str, line: int, body: str | None):
        self.name = name
        self.line = line
        self.body = body


def normalized_lines(text: str) -> list[str]:
    return [line.rstrip() for line in text.replace("\r\n", "\n").replace("\r", "\n").split("\n")]


def code_lines(lines: list[str]) -> list[bool]:
    """False for the content and the terminator of a here-string (`@'`...`'@`, `@"`...`"@`), True elsewhere.

    A `function` or a `}` written in column 0 inside a here-string is text, neither a definition nor its end.
    """
    flags: list[bool] = []
    terminator: str | None = None
    for line in lines:
        if terminator is not None:
            flags.append(False)
            if line.startswith(terminator):
                terminator = None
            continue
        flags.append(True)
        if line.endswith("@'"):
            terminator = "'@"
        elif line.endswith('@"'):
            terminator = '"@'
    return flags


def definitions(text: str) -> list[Definition]:
    lines = normalized_lines(text)
    code = code_lines(lines)
    found: list[Definition] = []
    for index, line in enumerate(lines):
        match = DEFINITION.match(line) if code[index] else None
        if not match:
            continue
        name = match.group("name")
        if "{" in line and line.count("{") == line.count("}"):
            found.append(Definition(name, index + 1, line))
            continue
        body = None
        for end in range(index + 1, len(lines)):
            if not code[end]:
                continue
            if DEFINITION.match(lines[end]):
                break
            if lines[end] == "}":
                body = "\n".join(lines[index:end + 1])
                break
        found.append(Definition(name, index + 1, body))
    return found


def perimeter_scripts(root: Path, failures: list[str]) -> dict[str, list[Definition]]:
    scripts: dict[str, list[Definition]] = {}
    for directory in PERIMETER:
        base = root / directory
        if not base.is_dir():
            failures.append(f"{directory}: perimeter directory not found; the gate would compare nothing there")
            continue
        for path in gate_cli.repository_files(base, SKIPPED_DIRECTORIES):
            if path.suffix.lower() != ".ps1" or not path.is_file():
                continue
            relative = path.relative_to(root).as_posix()
            try:
                text = path.read_text(encoding="utf-8-sig")
            except UnicodeDecodeError:
                failures.append(f"{relative}: not readable as UTF-8")
                continue
            scripts[relative] = definitions(text)
    return scripts


def read_definitions(root: Path, relative: str, cache: dict[str, list[Definition]]) -> list[Definition] | None:
    if relative in cache:
        return cache[relative]
    path = root / relative
    if not path.is_file():
        return None
    try:
        cache[relative] = definitions(path.read_text(encoding="utf-8-sig"))
    except UnicodeDecodeError:
        return None
    return cache[relative]


def unbounded_definitions(scripts: dict[str, list[Definition]]) -> list[str]:
    """Rule 2: a column-0 definition without its closing `}` never passes for identical."""
    return [f"{relative}:{definition.line}: function {definition.name} cannot be bounded "
            f"(no `}}` in column 0 before the next definition or the end of the file)"
            for relative, found in scripts.items() for definition in found if definition.body is None]


def entry_bodies(root: Path, name: str, files: tuple[str, ...], cache: dict[str, list[Definition]],
                 failures: list[str]) -> dict[str, str]:
    """The bounded body of `name` in each file of its entry; every missing or repeated definition is a failure."""
    key = name.lower()
    bodies: dict[str, str] = {}
    for relative in files:
        found = read_definitions(root, relative, cache)
        if found is None:
            failures.append(f"TWINS entry {name}: {relative} not found or not readable as UTF-8")
            continue
        matching = [definition for definition in found if definition.name.lower() == key]
        if not matching:
            failures.append(f"TWINS entry {name}: {relative} no longer defines {name} in column 0")
        elif len(matching) > 1:
            lines = ", ".join(str(definition.line) for definition in matching)
            failures.append(f"TWINS entry {name}: {relative} defines {name} {len(matching)} times (lines {lines})")
        elif matching[0].body is not None:
            bodies[relative] = matching[0].body
    return bodies


def twin_violations(root: Path, scripts: dict[str, list[Definition]]) -> tuple[list[str], int]:
    """Rule 3: every entry of TWINS is alive and its copies are identical; returns the failures and the copies compared."""
    failures: list[str] = []
    cache = dict(scripts)
    compared = 0
    seen_names: set[str] = set()
    for name, files in TWINS:
        if name.lower() in seen_names:
            failures.append(f"TWINS entry {name}: listed twice; one entry per function")
        seen_names.add(name.lower())
        if len(set(files)) < 2:
            failures.append(f"TWINS entry {name}: names fewer than two distinct files ({', '.join(files)}); "
                            f"a dead entry is removed")
            continue
        bodies = entry_bodies(root, name, files, cache, failures)
        compared += len(bodies)
        if len(set(bodies.values())) > 1:
            reference = files[0] if files[0] in bodies else next(iter(bodies))
            differing = [relative for relative in bodies if bodies[relative] != bodies[reference]]
            failures.append(f"{name} differs between {reference} and {', '.join(differing)}: a fix made in one copy "
                            f"must be made in every copy")
    return failures, compared


def undeclared_copies(scripts: dict[str, list[Definition]]) -> list[str]:
    """Rule 4: two perimeter files carrying the same function with the same body are covered by one TWINS entry."""
    declared = {name.lower(): set(files) for name, files in TWINS}
    groups: dict[tuple[str, str], set[str]] = defaultdict(set)
    for relative, found in scripts.items():
        for definition in found:
            if definition.body is not None:
                groups[(definition.name.lower(), definition.body)].add(relative)
    failures: list[str] = []
    for (key, _), owners in sorted(groups.items(), key=lambda item: (item[0][0], sorted(item[1]))):
        if len(owners) < 2 or owners <= declared.get(key, set()):
            continue
        name = next(definition.name for definition in scripts[min(owners)] if definition.name.lower() == key)
        failures.append(f"{name} is copied identically in {', '.join(sorted(owners))} without a TWINS entry "
                        f"covering these files: declare the copy (or make it a single definition)")
    return failures


def check(root: Path) -> tuple[list[str], int, int, int]:
    failures: list[str] = gate_cli.find_ci_wiring_violations(root, CI_STEPS)
    scripts = perimeter_scripts(root, failures)
    failures += unbounded_definitions(scripts)
    twin_failures, compared = twin_violations(root, scripts)
    failures += twin_failures
    failures += undeclared_copies(scripts)
    return failures, len(TWINS), compared, len(scripts)


def success(entries: int, compared: int, scripts: int) -> str:
    return (f"{LABEL} SUCCESS (entries={entries}, copies compared={compared}, perimeter scripts={scripts}; "
            f"compared: column-0 bodies, line endings and trailing spaces aside)")


def main() -> int:
    return gate_cli.run(__doc__, DEFAULT_ROOT, check, LABEL, success)


if __name__ == "__main__":
    raise SystemExit(main())
