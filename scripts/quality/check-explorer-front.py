#!/usr/bin/env python3
"""Run the tests of the semantic explorer page under Node and fail closed.

Audit finding AUD-TST-02: the script of the explorer page
(``minos-explorer/src/main/resources/com/minos/explorer/web/app.js``) was only searched for substrings.
This gate parses it with ``node --check`` and executes the ``node:test`` files of
``minos-explorer/src/test/js`` (``*.test.mjs``), which load the real script into a ``node:vm`` context
with a fake DOM. No npm package, no ``package.json``.

It fails (exit code 1) when Node is missing from ``PATH`` or older than ``MINIMUM_NODE_MAJOR``, when the
script does not parse, when a test fails, or when no test ran. None of these cases is reported as skipped.
Node receives an argument list (no shell) and a reduced environment.

What it does not prove: that the page behaves the same in a real browser (no browser runs here).

Self-test: ``--self-test`` replays the gate on mutated temporary copies (broken syntax, empty test folder,
``PATH`` without Node, minimum above the installed version, markup sinks, event handler attribute, code
generation, capture radius, contract constant without a label), then reads the failed test names from two TAP
samples: tests at the top level, and tests nested under one test per file.

Deviation from ``gate_cli.run``: this gate keeps its own ``main()`` because ``gate_cli.parse_root`` only knows
``--root`` and this gate also takes ``--self-test`` (same choice as ``check-bounded-helpers.py``); it still
reports through ``gate_cli.report_failures``.
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
# node:test with the TAP reporter and vm contexts with codeGeneration are stable from this major on.
MINIMUM_NODE_MAJOR = 20
WEB = "minos-explorer/src/main/resources/com/minos/explorer/web"
APP_JS = f"{WEB}/app.js"
TESTS = "minos-explorer/src/test/js"
TEST_SUFFIX = ".test.mjs"
TIMEOUT_SECONDS = 600
# The only variables Node receives: enough to start a process on Linux and Windows, nothing secret.
ENVIRONMENT_ALLOWLIST = ("PATH", "SYSTEMROOT", "WINDIR", "TEMP", "TMP", "TMPDIR")
LABEL = "Explorer front gate"


VERSION = re.compile(r"^v(\d+)\.\d+\.\d+")
TAP_COUNTER = re.compile(r"^# (tests|pass|fail|cancelled) (\d+)\s*$", re.MULTILINE)
# A failed test of the TAP reporter at any depth: depending on the Node major, the tests of a file list are at the
# top level or nested (indented) under one test per file, whose name is then the file path.
FAILED_TEST = re.compile(r"^[ \t]*not ok \d+ - (.+?)\s*$", re.MULTILINE)


def node_environment(path_value: str) -> dict[str, str]:
    """The reduced environment of every Node process: the allow-listed variables only, ``PATH`` replaced."""
    environment = {name: value for name, value in os.environ.items()
                   if name.upper() in ENVIRONMENT_ALLOWLIST and name.upper() != "PATH"}
    environment["PATH"] = path_value
    environment["NO_COLOR"] = "1"
    return environment


def run_node(node: str, arguments: list[str], root: Path, environment: dict[str, str]) -> subprocess.CompletedProcess:
    return subprocess.run([node, *arguments], cwd=root, env=environment, capture_output=True, text=True,
                          encoding="utf-8", errors="replace", timeout=TIMEOUT_SECONDS, check=False)


def tail(text: str, count: int = 40) -> str:
    return "\n".join(text.strip().splitlines()[-count:])


def failed_tests(tap: str) -> list[str]:
    """Names of the failed tests and of their enclosing tests, in TAP order, each name once."""
    return list(dict.fromkeys(FAILED_TEST.findall(tap)))


def check(root: Path, path_value: str | None = None,
          minimum: int = MINIMUM_NODE_MAJOR) -> tuple[list[str], int, str]:
    """Returns ``(failures, tests run, Node version)``; any failure means the gate fails."""
    path_value = os.environ.get("PATH", "") if path_value is None else path_value
    node = shutil.which("node", path=path_value)
    if node is None:
        return [f"Node.js not found in PATH: the explorer front tests need Node >= {minimum}"], 0, ""
    environment = node_environment(path_value)
    try:
        answer = run_node(node, ["--version"], root, environment)
    except (OSError, subprocess.TimeoutExpired) as error:
        return [f"Node.js could not be started ({type(error).__name__}): the gate needs Node >= {minimum}"], 0, ""
    version = answer.stdout.strip()
    match = VERSION.match(version)
    if answer.returncode != 0 or match is None:
        return [f"Node.js did not report a version (exit code {answer.returncode}): the gate needs Node >= {minimum}"], 0, ""
    if int(match.group(1)) < minimum:
        return [f"Node.js {version} is older than the minimum Node >= {minimum}"], 0, version

    app = root / APP_JS
    if not app.is_file():
        return [f"{APP_JS} not found"], 0, version
    try:
        syntax = run_node(node, ["--check", APP_JS], root, environment)
    except subprocess.TimeoutExpired:
        return [f"node --check {APP_JS} did not finish within {TIMEOUT_SECONDS} s"], 0, version
    if syntax.returncode != 0:
        return [f"{APP_JS} does not parse (node --check, exit code {syntax.returncode}):\n{tail(syntax.stderr)}"], 0, version

    directory = root / TESTS
    tests = sorted(path for path in directory.glob(f"*{TEST_SUFFIX}") if path.is_file()) if directory.is_dir() else []
    if not tests:
        return [f"no test file {TESTS}/*{TEST_SUFFIX}: zero tests is a failure, not a skip"], 0, version
    relative = [path.relative_to(root).as_posix() for path in tests]
    try:
        result = run_node(node, ["--test", "--test-reporter=tap", *relative], root, environment)
    except subprocess.TimeoutExpired:
        return [f"node --test did not finish within {TIMEOUT_SECONDS} s"], 0, version
    counters = {name: int(value) for name, value in TAP_COUNTER.findall(result.stdout)}
    ran = counters.get("tests", 0)
    failures: list[str] = []
    if result.returncode != 0 or counters.get("fail", 0) or counters.get("cancelled", 0):
        failed = failed_tests(result.stdout)
        failures.append(f"node --test failed (exit code {result.returncode}, {counters.get('fail', 0)} failed, "
                        f"{counters.get('cancelled', 0)} cancelled, {ran} tests); failed tests: "
                        f"{'; '.join(failed) or 'none named'}\n{tail(result.stdout)}\n{tail(result.stderr)}")
    elif ran <= 0 or counters.get("pass", 0) != ran:
        failures.append(f"node --test reported {ran} tests and {counters.get('pass', 0)} passes in {len(relative)} files: "
                        "no test result can be trusted")
    return failures, ran, version


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--self-test", action="store_true", help="replay the gate on mutated temporary copies")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test(args.root)
    failures, tests, version = check(args.root)
    if gate_cli.report_failures(LABEL, failures):
        return 1
    print(f"{LABEL} OK: Node {version} (minimum {MINIMUM_NODE_MAJOR}), {APP_JS} parses, {tests} tests passed.")
    return 0


# ---- self-test


def copy_tree(root: Path, target: Path) -> None:
    """Copies the page resources and the front tests to ``target``, at the same relative paths."""
    shutil.copytree(root / WEB, target / WEB)
    shutil.copytree(root / TESTS, target / TESTS)


def replace(path: Path, old: str, new: str) -> None:
    """Replaces one literal anchor; a missing anchor fails the self-test instead of mutating nothing."""
    text = path.read_text(encoding="utf-8")
    if text.count(old) != 1:
        raise AssertionError(f"anchor {old!r} not found exactly once in {path.name}")
    path.write_text(text.replace(old, new), encoding="utf-8")


def empty_tests(target: Path) -> None:
    for test in (target / TESTS).glob(f"*{TEST_SUFFIX}"):
        test.unlink()


Case = tuple[str, Callable[[Path], None], dict, int, str]


def cases() -> list[Case]:
    app = Path(APP_JS)
    labels = Path(TESTS) / "labels.test.mjs"
    no_node = os.pathsep.join([])
    # Each mutation must fail the test that owns the behaviour, named in the failure.
    hostile_list = "list, header, detail and neighbours render hostile values as text"
    radius = "a tie keeps the first point received and a point beyond the radius is not selected"
    constant = "every contract constant has a label in the page, and every label a contract constant"
    return [
        ("repository state passes", lambda t: None, {}, 0, ""),
        ("broken syntax names the file",
         lambda t: replace(t / app, "  return value.length > 14 ? value.slice(0, 12) + \"…\" : value;\n}",
                           "  return value.length > 14 ? value.slice(0, 12) + \"…\" : value;\n"),
         {}, 1, "app.js"),
        ("empty test folder", empty_tests, {}, 1, "no test"),
        ("PATH without Node names Node and the minimum", lambda t: None, {"path_value": no_node}, 1,
         f"Node >= {MINIMUM_NODE_MAJOR}"),
        ("installed Node under the minimum", lambda t: None, {"minimum": 1000}, 1, "older than"),
        ("markup sink insertAdjacentHTML",
         lambda t: replace(t / app, "node.textContent = String(text);",
                           "node.insertAdjacentHTML(\"beforeend\", String(text));"), {}, 1, hostile_list),
        ("markup sink under a computed property name",
         lambda t: replace(t / app, "node.textContent = String(text);",
                           "node[\"inner\" + \"HTML\"] = String(text);"), {}, 1, hostile_list),
        ("event handler attribute",
         lambda t: replace(t / app, "node.textContent = String(text);",
                           "node.setAttribute(\"onclick\", String(text));"), {}, 1, hostile_list),
        ("code generation from a string",
         lambda t: replace(t / app, "  if (!value) return \"—\";", "  if (!value) return \"—\";\n  eval(\"0\");"),
         {}, 1, hostile_list),
        ("capture radius changed",
         lambda t: replace(t / app, "let bestDistance = 64;", "let bestDistance = 100;"), {}, 1, radius),
        ("contract constant without a label",
         lambda t: replace(t / labels, "  indexStates: [\"DISABLED\",", "  indexStates: [\"UNKNOWN_STATE\", \"DISABLED\","),
         {}, 1, constant),
    ]


# TAP samples of a failed run: tests at the top level, and tests nested under one test per file.
TAP_FLAT = """TAP version 13
# Subtest: renders as text
not ok 1 - renders as text
  ---
  ...
