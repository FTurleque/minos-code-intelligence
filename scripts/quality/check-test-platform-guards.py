#!/usr/bin/env python3
"""Fail when a Java test passes silently behind an operating-system early return.

Audit finding AUD-TST-04. JUnit counts a test restricted by ``@EnabledOnOs`` as skipped, with its reason, on the
other system; a test that ends with ``if (!windows) { return; }`` is counted as passed without running anything.
This gate reads every Java test source of every module (``**/src/test/**/*.java``, IntelliJ plugin included;
``target/``, ``build/``, ``.gradle/``, worktrees and nested checkouts skipped) and refuses each statement
``if (<condition>) [{] return;`` whose ``return`` carries no value and whose condition reads the operating system,
on one line or several:

* directly: ``os.name``, ``currentPlatform``, ``OS.``, a ``windows`` / ``linux`` / ``mac`` / ``macos`` identifier
  (``isWindows()``, ``windows()``, ``IS_LINUX``...), or the literals ``"win"``, ``"linux"``, ``"mac"``;
* through a local variable of the same method assigned from an expression that reads ``os.name``.

A ``return <value>;`` under such a guard is accepted: a helper that returns a value makes no test pass silently.

Tolerated sites are a closed list (``EXCEPTIONS``): file, exact number of occurrences, reason. They must sit outside
any method annotated ``@Test``, ``@ParameterizedTest``, ``@RepeatedTest`` or ``@TestFactory`` (JUnit extension
callbacks, test tooling). The list is a ratchet: an entry whose count no longer matches fails, so it only shrinks.
The gate also fails when it reads no test file at all.

What it does not prove: capability guards (``if (!createSymbolicLink(...)) return;``) are not looked for; ``assume*``
skips are counted by JUnit and not inspected; the parsing is lexical (comments and literals are masked, braces are
counted), not a Java compiler.

Self-test: ``--self-test`` (temporary trees, one refused and one accepted case per rule).
"""
from __future__ import annotations

import argparse
import re
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
LABEL = "TEST PLATFORM GUARDS GATE"
SKIPPED_DIRECTORIES = {"target", "build", ".gradle", "node_modules", ".git", "__pycache__"}

# Closed list of tolerated operating-system early returns, outside test methods: path -> (occurrences, reason).
EXCEPTIONS: dict[str, tuple[int, str]] = {
    "minos-runtime-local/src/test/java/com/minos/runtime/local/sandbox/AppContainerGrantLeakCheck.java": (
        2, "JUnit extension callbacks (beforeAll/afterAll): AppContainer grants exist on Windows only, "
           "nothing to record or compare elsewhere"),
    "minos-engine/src/test/java/com/minos/testsupport/IcaclsTestAccessControl.java": (
        2, "Windows ACL test tooling (icacls): the access-control service is a no-op on other systems"),
}

TEST_ANNOTATIONS = re.compile(r"@(?:org\.junit\.jupiter\.api\.)?(?:Test|ParameterizedTest|RepeatedTest|TestFactory)\b")
OS_CONDITION = re.compile(
    r"os\.name|currentPlatform|\bOS\.|\b(?:is_?)?(?:windows|linux|macos|mac)\b|\"(?:win|linux|mac)\""
    r"|\b\w*(?:Windows|Linux|MacOs|MacOS)\b",
    re.IGNORECASE)
IF_KEYWORD = re.compile(r"\bif\s*\(")
BARE_RETURN = re.compile(r"\s*(?:\{\s*)?return\s*;")
OS_NAME_LOCAL = re.compile(r"\b([A-Za-z_]\w*)\s*=[^=;][^;]*?os\.name", re.DOTALL)
CONTROL_KEYWORDS = re.compile(r"\b(?:if|for|while|switch|catch|synchronized|try|else|do|finally|new|return)\s*$")


