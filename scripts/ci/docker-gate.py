#!/usr/bin/env python3
"""Scope and verdict of the Docker A -> B qualification of pull requests, so that its gate can be a required check
(AUD-DEP-03).

``release-promotion-gate.yml`` runs on every pull request to ``main``: a required check of a workflow filtered by
``paths:`` would stay pending on every pull request that does not touch those paths. The workflow computes its scope
here, and its gate job always reports a verdict:

  scope    prints ``docker=true|false`` (and appends it to ``--output``, i.e. ``$GITHUB_OUTPUT``): can the files
           changed between ``--base`` and ``--head`` alter the image or the upgrade of an installation? The answer is
           ``false`` only when EVERY changed file belongs to the closed exclusion list below. Any doubt (unknown path,
           manual dispatch, unknown base, git failure, empty diff) answers ``true``: when in doubt, the pull request is
           qualified.
  verdict  decides the result of the ``Docker upgrade evidence gate`` job from the results of the jobs it waits for.
           It does not check the evidence itself: ``scripts/release/check-docker-upgrade-evidence.py`` does, for a
           pull request in scope.

The qualification builds the product with ``-DskipTests -DskipITs clean package`` and the image from
``docker/Dockerfile.mcp.release``: documentation, specifications, assistant configuration, the IntelliJ plugin (a client
outside the image) and test sources cannot change either. The manual qualification of a release candidate is always
launched on the commit to tag: the evidence of a pull request is indexed on its head SHA, never on the merge commit.

Self-test: scripts/ci/test_docker_gate.py.
"""
from __future__ import annotations

import argparse
import importlib.util
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

# Reading the diff is the one function of plugin-gate.py that this gate needs: it is loaded, not copied.
_PLUGIN_GATE = Path(__file__).resolve().parent / "plugin-gate.py"
_SPEC = importlib.util.spec_from_file_location("plugin_gate", _PLUGIN_GATE)
_plugin_gate = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(_plugin_gate)

# The closed exclusion list: what a pull request may change without being qualified. A path outside it is in scope.
EXCLUDED_PREFIXES = (
    "docs/",
    "openspec/",
    ".claude/",
    "minos-intellij/",
)
EXCLUDED_PATTERNS = (
    r"^[^/]+\.md$",                 # the Markdown files of the repository root
    r"^[^/]+/src/test/",            # test sources: the qualification builds with -DskipTests -DskipITs
)
# Paths that must never be excluded: the image, what builds it, the workflow of the gate and its scripts. The self-test
# refuses an entry of the list that covers one of them.
FORBIDDEN_PROBES = (
    "docker/Dockerfile.mcp.release",
    "docker/compose-mcp.prod.yaml",
    "pom.xml",
    "minos-app/pom.xml",
    "mvnw",
    ".mvn/wrapper/maven-wrapper.properties",
    ".github/workflows/release-promotion-gate.yml",
    "scripts/ci/qualify-docker-upgrade.ps1",
    "scripts/ci/docker-gate.py",
    "scripts/release/check-docker-upgrade-evidence.py",
    "minos-app/src/main/java/com/minos/app/Main.java",
    "minos-engine/src/main/java/com/minos/store/A.java",
)


def _normalized(name: str) -> str:
    return name.replace("\\", "/").removeprefix("./")


def is_excluded(name: str, prefixes: tuple[str, ...] = EXCLUDED_PREFIXES,
                patterns: tuple[str, ...] = EXCLUDED_PATTERNS) -> bool:
    """Whether the closed list declares ``name`` (repository-relative) without effect on the image."""
    name = _normalized(name)
    return any(name == prefix.rstrip("/") or name.startswith(prefix) for prefix in prefixes) \
        or any(re.search(pattern, name) for pattern in patterns)


def forbidden_exclusions(prefixes: tuple[str, ...], patterns: tuple[str, ...]) -> list[str]:
    """The entries of an exclusion list that cover a path of FORBIDDEN_PROBES (each entry named once)."""
    refused: list[str] = []
    for prefix in prefixes:
        if any(is_excluded(probe, (prefix,), ()) for probe in FORBIDDEN_PROBES):
            refused.append(prefix)
    for pattern in patterns:
        if any(is_excluded(probe, (), (pattern,)) for probe in FORBIDDEN_PROBES):
            refused.append(pattern)
    return refused


def in_scope(files: list[str]) -> bool:
    """True when at least one changed file is outside the closed exclusion list."""
    return any(not is_excluded(name) for name in files)


def scope_of(files: list[str]) -> bool:
    """The scope of a known list of changed files; an empty diff proves nothing and is in scope."""
    return True if not files else in_scope(files)


def scope(event: str, base: str, head: str, root: Path = ROOT) -> bool:
    files = _plugin_gate.changed_files(event, base, head, root)
    return True if files is None else scope_of(files)


def decide(changes: str, docker_in_scope: str, qualification: str) -> tuple[list[str], str]:
    """(reasons the gate must fail, reason of the verdict); no reason to fail means it passes.

    An empty scope (the scope job did not produce one) is in scope, and a scope computation that did not succeed
    leaves no pull request out of scope.
    """
    problems: list[str] = []
    out_of_scope = docker_in_scope == "false" and changes == "success"
    if changes != "success":
        problems.append(f"the scope computation ended '{changes}': the pull request cannot be declared out of scope")
    if qualification in ("failure", "cancelled"):
        problems.append(f"the qualification ended '{qualification}'")
    elif not out_of_scope and qualification != "success" and changes == "success":
        problems.append(f"the qualification ended '{qualification}' although the pull request is in scope")
    elif out_of_scope and qualification not in ("skipped", "success"):
        problems.append(f"the qualification ended '{qualification}'")
    return problems, "non concerné" if out_of_scope else "concerné"


def main(argv: list[str] | None = None, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    commands = parser.add_subparsers(dest="command", required=True)
    scope_parser = commands.add_parser("scope")
    scope_parser.add_argument("--event", required=True)
    scope_parser.add_argument("--base", default="")
    scope_parser.add_argument("--head", default="")
    scope_parser.add_argument("--output", type=Path, help="file to append 'docker=<bool>' to (GITHUB_OUTPUT)")
    verdict_parser = commands.add_parser("verdict")
    verdict_parser.add_argument("--changes", required=True)
    verdict_parser.add_argument("--scope", required=True, choices=("true", "false", ""),
                                help="output of the scope job; empty when it did not run")
    verdict_parser.add_argument("--qualification", required=True)
    arguments = parser.parse_args(argv)

    if arguments.command == "scope":
        line = f"docker={'true' if scope(arguments.event, arguments.base, arguments.head, root) else 'false'}"
        print(line)
        if arguments.output is not None:
            with arguments.output.open("a", encoding="utf-8", newline="\n") as handle:
                handle.write(line + "\n")
        return 0

    problems, reason = decide(arguments.changes, arguments.scope, arguments.qualification)
    if problems:
        for problem in problems:
            print(f"DOCKER GATE FAILED: {problem}", file=sys.stderr)
        return 1
    print(f"DOCKER GATE SUCCESS ({reason})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
