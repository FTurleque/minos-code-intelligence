#!/usr/bin/env python3
"""Heuristic gate: a script that runs a MINOS command able to exit 3 must not treat every non-zero code as a failure.

The commands that can exit 3 (a partial result: valid for the registry entries that could be read, some were
counted and set aside) are listed once, in scripts/lib/partial-result-commands.json, and pinned to the real CLI by
PartialResultCommandsContractTest. This gate finds the scripts that *name* one of them and checks that each such
script shows it knows about the partial result: it reads the shared list (scripts/lib/MinosExitCode.ps1 or the JSON),
accepts a literal `0, 3`, delegates through `-Action Admin` (docker/scripts/mcp-lifecycle.ps1 accepts {0, 3}), or is
named in KNOWN_GAPS below with its reason.

WHAT IT DOES NOT DETECT (it is a heuristic, not a proof):
  * a command assembled at run time (string concatenation, a variable holding the verb, a wrapper with another name);
  * a file that loads the shared list but still tests one call strictly: the check is per file, not per call;
  * a dot-source or a `0, 3` that is dead code (written but never reached), or that sits where it does not run: only
    whole lines starting with `#` are ignored, so a PowerShell block comment `<# ... #>`, a here-string, a trailing
    comment or a Python docstring that contains the dot-source line or the JSON name still counts as handling;
  * the `run:` steps of .github/workflows (only scripts/, docker/ and packaging/ are scanned);
  * a strict call placed within two lines below an unrelated `-Action Admin` (it is read as delegated), and a
    single-word command such as `'inspect'` quoted for another purpose (a false positive; none exists today);
  * consumers that are not scripts: the IntelliJ plugin (Gradle, covered by its own tests), Java tests, anything
    outside this repository (NEXUS, users' own scripts);
  * a command that starts exiting 3 without being listed: that is PartialResultCommandsContractTest's job, not this one.

STATUS: heuristic, and blocking. The job that runs it (`Static invariants (single run)` in pr-ci.yml) is a check
required by the repository ruleset (.github/required-checks.json, audit finding G6 / AUD-DEP-09), so a failure here
blocks a merge. If its false positives ever outweigh its value, remove its step from that job: the ruleset is not touched.

KNOWN_GAPS may only shrink: an entry whose file no longer needs it, or no longer exists, fails the gate, and an entry
that is not in GAP_CEILING fails it too. Growing the ceiling is a visible change to this file, to be refused in review.
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = Path("scripts/lib/partial-result-commands.json")
SCAN_DIRECTORIES = ("scripts", "docker", "packaging")
SCAN_SUFFIXES = {".ps1", ".psm1", ".py", ".sh", ".cmd", ".bat"}
EXCLUDED_PARTS = {"history", "__pycache__", "target", "node_modules"}
EXCLUDED_PREFIXES = ("scripts/lib/",)  # the shared list and its own tests are not consumers

# File -> why it is allowed to name a partial-result command without handling the code. Shrinks only.
KNOWN_GAPS = {
    "scripts/m24/run-provider-e2e.py": (
        "already fails before its first command: it looks for target/minos-code-intelligence-0.2.0-SNAPSHOT-all.jar "
        "(the revision is 1.3.0-SNAPSHOT) and is frozen by assertion (ADR 0043); see Q25-Q26-SUIVI.md section 2.2"
    ),
}

GAP_CEILING = frozenset({"scripts/m24/run-provider-e2e.py"})  # a literal, on purpose: see the module docstring

# What counts as "knows about the partial result". Comment lines are removed first: naming the list in a comment is
# not loading it. A PowerShell script must really dot-source the helper; any dialect may read the JSON or accept 0 and 3.
HANDLED = re.compile(
    r"^\s*\.\s+.*MinosExitCode\.ps1|partial-result-commands\.json|@\(\s*0\s*,\s*3\s*\)|\{\s*0\s*,\s*3\s*\}|"
    r"AcceptedExitCodes\s+@\(\s*0\s*,\s*3",
    re.MULTILINE,
)
COMMENT_LINE = re.compile(r"^\s*#.*$", re.MULTILINE)
DELEGATED_LINE = re.compile(r"-Action\s+Admin\b")


def load_commands(root: Path) -> list[list[str]]:
    spec = json.loads((root / SPEC).read_text(encoding="utf-8"))
    commands = [command.split(" ") for command in spec["commands"]]
    if not commands:
        raise SystemExit("PARTIAL RESULT CONSUMER GATE FAILED: scripts/lib/partial-result-commands.json lists no command")
    return commands


def invocation_patterns(words: list[str]) -> list[re.Pattern[str]]:
    quote = r"""['"]"""
    quoted = r"\s*,\s*".join(quote + re.escape(word) + quote for word in words)
    spaced = r"\s+".join(re.escape(word) for word in words)
    after_launcher = r"(?:\bminos(?:\.cmd)?|MinosLauncher|\.jar['\"]?)\s+" + spaced + r"(?=\s|$|['\"])"
    return [re.compile(quoted), re.compile(after_launcher)]


def named_commands(text: str, commands: list[list[str]]) -> list[tuple[str, int]]:
    """(command, line number) of every line that names a partial-result command, minus the `-Action Admin` ones
    (the same line or the two before it)."""
    patterns = [(" ".join(words), pattern) for words in commands for pattern in invocation_patterns(words)]
    found: list[tuple[str, int]] = []
    lines = text.splitlines()
    for number, line in enumerate(lines, start=1):
        # A call split over lines puts `-Action Admin` on the line that opens the argument list.
        if DELEGATED_LINE.search(" ".join(lines[max(0, number - 3):number])):
            continue
        for name, pattern in patterns:
            if pattern.search(line):
                found.append((name, number))
                break
    return found


def candidate_files(root: Path) -> list[Path]:
    files: list[Path] = []
    for directory in SCAN_DIRECTORIES:
        base = root / directory
        if not base.is_dir():
            continue
        for path in sorted(base.rglob("*")):
            relative = path.relative_to(root)
            if not path.is_file() or path.suffix not in SCAN_SUFFIXES:
                continue
            if any(part in EXCLUDED_PARTS for part in relative.parts):
                continue
            if relative.as_posix().startswith(EXCLUDED_PREFIXES) or path.name.startswith("test_"):
                continue
            files.append(path)
    return files


def check(root: Path) -> list[str]:
    commands = load_commands(root)
    problems: list[str] = []
    seen_gaps: set[str] = set()
    for path in candidate_files(root):
        relative = path.relative_to(root).as_posix()
        text = path.read_text(encoding="utf-8", errors="ignore")
        code = COMMENT_LINE.sub("", text)  # keeps the line count: comment lines become empty lines
        named = named_commands(code, commands)
        if not named:
            continue
        handled = bool(HANDLED.search(code))
        if relative in KNOWN_GAPS:
            seen_gaps.add(relative)
            if handled:
                problems.append(f"{relative}: now handles the partial result; remove it from KNOWN_GAPS (the list only shrinks)")
            continue
        if not handled:
            names = ", ".join(sorted({f"`{name}` (line {line})" for name, line in named}))
            problems.append(
                f"{relative}: runs {names}, which can exit 3 (partial result), and neither reads "
                f"scripts/lib/partial-result-commands.json nor accepts `0, 3` nor delegates through `-Action Admin`")
    for relative in sorted(set(KNOWN_GAPS) - GAP_CEILING):
        problems.append(f"{relative}: KNOWN_GAPS may not grow (it is not in GAP_CEILING)")
    for relative in sorted(set(KNOWN_GAPS) - seen_gaps):
        problems.append(f"{relative}: listed in KNOWN_GAPS but no longer names a partial-result command or no longer exists; remove it")
    return problems


def self_test() -> int:
    """A consumer that ignores the list fails; each accepted route, the history archive and a stale gap behave as written."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "scripts" / "lib").mkdir(parents=True)
        (root / "scripts" / "lib" / "partial-result-commands.json").write_text(
            json.dumps({"commands": ["project list", "inspect", "index-status"]}), encoding="utf-8")

        def write(relative: str, text: str) -> None:
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")

        write("scripts/a/strict.ps1", "$o = Invoke-Minos @('index-status', 'x')\nif ($LASTEXITCODE -ne 0) { throw 'failed' }\n")
        write("scripts/a/strict.py", 'run(["java", "-jar", JAR, "project", "list"])\nif code != 0: raise RuntimeError\n')
        write("scripts/a/strict.sh", "minos inspect alpha\n[ $? -eq 0 ] || exit 1\n")
        write("scripts/a/comment_only.ps1",
              "# see partial-result-commands.json and MinosExitCode.ps1\n$o = Invoke-Minos @('index-status', 'x')\n"
              "if ($LASTEXITCODE -ne 0) { throw }\n")
        write("scripts/b/shared.ps1", ". scripts\\lib\\MinosExitCode.ps1\n$o = Invoke-Minos @('index-status', 'x')\n")
        write("scripts/b/literal.ps1", "Invoke-Minos @('inspect', 'x') -AcceptedExitCodes @(0, 3)\n")
        write("scripts/b/delegated.ps1", "Invoke-Workflow -Action Admin -MinosArguments @('project', 'list')\n")
        write("scripts/b/unrelated.ps1", "Invoke-Minos @('index', 'x')\nif ($LASTEXITCODE -ne 0) { throw 'failed' }\n")
        write("scripts/history/m1/old.ps1", "Invoke-Minos @('index-status', 'x')\nif ($LASTEXITCODE -ne 0) { throw }\n")
        write("scripts/lib/test_something.py", "run(['inspect'])\n")

        expected = {"scripts/a/strict.ps1", "scripts/a/strict.py", "scripts/a/strict.sh", "scripts/a/comment_only.ps1"}
        failing = {problem.split(":", 1)[0] for problem in check(root) if "KNOWN_GAPS" not in problem}
        if failing != expected:
            print(f"PARTIAL RESULT CONSUMER GATE SELF-TEST FAILED: expected {sorted(expected)}, got {sorted(failing)}",
                  file=sys.stderr)
            return 1

        # A stale KNOWN_GAPS entry fails, and a gap that is real passes.
        stale = [problem for problem in check(root) if "KNOWN_GAPS" in problem]
        if len(stale) != len(KNOWN_GAPS):
            print("PARTIAL RESULT CONSUMER GATE SELF-TEST FAILED: a KNOWN_GAPS entry without a file must be reported",
                  file=sys.stderr)
            return 1
        gap = next(iter(KNOWN_GAPS))
        write(gap, 'cli(env, "project", "inspect", name)\n')
        write(gap, 'cli(env, "project", "list")\n')
        if [problem for problem in check(root) if problem.startswith(gap)]:
            print("PARTIAL RESULT CONSUMER GATE SELF-TEST FAILED: a real known gap must pass", file=sys.stderr)
            return 1
    print("PARTIAL RESULT CONSUMER GATE SELF-TEST SUCCESS (strict consumer fails; shared list, literal 0/3, delegation, "
          "archive, test file and known gap behave as written)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true", help="run the gate's own fixture-based self-test and exit")
    arguments = parser.parse_args()
    if arguments.self_test:
        return self_test()
    problems = check(ROOT)
    if problems:
        print("PARTIAL RESULT CONSUMER GATE FAILED (advisory heuristic, see the module docstring for what it cannot see)",
              file=sys.stderr)
        for problem in problems:
            print(f" - {problem}", file=sys.stderr)
        return 1
    print(f"PARTIAL RESULT CONSUMER GATE SUCCESS (advisory; files scanned={len(candidate_files(ROOT))}, "
          f"known gaps={len(KNOWN_GAPS)})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