def mask(text: str, keep_literals: bool = False) -> str:
    """Comments replaced by spaces (newlines kept); literal contents too unless ``keep_literals``.

    With literals masked, braces and parentheses become countable; with literals kept, conditions such as
    ``contains("win")`` stay readable.
    """
    out = list(text)

    def blank(start: int, end: int) -> None:
        for k in range(start, min(end, len(text))):
            if text[k] != "\n":
                out[k] = " "

    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            blank(i, end)
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            blank(i, end)
            i = end
        elif text.startswith('"""', i):
            end = text.find('"""', i + 3)
            end = n if end < 0 else end + 3
            if not keep_literals:
                blank(i + 3, end - 3)
            i = end
        elif text[i] in "\"'":
            quote = text[i]
            k = i + 1
            while k < n and text[k] != quote and text[k] != "\n":
                k += 2 if text[k] == "\\" else 1
            if not keep_literals:
                blank(i + 1, k)
            i = k + 1
        else:
            i += 1
    return "".join(out)


def closing_parenthesis(masked: str, opening: int) -> int:
    depth = 0
    for k in range(opening, len(masked)):
        if masked[k] == "(":
            depth += 1
        elif masked[k] == ")":
            depth -= 1
            if depth == 0:
                return k
    return -1


def enclosing_method(masked: str, position: int) -> tuple[int, str] | None:
    """Opening brace and header of the innermost method (or constructor) body around ``position``."""
    stack: list[int] = []
    for k in range(position):
        if masked[k] == "{":
            stack.append(k)
        elif masked[k] == "}" and stack:
            stack.pop()
    for brace in reversed(stack):
        before = masked[:brace].rstrip()
        before = re.sub(r"\bthrows\s+[\w.,\s<>]+$", "", before).rstrip()
        if not before.endswith(")"):
            continue
        opening = before.rfind("(")
        depth = 0
        for k in range(len(before) - 1, -1, -1):
            if before[k] == ")":
                depth += 1
            elif before[k] == "(":
                depth -= 1
                if depth == 0:
                    opening = k
                    break
        if CONTROL_KEYWORDS.search(before[:opening]) or before[:opening].rstrip().endswith("->"):
            continue
        start = max(before.rfind(";", 0, opening), before.rfind("}", 0, opening), before.rfind("{", 0, opening))
        return brace, masked[start + 1:brace]
    return None


def platform_guards(text: str) -> list[tuple[int, bool, str]]:
    """Each operating-system early return: (line, inside a test method, excerpt)."""
    masked = mask(text)
    readable = mask(text, keep_literals=True)
    found: list[tuple[int, bool, str]] = []
    for match in IF_KEYWORD.finditer(masked):
        opening = match.end() - 1
        closing = closing_parenthesis(masked, opening)
        if closing < 0 or not BARE_RETURN.match(masked, closing + 1):
            continue
        condition = readable[opening + 1:closing]
        method = enclosing_method(masked, match.start())
        reads_os = bool(OS_CONDITION.search(condition))
        if not reads_os and method is not None:
            body = readable[method[0]:match.start()]
            names = {name for name in OS_NAME_LOCAL.findall(body)}
            reads_os = any(re.search(rf"\b{re.escape(name)}\b", condition) for name in names)
        if not reads_os:
            continue
        in_test = method is not None and bool(TEST_ANNOTATIONS.search(method[1]))
        line = text.count("\n", 0, match.start()) + 1
        excerpt = " ".join(readable[match.start():BARE_RETURN.match(masked, closing + 1).end()].split())
        found.append((line, in_test, excerpt))
    return found


def test_sources(root: Path) -> list[Path]:
    return [path for path in gate_cli.repository_files(root, SKIPPED_DIRECTORIES)
            if path.suffix == ".java" and "/src/test/" in "/" + path.relative_to(root).as_posix()]


