#!/usr/bin/env python3
"""Prove that two builds of the same commit give the same shaded JAR (AUD-DEP-11).

Two modes:

* ``compare A.jar B.jar``: equal SHA-256, or the list of entries that differ (present in one archive only, other
  content, other date, other order). Nothing else is compared: a JAR is reproducible when its bytes are.
* ``twice --repository <git tree> --work <empty directory>``: clones the commit of the tree twice (``git clone``,
  so nothing of the working copy leaks in), builds ``package -DskipTests -pl minos-app -am`` in each clone with the
  Maven wrapper and ``-Dproject.build.outputTimestamp`` set to the date of the commit, then compares the two shaded
  JARs as above. It never runs ``clean`` in the repository itself. ``--without-commit-timestamp`` leaves the
  timestamp to the POM (measurement of a build that does not pass it); ``--maven`` builds with another Maven than
  the wrapper (the enforcer accepts [3.9,4.0): Maven 3.10 sets a default timestamp, Maven 3.9 does not).

What this proves: reproducibility on one operating system, for the JAR only. A JAR built on Linux and one built on
Windows may differ (resources filtered with the line endings of the system); zips, the installer and images are out
of reach of ``project.build.outputTimestamp`` and are not claimed reproducible.

Self-test (simulated archives, no Maven): python scripts/quality/check-reproducible-build.py --self-test
"""
from __future__ import annotations

import argparse
import hashlib
import os
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

# minos-app writes its JARs to the target directory of the root (the shaded JAR other consumers read there).
SHADED_JAR_GLOB = "target/minos-code-intelligence-*-all.jar"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def differences(first: Path, second: Path) -> list[str]:
    """The entries of two archives that differ, by name, in a stable order; empty when the archives are equal."""
    with zipfile.ZipFile(first) as a, zipfile.ZipFile(second) as b:
        left = {info.filename: info for info in a.infolist()}
        right = {info.filename: info for info in b.infolist()}
        found: list[str] = []
        for name in sorted(left.keys() - right.keys()):
            found.append(f"{name}: only in {first.name}")
        for name in sorted(right.keys() - left.keys()):
            found.append(f"{name}: only in {second.name}")
        for name in sorted(left.keys() & right.keys()):
            one, two = left[name], right[name]
            if one.date_time != two.date_time:
                found.append(f"{name}: date {one.date_time} != {two.date_time}")
            if one.CRC != two.CRC or one.file_size != two.file_size or a.read(name) != b.read(name):
                found.append(f"{name}: content differs")
            elif (one.external_attr, one.extra, one.compress_type) != (two.external_attr, two.extra, two.compress_type):
                found.append(f"{name}: attributes differ")
        common = [name for name in left if name in right]
        if not found and common != [name for name in right if name in left]:
            found.append("entries are written in another order")
    return found


def compare(first: Path, second: Path) -> int:
    for path in (first, second):
        if not path.is_file():
            print(f"REPRODUCIBLE BUILD CHECK FAILED: {path} does not exist", file=sys.stderr)
            return 2
    one, two = sha256(first), sha256(second)
    if one == two:
        print(f"REPRODUCIBLE BUILD CHECK SUCCESS (sha256={one})")
        return 0
    found = differences(first, second) or ["no entry differs: the archive metadata (comment, central directory) does"]
    print(f"REPRODUCIBLE BUILD CHECK FAILED: {first.name} sha256={one}, {second.name} sha256={two}", file=sys.stderr)
    for line in found[:200]:
        print(f" - {line}", file=sys.stderr)
    if len(found) > 200:
        print(f" - ... {len(found) - 200} more", file=sys.stderr)
    return 1


def git(repository: Path, *arguments: str) -> str:
    return subprocess.run(["git", "-C", str(repository), *arguments], check=True, capture_output=True,
                          text=True).stdout.strip()


def build(clone: Path, timestamp: str | None, maven: str | None = None) -> Path:
    wrapper = maven or str(clone / ("mvnw.cmd" if os.name == "nt" else "mvnw"))
    command = [wrapper, "-B", "-ntp", "-q", "package", "-DskipTests", "-pl", "minos-app", "-am"]
    if timestamp is not None:
        command.append(f"-Dproject.build.outputTimestamp={timestamp}")
    subprocess.run(command, cwd=clone, check=True)
    jars = sorted(clone.glob(SHADED_JAR_GLOB))
    if len(jars) != 1:
        raise RuntimeError(f"expected one shaded JAR in {clone}, found {len(jars)}")
    return jars[0]


