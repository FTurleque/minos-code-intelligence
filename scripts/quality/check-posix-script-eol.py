#!/usr/bin/env python3
"""Fail when a POSIX shell script can be checked out with CRLF line endings (AUD-DEP-02).

A Windows clone with ``core.autocrlf=true`` converts every text file without an ``eol`` attribute to CRLF on
checkout. A shell script converted this way no longer runs under bash (``set: pipefail\\r: invalid option name``),
neither with Git for Windows nor from WSL2 on the same working copy. The ``.gitattributes`` rules pin these files to
LF on every checkout; this gate keeps the rule covering each new script.

A POSIX script is a file tracked by Git whose name ends with ``.sh``, or whose first recorded line starts with ``#!``
and names ``sh``, ``bash``, ``dash``, ``ksh`` or ``zsh`` right after the ``#!``, a ``/`` or ``env`` and its options
(``#!/usr/bin/fish`` is not one), outside any ``fixtures/`` directory. The properties file of the Maven wrapper, read
by the POSIX ``mvnw``, is checked by name when it is tracked. For each file, two facts are read from Git itself (``git ls-files --eol`` and ``git grep --cached``): the
resolved attribute must carry the ``eol=lf`` token and the recorded content must have no carriage return (``i/lf``).
Git resolves ``eol=lf`` alone as ``text eol=lf``; ``-text eol=lf`` disables the conversion and is refused.

Fail-closed: ``git`` missing, a root that is not the top level of a Git repository, or zero POSIX script found is a
failure, never a success on zero files. Messages name repository-relative paths only.

What this gate does not prove: the bytes of an existing working copy. The attribute acts on future checkouts; a
clone made before the rule keeps its CRLF files until they are checked out again (docs/developer/testing.md).

Self-test (temporary Git repositories, no network): python scripts/quality/check-posix-script-eol.py --self-test
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
LABEL = "POSIX SCRIPT EOL CHECK"
WRAPPER_PROPERTIES = ".mvn/wrapper/maven-wrapper.properties"
# The interpreter name follows the "#!", a "/" or the "env" word and its options: "fish" or "/usr/bin/fish" is not "sh".
POSIX_SHEBANG = re.compile(r"^#!\s*(?:\S*/)?(?:env\s+(?:-\S+\s+)*(?:\S*/)?)?(?:sh|bash|dash|ksh|zsh)(?:\s|$)")
# Only the variables Git needs to start and to locate its own configuration; nothing else is inherited.
ALLOWED_ENVIRONMENT = ("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH")


class GateError(Exception):
    """A check that could not be made: the gate fails instead of concluding."""


def git_environment() -> dict[str, str]:
    environment = {name: os.environ[name] for name in ALLOWED_ENVIRONMENT if name in os.environ}
    environment.update(LC_ALL="C", GIT_TERMINAL_PROMPT="0", GIT_OPTIONAL_LOCKS="0")
    return environment


def git_executable() -> str:
    executable = shutil.which("git")
    if executable is None:
        raise GateError("git was not found on the PATH; line endings cannot be read")
    return executable


def git(root: Path, *arguments: str, accepted: tuple[int, ...] = (0,)) -> bytes:
    executable = git_executable()
    try:
        completed = subprocess.run([executable, "-C", str(root), *arguments], capture_output=True,
                                   env=git_environment(), check=False)
    except OSError as error:
        raise GateError(f"git could not be started ({type(error).__name__})") from None
    if completed.returncode not in accepted:
        raise GateError(f"git {arguments[0]} failed with exit code {completed.returncode}")
    return completed.stdout


def require_repository_root(root: Path) -> None:
    if not root.is_dir():
        raise GateError("the root is not a directory")
    git_executable()
    try:
        top = git(root, "rev-parse", "--show-toplevel").decode("utf-8").strip()
    except GateError:
        raise GateError("the root is not a Git repository") from None
    if os.path.normcase(str(Path(top).resolve())) != os.path.normcase(str(root.resolve())):
        raise GateError("the root is not the top level of its Git repository; only part of it would be checked")


def eol_entries(root: Path) -> dict[str, tuple[str, str]]:
    """Tracked path -> (index line endings, attribute text), from ``git ls-files --eol -z``."""
    entries: dict[str, tuple[str, str]] = {}
    for record in git(root, "ls-files", "--eol", "-z").split(b"\0"):
        if not record:
            continue
        info, _, path = record.decode("utf-8", errors="replace").partition("\t")
        fields = info.split(None, 2)
        if len(fields) < 2 or not fields[0].startswith("i/"):
            raise GateError("unexpected output of git ls-files --eol")
        attributes = fields[2] if len(fields) > 2 else "attr/"
        entries[path] = (fields[0], attributes.strip())
    return entries


def shebang_scripts(root: Path) -> set[str]:
    """Tracked files whose recorded first line starts with a POSIX shell shebang."""
    output = git(root, "grep", "--cached", "-I", "-n", "-z", "-e", "^#!", accepted=(0, 1))
    found: set[str] = set()
    for line in output.decode("utf-8", errors="replace").splitlines():
        parts = line.split("\0", 2)
        if len(parts) == 3 and parts[1] == "1" and POSIX_SHEBANG.match(parts[2].rstrip("\r")):
            found.add(parts[0])
    return found


def is_fixture(path: str) -> bool:
    return "fixtures" in path.split("/")[:-1]


def rule_for(path: str) -> str:
    return "*.sh text eol=lf" if path.endswith(".sh") else f"{path} text eol=lf"


def check(root: Path) -> tuple[list[str], int]:
    """(failures, number of files checked)."""
    try:
        require_repository_root(root)
        entries = eol_entries(root)
        scripts = {path for path in entries if path.endswith(".sh")} | shebang_scripts(root)
    except GateError as error:
        return [str(error)], 0
    scripts = {path for path in scripts if path in entries and not is_fixture(path)}
    if not scripts:
        return ["no POSIX shell script is tracked; the gate refuses to succeed on zero files"], 0
    checked = sorted(scripts | ({WRAPPER_PROPERTIES} & entries.keys()))
    failures: list[str] = []
    for path in checked:
        index, attributes = entries[path]
        if "eol=lf" not in attributes.split():
            failures.append(f"{path}: no 'eol=lf' attribute ({attributes}); add '{rule_for(path)}' to .gitattributes")
        if index != "i/lf":
            failures.append(f"{path}: recorded as {index}, expected i/lf; run 'git add --renormalize -- {path}'")
    return failures, len(checked)


def run(root: Path) -> int:
    failures, count = check(root)
    if gate_cli.report_failures(LABEL, failures):
        return 1
    print(f"{LABEL} SUCCESS (POSIX scripts checked={count})")
    return 0


def make_repository(directory: Path, files: dict[str, bytes], attributes: str | None,
                    attributes_after: bool = False) -> None:
    """A Git repository whose index holds ``files``; ``.gitattributes`` is staged before or after them."""
    git_executable = shutil.which("git")
    if git_executable is None:
        raise GateError("git was not found on the PATH; the self-test cannot build its repositories")

    def run_git(*arguments: str) -> None:
        subprocess.run([git_executable, "-C", str(directory), "-c", "core.autocrlf=false", *arguments],
                       check=True, capture_output=True, env=git_environment())

    directory.mkdir(parents=True)
    run_git("init", "--quiet")

    def stage_attributes() -> None:
        if attributes is not None:
            (directory / ".gitattributes").write_bytes(attributes.encode("utf-8"))
            run_git("add", ".gitattributes")

    if not attributes_after:
        stage_attributes()
    for name, content in files.items():
        target = directory / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        run_git("add", name)
    if attributes_after:
        stage_attributes()


def check_without_git(root: Path, empty_directory: Path) -> list[str]:
    """Failures of ``check`` on ``root`` while the PATH names only an empty directory."""
    empty_directory.mkdir()
    saved = os.environ.get("PATH")
    os.environ["PATH"] = str(empty_directory)
    try:
        return check(root)[0]
    finally:
        if saved is None:
            del os.environ["PATH"]
        else:
            os.environ["PATH"] = saved


def self_test() -> int:
    lf_script = b"#!/usr/bin/env bash\nset -euo pipefail\necho ok\n"
    crlf_script = lf_script.replace(b"\n", b"\r\n")
    rules = "*.sh text eol=lf\noutil text eol=lf\n"
    fish_script = b"#!/usr/bin/fish\necho ok\n"
    # (name, files, attributes, attributes staged after the files, expected exit code, expected message fragment,
    #  expected number of files checked on success)
    cases = [
        ("script without attribute", {"build.sh": lf_script}, None, False, 1, "add '*.sh text eol=lf'", None),
        ("shebang script without extension", {"outil": lf_script, "a.sh": lf_script}, "*.sh text eol=lf\n", False, 1,
         "outil: no 'eol=lf' attribute (attr/); add 'outil text eol=lf' to .gitattributes", None),
        ("script recorded with CRLF", {"build.sh": crlf_script}, rules, True, 1, "recorded as i/crlf", None),
        ("repository without script", {"README.md": b"# readme\n", "tool.py": b"#!/usr/bin/env python3\n"}, rules,
         False, 1, "zero files", None),
        ("script in a fixtures directory only", {"src/fixtures/x.sh": lf_script}, None, False, 1, "zero files", None),
        ("wrapper properties without attribute", {"a.sh": lf_script, WRAPPER_PROPERTIES: b"k=v\n"}, rules, False, 1,
         f"{WRAPPER_PROPERTIES}: no 'eol=lf' attribute", None),
        ("attribute disabling text conversion", {"a.sh": lf_script}, "*.sh -text eol=lf\n", False, 1,
         "a.sh: no 'eol=lf' attribute (attr/-text)", None),
        ("fish shebangs are not POSIX scripts", {"a.sh": lf_script, "outil": fish_script,
                                                 "bin/run": b"#!/usr/bin/env fish\necho ok\n"},
         "*.sh text eol=lf\n", False, 0, None, 1),
        ("eol=lf without text, resolved by Git as text eol=lf", {"a.sh": lf_script}, "*.sh eol=lf\n", False, 0, None,
         1),
        ("compliant repository", {"a.sh": lf_script, "outil": lf_script, "bin/run": b"#!/bin/sh\nexit 0\n",
                                  "bin/env-s": b"#!/usr/bin/env -S bash -e\nexit 0\n",
                                  "tool.py": b"#!/usr/bin/env python3\n"},
         rules + "bin/run text eol=lf\nbin/env-s text eol=lf\n", False, 0, None, 4),
    ]
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as scratch:
        base = Path(scratch)
        for index, (name, files, attributes, after, expected, fragment, checked) in enumerate(cases):
            repository = base / f"case-{index}"
            make_repository(repository, files, attributes, after)
            found, count = check(repository)
            code = 1 if found else 0
            if code != expected or (fragment and not any(fragment in line for line in found)):
                failures.append(f"{name}: exit {code} (expected {expected}), failures {found}")
            if checked is not None and count != checked:
                failures.append(f"{name}: {count} files checked (expected {checked})")
        outside = base / "not-a-repository"
        outside.mkdir()
        found, _ = check(outside)
        if not found or "not a Git repository" not in found[0] or scratch in found[0] or str(outside) in found[0]:
            failures.append(f"directory outside a repository: {found}")
        compliant = base / f"case-{len(cases) - 1}"
        found, _ = check(compliant / "bin")
        if not found or "top level" not in found[0]:
            failures.append(f"subdirectory of a repository: {found}")
        found = check_without_git(compliant, base / "empty-path")
        if found != ["git was not found on the PATH; line endings cannot be read"]:
            failures.append(f"git missing from the PATH: {found}")
    if gate_cli.report_failures(f"{LABEL} SELF-TEST", failures):
        return 1
    print(f"{LABEL} SELF-TEST SUCCESS (cases={len(cases) + 3})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--self-test", action="store_true", help="run the cases on temporary Git repositories")
    args = parser.parse_args(argv)
    if args.self_test:
        try:
            return self_test()
        except (GateError, subprocess.CalledProcessError, OSError) as error:
            print(f"{LABEL} SELF-TEST FAILED: {type(error).__name__}", file=sys.stderr)
            return 1
    return run(args.root)


if __name__ == "__main__":
    raise SystemExit(main())