def guard_failures(relative: str, guards: list[tuple[int, bool, str]], declared: bool) -> list[str]:
    """Failures of the guards of one file: all of them when undeclared, those inside a test method otherwise."""
    failures: list[str] = []
    for line, in_test, excerpt in guards:
        if not declared:
            failures.append(f"{relative}:{line}: `{excerpt}` - a test restricted to one system must use "
                            f"@EnabledOnOs (counted as skipped), not an early return (counted as passed)")
        elif in_test:
            failures.append(f"{relative}:{line}: `{excerpt}` is in a test method; an exception only covers "
                            f"extension callbacks and test tooling: use @EnabledOnOs")
    return failures


def exception_failures(exceptions: dict[str, tuple[int, str]], occurrences: dict[str, int]) -> list[str]:
    """The exception list is a ratchet: each entry has a reason and the exact number of occurrences found."""
    failures: list[str] = []
    for relative, (expected, reason) in sorted(exceptions.items()):
        actual = occurrences.get(relative, 0)
        if not reason.strip():
            failures.append(f"exception {relative} has no written reason")
        if actual != expected:
            failures.append(f"exception {relative} declares {expected} occurrence(s), found {actual}: "
                            f"tighten the exception list")
    return failures


def check(root: Path, exceptions: dict[str, tuple[int, str]] | None = None) -> tuple[list[str], int, int]:
    exceptions = EXCEPTIONS if exceptions is None else exceptions
    files = test_sources(root)
    failures: list[str] = []
    if not files:
        failures.append(f"no Java test source found under {root.name or root}: the gate read nothing")
    occurrences: dict[str, int] = {}
    for path in files:
        relative = path.relative_to(root).as_posix()
        guards = platform_guards(path.read_text(encoding="utf-8"))
        if guards:
            occurrences[relative] = len(guards)
            failures.extend(guard_failures(relative, guards, relative in exceptions))
    failures.extend(exception_failures(exceptions, occurrences))
    return failures, len(files), len(exceptions)