ok 2 - zooms
1..2
# tests 2
# pass 1
# fail 1
"""
TAP_NESTED = """TAP version 13
# Subtest: minos-explorer/src/test/js/sinks.test.mjs
    # Subtest: renders as text
    not ok 1 - renders as text
      ---
      ...
    ok 2 - zooms
    1..2
not ok 1 - minos-explorer/src/test/js/sinks.test.mjs
  ---
  ...
1..1
# tests 2
# pass 1
# fail 1
"""


def tap_errors() -> list[str]:
    errors = []
    for name, sample, expected in (
            ("flat TAP", TAP_FLAT, ["renders as text"]),
            ("nested TAP", TAP_NESTED, ["renders as text", "minos-explorer/src/test/js/sinks.test.mjs"])):
        found = failed_tests(sample)
        if found != expected:
            errors.append(f"{name}: failed tests {found}, expected {expected}")
        else:
            print(f"ok - {name} names the failed test")
    return errors


def self_test(root: Path) -> int:
    errors: list[str] = tap_errors()
    for name, mutate, options, expected, fragment in cases():
        with tempfile.TemporaryDirectory(prefix="minos-explorer-front-") as directory:
            target = Path(directory)
            try:
                copy_tree(root, target)
                mutate(target)
                failures, tests, _ = check(target, **options)
            except (OSError, AssertionError) as error:
                errors.append(f"{name}: {error}")
                continue
            code = 1 if failures else 0
            message = "\n".join(failures)
            if code != expected:
                errors.append(f"{name}: exit code {code}, expected {expected} ({message or 'no failure'})")
            elif expected == 1 and fragment not in message:
                errors.append(f"{name}: failure does not mention {fragment!r}: {message}")
            elif expected == 0 and tests <= 0:
                errors.append(f"{name}: success with {tests} tests")
            else:
                print(f"ok - {name}")
    if gate_cli.report_failures(LABEL + " SELF-TEST", errors):
        return 1
    print(f"{LABEL} self-test OK: {len(cases())} cases and 2 TAP samples.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
