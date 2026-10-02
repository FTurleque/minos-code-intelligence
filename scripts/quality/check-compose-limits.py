#!/usr/bin/env python3
"""Fail when a compose service has no CPU, memory and PID ceiling, or when the ceilings diverge.

Contract of docker/compose.mcp.prod.yaml and docker/compose.mcp.connected.yaml (audit S11):

* each file declares its ceilings once, as top-level ``x-limits-<role>: &limits-<role>`` blocks of
  exactly ``cpus``, ``mem_limit``, ``memswap_limit`` and ``pids_limit``; every value is
  ``"${MINOS_<ROLE>_<KIND>:-<default>}"`` (overridable from the runtime .env, default documented);
* ``memswap_limit`` repeats ``mem_limit`` (no swap);
* every service pulls its ceilings from one of those blocks with ``<<: *limits-<role>`` and never
  spells a ceiling itself (no second definition inside a service, no ``deploy`` resources);
* the two files carry identical blocks, and give a service they share the same role;
* every variable and its default appears on one line of docs/user/docker-runtime.md.

Self-test: scripts/quality/test_check_compose_limits.py.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DOC = "docs/user/docker-runtime.md"
KEYS = ("cpus", "mem_limit", "memswap_limit", "pids_limit")
FORBIDDEN_IN_SERVICE = re.compile(r"^\s+(cpus|mem_limit|memswap_limit|pids_limit|mem_reservation|cpu_shares|deploy):")
BLOCK_HEADER = re.compile(r"^x-limits-(?P<role>[a-z]+): &limits-(?P=role)\s*$")
VALUE = re.compile(r'^  (?P<key>[a-z_]+): "\$\{(?P<var>MINOS_[A-Z_]+):-(?P<default>[^}]+)\}"\s*$')
SERVICE_HEADER = re.compile(r"^  (?P<name>[a-z][a-z0-9-]*):\s*$")
MERGE = re.compile(r"^    <<: \*limits-(?P<role>[a-z]+)\s*$")


def top_level_sections(text: str) -> dict[str, list[str]]:
    """{top-level key line: its indented body lines}, in file order (comments and blanks kept)."""
    sections: dict[str, list[str]] = {}
    current: str | None = None
    for line in text.splitlines():
        if line and not line[0].isspace() and not line.startswith("#"):
            current = line.rstrip()
            sections[current] = []
        elif current is not None:
            sections[current].append(line)
    return sections


def parse(path: Path, root: Path):
    failures: list[str] = []
    relative = path.relative_to(root).as_posix()
    sections = top_level_sections(path.read_text(encoding="utf-8"))
    blocks: dict[str, dict[str, tuple[str, str]]] = {}
    for header, body in sections.items():
        match = BLOCK_HEADER.match(header)
        if not match:
            continue
        role = match.group("role")
        values: dict[str, tuple[str, str]] = {}
        for line in body:
            if not line.strip() or line.lstrip().startswith("#"):
                continue
            value = VALUE.match(line)
            if not value:
                failures.append(f"{relative}: x-limits-{role}: unreadable line {line.strip()!r} "
                                f'(expected key: "${{MINOS_...:-default}}")')
                continue
            values[value.group("key")] = (value.group("var"), value.group("default"))
        if tuple(sorted(values)) != tuple(sorted(KEYS)):
            failures.append(f"{relative}: x-limits-{role} must define exactly {', '.join(KEYS)}")
        elif values["memswap_limit"] != values["mem_limit"]:
            failures.append(f"{relative}: x-limits-{role}: memswap_limit must equal mem_limit (no swap)")
        blocks[role] = values

    services: dict[str, str | None] = {}
    body = sections.get("services:", [])
    name: str | None = None
    for line in body:
        header = SERVICE_HEADER.match(line)
        if header:
            name = header.group("name")
            services[name] = None
            continue
        if name is None:
            continue
        merge = MERGE.match(line)
        if merge:
            if services[name] is not None:
                failures.append(f"{relative}: service {name} merges more than one limits block")
            services[name] = merge.group("role")
        elif FORBIDDEN_IN_SERVICE.match(line):
            failures.append(f"{relative}: service {name} spells a ceiling itself ({line.strip()}); "
                            f"use the x-limits block, the single definition")
    for service, role in services.items():
        if role is None:
            failures.append(f"{relative}: service {service} has no `<<: *limits-<role>`: no CPU, memory or PID ceiling")
        elif role not in blocks:
            failures.append(f"{relative}: service {service} merges *limits-{role}, which is not defined")
    if not services:
        failures.append(f"{relative}: no service found")
    return failures, blocks, services


def check(root: Path) -> tuple[list[str], int]:
    failures: list[str] = []
    files = sorted((root / "docker").glob("compose*.y*ml"))
    parsed = {}
    for path in files:
        file_failures, blocks, services = parse(path, root)
        failures += file_failures
        parsed[path.name] = (blocks, services)
    names = sorted(parsed)
    for index, first in enumerate(names):
        for second in names[index + 1:]:
            blocks_a, services_a = parsed[first]
            blocks_b, services_b = parsed[second]
            for role in sorted(set(blocks_a) & set(blocks_b)):
                if blocks_a[role] != blocks_b[role]:
                    failures.append(f"x-limits-{role} differs between {first} and {second}: one definition, two files, same values")
            for service in sorted(set(services_a) & set(services_b)):
                if services_a[service] != services_b[service]:
                    failures.append(f"service {service} has role {services_a[service]} in {first} "
                                    f"and {services_b[service]} in {second}")
    doc = root / DOC
    doc_lines = doc.read_text(encoding="utf-8").splitlines() if doc.is_file() else []
    seen: set[tuple[str, str]] = set()
    for blocks, _ in parsed.values():
        for values in blocks.values():
            for variable, default in values.values():
                seen.add((variable, default))
    for variable, default in sorted(seen):
        if not any(variable in line and f"`{default}`" in line for line in doc_lines):
            failures.append(f"{DOC}: no line documents {variable} with its default `{default}`")
    if not files:
        failures.append("no docker/compose*.yaml found: the gate would check nothing")
    return failures, len(files)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    arguments = parser.parse_args(argv)
    failures, inspected = check(arguments.root.resolve())
    if failures:
        print("COMPOSE RESOURCE LIMITS GATE FAILED")
        for failure in failures:
            print(f" - {failure}")
        return 1
    print(f"COMPOSE RESOURCE LIMITS GATE SUCCESS (files checked={inspected})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