def self_test_cases() -> list[tuple[str, dict[str, str], dict[str, tuple[int, str]], int, str]]:
    """(name, files, exceptions, expected exit code, text expected in the failures)."""
    windows_two_lines = ("class A {\n    @Test\n    void t() {\n        if (!CommandLocator.isWindows()) {\n"
                         "            return;\n        }\n        run();\n    }\n}\n")
    one_line = "class B {\n    @Test\n    void t() {\n        if (!SystemInfo.isLinux()) return;\n        run();\n    }\n}\n"
    local = ("class C {\n    @Test\n    void t() throws Exception {\n"
             "        String os = System.getProperty(\"os.name\", \"\").toLowerCase(java.util.Locale.ROOT);\n"
             "        if (!os.contains(\"linux\")) return;\n        run();\n    }\n}\n")
    literal = ("class D {\n    @ParameterizedTest\n    void t(int x) {\n"
               "        if (!System.getProperty(\"os.name\", \"\").toLowerCase().contains(\"win\")) {\n"
               "            return;\n        }\n    }\n}\n")
    enabled = "class E {\n    @Test\n    @EnabledOnOs(OS.WINDOWS)\n    void t() {\n        run();\n    }\n}\n"
    unrelated = "class F {\n    @Test\n    void t() {\n        if (list.isEmpty()) {\n            return;\n        }\n    }\n}\n"
    value_helper = ("class G {\n    private static String shell() {\n        if (isWindows()) {\n"
                    "            return \"cmd\";\n        }\n        return \"sh\";\n    }\n}\n")
    commented = "class H {\n    @Test\n    void t() {\n        // if (!isWindows()) return;\n        run();\n    }\n}\n"
    extension = ("class Ext implements BeforeAllCallback {\n    @Override\n"
                 "    public void beforeAll(ExtensionContext c) throws Exception {\n        if (!windows()) return;\n"
                 "        record();\n    }\n    @Override\n    public void afterAll(ExtensionContext c) throws Exception {\n"
                 "        if (!windows()) return;\n        compare();\n    }\n}\n")
    one_left = ("class Ext implements BeforeAllCallback {\n    @Override\n"
                "    public void beforeAll(ExtensionContext c) throws Exception {\n        if (!windows()) return;\n"
                "    }\n}\n")
    in_test = "class T {\n    @Test\n    void t() {\n        if (!windows()) return;\n    }\n}\n"
    ext = "m/src/test/java/Ext.java"
    return [
        ("two-line guard", {"m/src/test/java/A.java": windows_two_lines}, {}, 1, "m/src/test/java/A.java:4"),
        ("one-line guard", {"m/src/test/java/B.java": one_line}, {}, 1, "m/src/test/java/B.java:4"),
        ("local variable from os.name", {"m/src/test/java/C.java": local}, {}, 1, "m/src/test/java/C.java:5"),
        ("os.name literal in a parameterized test", {"m/src/test/java/D.java": literal}, {}, 1, "D.java:4"),
        ("plugin sources are read", {"minos-intellij/src/test/kotlin/x/A.java": windows_two_lines}, {}, 1, "A.java:4"),
        ("no test source", {"m/src/main/java/A.java": windows_two_lines}, {}, 1, "read nothing"),
        ("@EnabledOnOs accepted", {"m/src/test/java/E.java": enabled}, {}, 0, ""),
        ("unrelated early return accepted", {"m/src/test/java/F.java": unrelated}, {}, 0, ""),
        ("valued return of a helper accepted", {"m/src/test/java/G.java": value_helper}, {}, 0, ""),
        ("commented guard accepted", {"m/src/test/java/H.java": commented}, {}, 0, ""),
        ("main sources ignored", {"m/src/test/java/E.java": enabled, "m/src/main/java/A.java": one_line}, {}, 0, ""),
        ("build output ignored", {"m/src/test/java/E.java": enabled, "m/target/src/test/A.java": one_line}, {}, 0, ""),
        ("declared exception accepted", {ext: extension}, {ext: (2, "extension callback")}, 0, ""),
        ("undeclared extension refused", {ext: extension}, {}, 1, "Ext.java:4"),
        ("stale exception refused", {ext: one_left}, {ext: (2, "extension callback")}, 1, "declares 2 occurrence(s), found 1"),
        ("exception without reason refused", {ext: extension}, {ext: (2, " ")}, 1, "no written reason"),
        ("exception in a test method refused", {"m/src/test/java/T.java": in_test},
         {"m/src/test/java/T.java": (1, "tooling")}, 1, "is in a test method"),
    ]


def case_errors(name: str, files: dict[str, str], exceptions: dict[str, tuple[int, str]], expected: int,
                needle: str) -> list[str]:
    """Runs the gate on a temporary tree holding ``files`` and compares its verdict with the expected one."""
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        for relative, content in files.items():
            (root / relative).parent.mkdir(parents=True, exist_ok=True)
            (root / relative).write_text(content, encoding="utf-8")
        failures, _, _ = check(root, exceptions)
    code = 1 if failures else 0
    if code != expected or (needle and not any(needle in failure for failure in failures)):
        return [f"{name}: expected code {expected} with '{needle}', got {code}: {failures}"]
    return []


def self_test() -> int:
    cases = self_test_cases()
    errors: list[str] = []
    for case in cases:
        errors.extend(case_errors(*case))
    # The real exception list must match the repository it guards.
    real_failures, real_files, _ = check(DEFAULT_ROOT)
    stale = [failure for failure in real_failures if failure.startswith("exception ")]
    errors.extend(f"repository: {failure}" for failure in stale)
    if gate_cli.report_failures(f"{LABEL} SELF-TEST", errors):
        return 1
    print(f"{LABEL} SELF-TEST SUCCESS (cases={len(cases)}, repository test files={real_files})")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--self-test", action="store_true", help="run the gate's fixture-based self-test and exit")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    failures, files, exceptions = check(args.root)
    if gate_cli.report_failures(LABEL, failures):
        return 1
    print(f"{LABEL} SUCCESS (test files read={files}, declared exceptions={exceptions})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