def twice(repository: Path, work: Path, with_timestamp: bool = True, maven: str | None = None) -> int:
    if work.exists() and any(work.iterdir()):
        print(f"REPRODUCIBLE BUILD CHECK FAILED: {work} is not empty", file=sys.stderr)
        return 2
    commit = git(repository, "rev-parse", "HEAD")
    timestamp = git(repository, "show", "-s", "--format=%cI", commit) if with_timestamp else None
    jars = []
    for name in ("first", "second"):
        clone = work / name
        subprocess.run(["git", "clone", "--quiet", "--no-hardlinks", str(repository), str(clone)], check=True)
        git(clone, "checkout", "--quiet", "--detach", commit)
        jars.append(build(clone, timestamp, maven))
    print(f"commit {commit}, project.build.outputTimestamp={timestamp}")
    return compare(*jars)


def write_archive(path: Path, entries: list[tuple[str, bytes, tuple[int, ...]]]) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        for name, content, date in entries:
            archive.writestr(zipfile.ZipInfo(name, date_time=date), content)


def self_test() -> int:
    date = (2026, 10, 15, 12, 0, 0)
    other = (2026, 10, 16, 9, 30, 0)
    base = [("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n", date), ("com/minos/A.class", b"\xca\xfe", date)]
    cases = [
        ("identical archives", base, base, 0, None),
        ("an entry with another content", base, [base[0], ("com/minos/A.class", b"\xca\xfe\x00", date)], 1,
         "com/minos/A.class: content differs"),
        ("an entry with another date", base, [base[0], ("com/minos/A.class", b"\xca\xfe", other)], 1,
         "com/minos/A.class: date"),
        ("an entry present in one archive only", base, base + [("com/minos/B.class", b"\x00", date)], 1,
         "com/minos/B.class: only in"),
        ("the same entries in another order", base, list(reversed(base)), 1, "another order"),
    ]
    failures = []
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for index, (name, left, right, expected, message) in enumerate(cases):
            first, second = root / f"{index}-a.jar", root / f"{index}-b.jar"
            write_archive(first, left)
            write_archive(second, right)
            code = compare(first, second)
            found = differences(first, second)
            if code != expected or (message and not any(message in line for line in found)):
                failures.append(f"{name}: exit {code} (expected {expected}), differences {found}")
        if compare(root / "missing-a.jar", root / "missing-b.jar") != 2:
            failures.append("a missing archive is not refused")
    if failures:
        print("REPRODUCIBLE BUILD SELF-TEST FAILED", file=sys.stderr)
        for failure in failures:
            print(f" - {failure}", file=sys.stderr)
        return 1
    print(f"REPRODUCIBLE BUILD SELF-TEST SUCCESS (cases={len(cases) + 1})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--self-test", action="store_true", help="run the self-test on simulated archives")
    sub = parser.add_subparsers(dest="mode")
    compare_parser = sub.add_parser("compare", help="compare two JARs")
    compare_parser.add_argument("first", type=Path)
    compare_parser.add_argument("second", type=Path)
    twice_parser = sub.add_parser("twice", help="build the shaded JAR twice from clean clones and compare")
    twice_parser.add_argument("--repository", type=Path, default=Path(__file__).resolve().parents[2])
    twice_parser.add_argument("--work", type=Path, required=True)
    twice_parser.add_argument("--without-commit-timestamp", action="store_true",
                              help="measurement only: build with the POM's own timestamp, not the commit date")
    twice_parser.add_argument("--maven", help="measurement only: a Maven executable instead of the wrapper")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if args.mode == "compare":
        return compare(args.first, args.second)
    if args.mode == "twice":
        return twice(args.repository.resolve(), args.work.resolve(), not args.without_commit_timestamp, args.maven)
    parser.error("choose --self-test, compare or twice")
    return 2


if __name__ == "__main__":
    raise SystemExit(main())
