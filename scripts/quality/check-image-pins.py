#!/usr/bin/env python3
"""Fail when a container image reference is not pinned as <image>:<tag>@sha256:<digest>.

Scope (the whole container supply chain of this repository):

* every ``FROM`` of ``docker/Dockerfile*`` (stage references and ``scratch`` excepted);
* every ``image:`` of ``docker/compose*.y*ml``: a literal, or the default of ``${VAR:-default}``,
  must be pinned; ``${MINOS_IMAGE}`` (the MINOS image itself, built or loaded by the release
  workflow, never pulled from a registry) is the only reference allowed without a pin;
* a managed-service image named by a compose pin (pgvector, ollama, ...) must not be written a
  second time, with a tag, anywhere else in scripts, workflows or sources: the compose file is
  its single source.

The gate and its self-test must be run by the ``invariants`` job of .github/workflows/pr-ci.yml
(checked here, so removing the step turns the gate red wherever it still runs).

Why both a tag and a digest: the digest is the immutable pin, the tag is what Dependabot (docker
ecosystem) follows to propose a new digest. A reference with a digest and no tag is moved to
``latest`` by Dependabot; a tag without a digest is mutable.

Self-test: scripts/quality/test_check_image_pins.py.
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]

PINNED = re.compile(r"^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"
                    r"(?::\d+)?(?:/[a-z0-9]+(?:[._-][a-z0-9]+)*)*"
                    r":[\w][\w.-]{0,127}@sha256:[0-9a-f]{64}$")
FROM_LINE = re.compile(r"^\s*FROM\s+(?:--platform=\S+\s+)?(?P<ref>\S+)(?:\s+AS\s+(?P<alias>[\w.-]+))?\s*(?:#.*)?$",
                       re.IGNORECASE)
IMAGE_KEY = re.compile(r"^\s*image:\s*(?P<value>.+?)\s*(?:#.*)?$")
VARIABLE_WITH_DEFAULT = re.compile(r"^\$\{[A-Za-z_][A-Za-z0-9_]*:-(?P<default>[^}]+)\}$")
VARIABLE = re.compile(r"^\$\{(?P<name>[A-Za-z_][A-Za-z0-9_]*)\}$")
UNPINNED_ALLOWED_VARIABLES = frozenset({"MINOS_IMAGE"})

PR_CI = ".github/workflows/pr-ci.yml"
CI_STEPS = ("scripts/quality/check-image-pins.py", "scripts/quality/test_check_image_pins.py")

SKIPPED_DIRECTORIES = frozenset({".git", "target", "node_modules", "build", ".gradle", ".idea", "history"})
SCANNED_SUFFIXES = frozenset({".ps1", ".py", ".sh", ".yml", ".yaml", ".java", ".json", ".properties", ".iss", ".xml"})
# Tracking files and prose legitimately quote references while discussing them.
SKIPPED_RELATIVE_PREFIXES = ("docs/", "scripts/quality/check-image-pins.py", "scripts/quality/test_check_image_pins.py")


def unquote(value: str) -> str:
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        return value[1:-1]
    return value


def find_dockerfile_violations(path: Path, root: Path) -> list[str]:
    failures: list[str] = []
    stages: set[str] = set()
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        match = FROM_LINE.match(line)
        if not match:
            continue
        reference = match.group("ref")
        alias = match.group("alias")
        if reference.lower() != "scratch" and reference not in stages and not PINNED.match(reference):
            failures.append(
                f"{path.relative_to(root).as_posix()}:{number}: FROM {reference} is not "
                f"<image>:<tag>@sha256:<64 hex> (digest = immutable pin, tag = what Dependabot follows)")
        if alias:
            stages.add(alias)
    return failures


def compose_images(path: Path) -> list[tuple[int, str]]:
    """Every (line, raw image value) of a compose file, without a YAML dependency."""
    images: list[tuple[int, str]] = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        match = IMAGE_KEY.match(line)
        if match and not line.lstrip().startswith("#"):
            images.append((number, unquote(match.group("value"))))
    return images


def compose_pin(value: str) -> str | None:
    """The registry reference a compose ``image:`` value stands for, or None for ${MINOS_IMAGE}-style values."""
    default = VARIABLE_WITH_DEFAULT.match(value)
    if default:
        return default.group("default")
    variable = VARIABLE.match(value)
    if variable:
        return None if variable.group("name") in UNPINNED_ALLOWED_VARIABLES else value
    return value


def find_compose_violations(path: Path, root: Path) -> tuple[list[str], set[str]]:
    failures: list[str] = []
    service_repositories: set[str] = set()
    for number, value in compose_images(path):
        pin = compose_pin(value)
        if pin is None:
            continue
        if not PINNED.match(pin):
            failures.append(
                f"{path.relative_to(root).as_posix()}:{number}: image {value} is not "
                f"<image>:<tag>@sha256:<64 hex> (a ${{VAR:-default}} default must be pinned too)")
            continue
        service_repositories.add(pin.split(":", 1)[0])
    return failures, service_repositories


def iter_scanned_files(root: Path):
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in SCANNED_SUFFIXES:
            continue
        relative = path.relative_to(root).as_posix()
        if any(part in SKIPPED_DIRECTORIES for part in path.relative_to(root).parts):
            continue
        if relative.startswith(SKIPPED_RELATIVE_PREFIXES):
            continue
        if re.fullmatch(r"docker/compose[^/]*\.ya?ml", relative):
            continue
        yield path, relative


def find_duplicate_violations(root: Path, repositories: set[str]) -> list[str]:
    failures: list[str] = []
    if not repositories:
        return failures
    pattern = re.compile(r"(?<![\w./-])(?:" + "|".join(re.escape(name) for name in sorted(repositories))
                         + r"):[\w][\w.-]*")
    for path, relative in iter_scanned_files(root):
        try:
            text = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            continue
        for number, line in enumerate(text.splitlines(), start=1):
            if pattern.search(line):
                failures.append(
                    f"{relative}:{number}: a managed-service image is written outside "
                    f"docker/compose*.yaml, its single source")
    return failures


def find_ci_wiring_violations(root: Path) -> list[str]:
    workflow = root / PR_CI
    if not workflow.is_file():
        return [f"{PR_CI} not found: the gate would not run in CI"]
    runs = [line.strip() for line in workflow.read_text(encoding="utf-8").splitlines()
            if not line.lstrip().startswith("#")]
    return [f"{PR_CI} does not run `python {step}`" for step in CI_STEPS
            if not any(line.removeprefix("- ").startswith(f"run: python {step}") for line in runs)]


def check(root: Path) -> tuple[list[str], int]:
    docker = root / "docker"
    failures: list[str] = find_ci_wiring_violations(root)
    inspected = 0
    repositories: set[str] = set()
    if docker.is_dir():
        for path in sorted(docker.glob("Dockerfile*")):
            inspected += 1
            failures += find_dockerfile_violations(path, root)
        for path in sorted(list(docker.glob("compose*.yaml")) + list(docker.glob("compose*.yml"))):
            inspected += 1
            compose_failures, names = find_compose_violations(path, root)
            failures += compose_failures
            repositories |= names
    failures += find_duplicate_violations(root, repositories)
    if inspected == 0:
        failures.append("no docker/Dockerfile* or docker/compose*.yaml found: the gate would check nothing")
    return failures, inspected


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    arguments = parser.parse_args(argv)
    failures, inspected = check(arguments.root.resolve())
    if failures:
        print("CONTAINER IMAGE PIN GATE FAILED")
        for failure in failures:
            print(f" - {failure}")
        return 1
    print(f"CONTAINER IMAGE PIN GATE SUCCESS (files checked={inspected})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
