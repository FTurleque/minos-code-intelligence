#!/usr/bin/env python3
"""Scope and verdict of the IntelliJ plugin workflow, so that it can be a required check (AUD-DEP-09, AUD-TST-13).

A required check of a workflow filtered by ``paths:`` stays pending on every pull request that does not touch
those paths. ``intellij-plugin.yml`` therefore runs on every pull request and computes its scope here:

  scope    prints ``plugin=true|false`` (and appends it to ``--output``, i.e. ``$GITHUB_OUTPUT``): is the plugin
           concerned by the files changed between ``--base`` and ``--head``? Any doubt (manual dispatch, unknown
           base, git failure) answers ``true``: when in doubt, the plugin is tested.
  verdict  decides the result of the ``IntelliJ plugin (gate)`` job from the results of the jobs it waits for.

Self-test: scripts/ci/test_plugin_gate.py.
"""
from __future__ import annotations

import argparse
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# The plugin consumes the JSON that the CLI prints. That JSON is produced by the renderers of minos-application and
# by the domain model, and frozen by the characterization goldens: a change there must run the plugin tests.
PLUGIN_PATHS = (
    "minos-intellij/",
    "minos-cli/",
    "minos-integration-git/",
    "minos-application/src/main/java/com/minos/output/",
    "minos-domain/",
    "minos-app/src/test/resources/characterization/",
    "docs/user/intellij-plugin.md",
    "docs/roadmap/M18_EXECUTION.md",
    ".github/workflows/intellij-plugin.yml",
    "scripts/ci/plugin-gate.py",
)

GIT_RANGES = {"pull_request": "{base}...{head}", "push": "{base}..{head}"}


def in_scope(files: list[str]) -> bool:
    """True when at least one changed file lies under a plugin path (``/`` separators, repository-relative)."""
    normalized = [name.replace("\\", "/").removeprefix("./") for name in files]
    return any(name == path or name.startswith(path) for name in normalized for path in PLUGIN_PATHS)


def changed_files(event: str, base: str, head: str, root: Path = ROOT) -> list[str] | None:
    """Files changed by the event, or None when they cannot be determined."""
    template = GIT_RANGES.get(event)
    if template is None or not base or not head or set(base) == {"0"}:
        return None
    try:
        completed = subprocess.run(
            ["git", "diff", "--name-only", template.format(base=base, head=head)],
            cwd=root, capture_output=True, text=True, check=True, timeout=120)
    except (OSError, subprocess.SubprocessError):
        return None
    return [line for line in completed.stdout.splitlines() if line.strip()]


def scope(event: str, base: str, head: str, root: Path = ROOT) -> bool:
    files = changed_files(event, base, head, root)
    return True if files is None else in_scope(files)


def verdict(changes: str, plugin_in_scope: bool, results: dict[str, str]) -> list[str]:
    """Reasons the gate must fail; an empty list means it passes."""
    problems: list[str] = []
    if changes != "success":
        problems.append(f"the scope computation ended '{changes}': the plugin cannot be declared out of scope")
    for job, result in sorted(results.items()):
        if result in ("failure", "cancelled"):
            problems.append(f"{job} ended '{result}'")
        elif plugin_in_scope and result != "success":
            problems.append(f"{job} ended '{result}' although the plugin is in scope")
        elif not plugin_in_scope and result not in ("skipped", "success"):
            problems.append(f"{job} ended '{result}'")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    scope_parser = commands.add_parser("scope")
    scope_parser.add_argument("--event", required=True)
    scope_parser.add_argument("--base", default="")
    scope_parser.add_argument("--head", default="")
    scope_parser.add_argument("--output", type=Path, help="file to append 'plugin=<bool>' to (GITHUB_OUTPUT)")
    verdict_parser = commands.add_parser("verdict")
    verdict_parser.add_argument("--changes", required=True)
    verdict_parser.add_argument("--scope", required=True, choices=("true", "false", ""),
                                help="output of the scope job; empty when it did not run")
    verdict_parser.add_argument("--plugin", required=True)
    verdict_parser.add_argument("--windows-ownership", required=True)
    arguments = parser.parse_args(argv)

    if arguments.command == "scope":
        line = f"plugin={'true' if scope(arguments.event, arguments.base, arguments.head) else 'false'}"
        print(line)
        if arguments.output is not None:
            with arguments.output.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(line + "\n")
        return 0

    # An empty scope (the scope job did not produce one) is treated as "in scope": fail-closed.
    problems = verdict(arguments.changes, arguments.scope != "false",
                       {"plugin": arguments.plugin, "windows-ownership": arguments.windows_ownership})
    if problems:
        for problem in problems:
            print(f"INTELLIJ PLUGIN GATE FAILED: {problem}", file=sys.stderr)
        return 1
    print(f"INTELLIJ PLUGIN GATE SUCCESS (in scope: {arguments.scope != 'false'})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
