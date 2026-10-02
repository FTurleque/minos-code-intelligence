#!/usr/bin/env python3
"""Fail when a compose service has no CPU, memory and PID ceiling, or when the ceilings diverge.

Contract of docker/compose.mcp.prod.yaml and docker/compose.mcp.connected.yaml (audit S11):

* each file declares its ceilings once, as top-level ``x-limits-<role>: &limits-<role>`` blocks of
  ``mem_limit``, ``memswap_limit``, ``pids_limit`` and optionally ``cpus``; every value is
  ``"${MINOS_<ROLE>_<KIND>:-<default>}"`` (overridable from the runtime .env, default documented);
* memory and PID defaults are strictly positive: ``mem_limit`` and ``memswap_limit`` a non-zero size
  (``[1-9][0-9]*[kmg]``), ``pids_limit`` a positive integer; empty, ``0`` and ``-1`` all mean
  "unlimited" to Docker and are refused;
* CPU is the opposite rule, on purpose. The Docker daemon refuses to create a container whose ``cpus``
  exceeds the host's CPU count ("range of CPUs is from 0.01 to N"), so a hard default of 4 breaks every
  2-CPU Docker Desktop/WSL2 host, and 1 CPU is the only value that starts everywhere. A default CPU
  ceiling above 1 is therefore refused (do not raise it "because it is only a number"). ``cpus`` may be
  absent from a block (no CPU ceiling, the shipped intent) or default to ``0``, Docker's own spelling of
  "no ceiling" and the only one Compose can parse (an empty ``${VAR:-}`` default fails interpolation:
  ``strconv.ParseFloat: parsing ""``); negative and empty values are refused. A host-specific CPU cap
  is the operator's choice, through ``MINOS_<ROLE>_CPUS`` in the runtime .env;
* ``memswap_limit`` repeats ``mem_limit`` (no swap);
* every service pulls its ceilings from one of those blocks with ``<<: *limits-<role>`` and never
  spells a ceiling itself (no second definition inside a service, no ``deploy`` resources);
* the two files carry identical blocks, and give a service they share the same role;
* every variable and its default appears on one line of docs/user/docker-runtime.md, and as
  ``# VAR=default   # unit: ...`` in docker/.env.example (where an operator discovers the knob);
* the gate and its self-test are run by the ``invariants`` job of .github/workflows/pr-ci.yml.

What "identical blocks" compares: the blocks **normalised by this script's line parser** (for each key,
the variable name and its default), not the raw text and not a YAML parse (PyYAML is not guaranteed on the
CI interpreter). Key order, comments and spacing are ignored; what Compose then does with the anchor and
the ``<<`` merge is not re-derived here (checked by hand with ``docker compose config``, see the doc).

Self-test: scripts/quality/test_check_compose_limits.py.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DOC = "docs/user/docker-runtime.md"
ENV_EXAMPLE = "docker/.env.example"
KEYS = ("cpus", "mem_limit", "memswap_limit", "pids_limit")
REQUIRED_KEYS = ("mem_limit", "memswap_limit", "pids_limit")
POSITIVE_DEFAULT = {
    "mem_limit": re.compile(r"^[1-9][0-9]*[kKmMgG]$"),
    "memswap_limit": re.compile(r"^[1-9][0-9]*[kKmMgG]$"),
    "pids_limit": re.compile(r"^[1-9][0-9]*$"),
    # 0 = no CPU ceiling (Docker's meaning); otherwise a positive number no larger than 1.
    "cpus": re.compile(r"^(0|1(\.0+)?|0?\.[0-9]*[1-9][0-9]*)$"),
}
PR_CI = ".github/workflows/pr-ci.yml"
CI_STEPS = ("scripts/quality/check-compose-limits.py", "scripts/quality/test_check_compose_limits.py")
FORBIDDEN_IN_SERVICE = re.compile(r"^\s+(cpus|mem_limit|memswap_limit|pids_limit|mem_reservation|cpu_shares|deploy):")
BLOCK_HEADER = re.compile(r"^x-limits-(?P<role>[a-z]+): &limits-(?P=role)\s*$")
VALUE = re.compile(r'^  (?P<key>[a-z_]+): "\$\{(?P<var>MINOS_[A-Z_]+):-(?P<default>[^}]*)\}"\s*$')
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
            key, default = value.group("key"), value.group("default")
            if key == "cpus" and not POSITIVE_DEFAULT["cpus"].match(default):
                failures.append(
                    f"{relative}: x-limits-{role}: cpus default {default!r} refused. A default CPU ceiling must be "
                    f"0 (none) or at most 1: the Docker daemon refuses to create a container whose cpus exceeds "
                    f"the host's CPU count, and 1 CPU starts everywhere; an empty default cannot be parsed by "
                    f"Compose. Do not raise it: operators set MINOS_<ROLE>_CPUS in the runtime .env.")
            elif key in POSITIVE_DEFAULT and not POSITIVE_DEFAULT[key].match(default):
                failures.append(
                    f"{relative}: x-limits-{role}: {key} default {default!r} refused: a memory or PID ceiling must be "
                    f"strictly positive (empty, 0 and -1 mean unlimited to Docker); sizes are <n>k, <n>m or <n>g")
            values[key] = (value.group("var"), default)
        if not set(REQUIRED_KEYS) <= set(values) or not set(values) <= set(KEYS):
            failures.append(f"{relative}: x-limits-{role} must define {', '.join(REQUIRED_KEYS)} (and optionally cpus)")
        elif values.get("memswap_limit") != values.get("mem_limit"):
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


def find_ci_wiring_violations(root: Path) -> list[str]:
    workflow = root / PR_CI
    if not workflow.is_file():
        return [f"{PR_CI} not found: the gate would not run in CI"]
    runs = [line.strip() for line in workflow.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("#")]
    return [f"{PR_CI} does not run `python {step}`" for step in CI_STEPS
            if not any(line.removeprefix("- ").startswith(f"run: python {step}") for line in runs)]


def check(root: Path) -> tuple[list[str], int]:
    failures: list[str] = find_ci_wiring_violations(root)
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
    example = root / ENV_EXAMPLE
    example_lines = example.read_text(encoding="utf-8").splitlines() if example.is_file() else []
    for variable, default in sorted(seen):
        if not any(f"{variable}={default} " in line and "unit:" in line for line in example_lines):
            failures.append(f"{ENV_EXAMPLE}: no `# {variable}={default}   # unit: ...` line")
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
    print(f"COMPOSE RESOURCE LIMITS GATE SUCCESS (files checked={inspected}; compared: blocks normalised by the "
          f"line parser, not raw text nor a YAML parse)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
