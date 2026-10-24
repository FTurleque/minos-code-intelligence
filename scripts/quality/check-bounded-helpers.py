#!/usr/bin/env python3
"""Fail when a bounded-arithmetic helper of production code has a name that hides its overflow behaviour.

The same private helper name used to mean three things: `safeAdd` saturated in six classes, threw an
`OutputBudgetExceededException` in one and an `IllegalStateException` in another; `checkedAdd` saturated in one
class and threw an `IOException` in two others. Copying a call from one file to another silently changed what
happened on overflow.

Rules, on every Java source of a `minos-*/src/main` tree (comments and string literals ignored):

1. No method named `safeAdd` or `safeMultiply` is declared, whatever its signature: a helper that saturates is
   called `saturatingAdd` / `saturatingMultiply`, one that throws says so (`addOrThrow`, `checkedAdd`).
2. A method named `checkedAdd` throws: its body contains a `throw` statement. A `checkedAdd` that saturates is a
   `saturatingAdd`.
3. An access-ordered map (`new LinkedHashMap<...>(capacity, loadFactor, true)`, spaces and line breaks allowed) is
   built only by the shared weighted LRU cache (SHARED_CACHE) or by a file of the closed list
   ACCESS_ORDER_EXCEPTIONS, each with its reason: a weighted LRU cache is written once, the exceptions are
   structures without a weight. The shared cache or a listed file that no longer builds such a map fails: the list
   only shrinks.

Each failure names the file and the line. On success the gate prints how many bounded helpers it checked and the
closed list.

What this gate does not prove: that a helper does what its name says beyond the presence of a `throw` (its tests
do), nor anything about inline `Math.addExact` / `Math.multiplyExact` calls; an access-ordered map built through a
variable flag, or by a subclass of LinkedHashMap, is not seen.

`--root` points the gate at another tree (default: this repository). `--self-test` runs the gate on temporary
trees: every refusal and every acceptance below has its own fixture.
"""
from __future__ import annotations

import argparse
import re
import shutil
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path

DEFAULT_ROOT = Path(__file__).resolve().parents[2]
LABEL = "BOUNDED HELPERS GATE"
SKIPPED_DIRECTORIES = frozenset({"target", "build", ".git", "node_modules", "__pycache__"})

AMBIGUOUS_NAMES = ("safeAdd", "safeMultiply")
THROWING_NAME = "checkedAdd"
# Every name a bounded helper may carry; the gate counts their declarations.
CHECKED_NAMES = AMBIGUOUS_NAMES + (THROWING_NAME, "saturatingAdd", "saturatingMultiply", "addOrThrow")

COMMENTS_AND_LITERALS = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'", re.DOTALL)
# A return type (primitive or capitalised, possibly generic or an array) followed by the helper name and '('.
DECLARATION = re.compile(
    r"\b(?:void|boolean|byte|char|short|int|long|float|double|[A-Z][\w.]*(?:<[^;(){}]*>)?)"
    r"(?:\s*\[\s*\])*\s+(" + "|".join(CHECKED_NAMES) + r")\s*\(")
THROW = re.compile(r"\bthrow\b")

SHARED_CACHE = "minos-engine/src/main/java/com/minos/store/cache/WeightedLruCache.java"
# Closed list: production files allowed to build an access-ordered map outside the shared cache, with the reason.
ACCESS_ORDER_EXCEPTIONS: dict[str, str] = {
    "minos-engine/src/main/java/com/minos/hosted/HostedDenialThrottle.java":
        "sliding refusal windows (one ArrayDeque<Instant> per key), no weight",
    "minos-application/src/main/java/com/minos/program/analysis/SourceStateKeyMemo.java":
        "memo with a reuse delay on a monotonic clock, bounded by count, no weight",
}
NEW_LINKED_HASH_MAP = re.compile(r"\bnew\s+(?:java\.util\.)?LinkedHashMap\s*(?:<[^;(){}]*>)?\s*\(")


def blank_comments_and_literals(code: str) -> str:
    """Blanks comments and string literals but keeps every newline, so that line numbers stay exact."""
    return COMMENTS_AND_LITERALS.sub(lambda match: re.sub(r"[^\n]", " ", match.group(0)), code)


def closing_index(code: str, opening: int) -> int:
    """Index of the bracket that closes the one at `opening`, or -1 when the text ends first."""
    pairs = {"(": ")", "{": "}"}
    expected = pairs[code[opening]]
    depth = 0
    for index in range(opening, len(code)):
        character = code[index]
        if character == code[opening]:
            depth += 1
        elif character == expected:
            depth -= 1
            if depth == 0:
                return index
    return -1


def method_body(code: str, open_parenthesis: int) -> str | None:
    """The body of the method whose parameter list opens at `open_parenthesis`; None when it is not a declaration
    with a body (a call, or an abstract method)."""
    close = closing_index(code, open_parenthesis)
    if close < 0:
        return None
    rest = code[close + 1:]
    header = re.match(r"\s*(?:throws\s+[\w.,\s<>]+?)?\s*\{", rest)
    if header is None:
        return None
    opening = close + 1 + header.end() - 1
    end = closing_index(code, opening)
    return code[opening + 1:end] if end >= 0 else None


def top_level_arguments(code: str, open_parenthesis: int) -> list[str] | None:
    """The stripped arguments of the call whose parameter list opens at `open_parenthesis`; None if unbalanced."""
    close = closing_index(code, open_parenthesis)
    if close < 0:
        return None
    arguments: list[str] = []
    current: list[str] = []
    depth = 0
    for character in code[open_parenthesis + 1:close]:
        if character in "([{":
            depth += 1
        elif character in ")]}":
            depth -= 1
        if character == "," and depth == 0:
            arguments.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    arguments.append("".join(current).strip())
    return arguments


def access_order_map_lines(code: str) -> list[int]:
    """Lines where a blanked source builds a LinkedHashMap whose third argument is the literal `true`."""
    lines: list[int] = []
    for match in NEW_LINKED_HASH_MAP.finditer(code):
        arguments = top_level_arguments(code, match.end() - 1)
        if arguments is not None and len(arguments) == 3 and arguments[2] == "true":
            lines.append(code.count("\n", 0, match.start()) + 1)
    return lines


def java_sources(root: Path) -> list[Path]:
    """Production Java sources of every `minos-*` module under `root`, sorted."""
    sources: list[Path] = []
    for module in sorted(path for path in root.glob("minos-*") if path.is_dir()):
        main = module / "src" / "main"
        if main.is_dir():
            sources.extend(path for path in gate_cli.repository_files(main, SKIPPED_DIRECTORIES)
                           if path.suffix == ".java")
    return sorted(sources)


def check_helper_names(relative: str, code: str) -> tuple[list[str], int]:
    """(failures, declarations checked) for one blanked source."""
    failures: list[str] = []
    checked = 0
    for match in DECLARATION.finditer(code):
        name = match.group(1)
        body = method_body(code, match.end() - 1)
        if body is None:
            continue
        checked += 1
        line = code.count("\n", 0, match.start(1)) + 1
        if name in AMBIGUOUS_NAMES:
            failures.append(f"{relative}:{line} declares {name}, a name that does not say what happens on overflow; "
                            f"call it saturating{name.removeprefix('safe')} if it saturates, or name the failure "
                            f"({name.removeprefix('safe').lower()}OrThrow) if it throws")
        elif name == THROWING_NAME and not THROW.search(body):
            failures.append(f"{relative}:{line} declares {THROWING_NAME} without a throw statement; a helper that "
                            f"saturates is called saturatingAdd")
    return failures, checked


def check(root: Path, shared_cache: str | None = SHARED_CACHE,
          exceptions: dict[str, str] | None = None) -> tuple[list[str], int]:
    """(failures, bounded helpers checked) for the tree under `root`. `shared_cache` and `exceptions` default to the
    real ones; a self-test passes None and {} to look at helper names alone."""
    exceptions = ACCESS_ORDER_EXCEPTIONS if exceptions is None else exceptions
    allowed = set(exceptions) | ({shared_cache} if shared_cache else set())
    failures: list[str] = [f"ACCESS_ORDER_EXCEPTIONS entry {entry} needs a written reason"
                           for entry, reason in sorted(exceptions.items()) if not reason.strip()]
    checked = 0
    used: set[str] = set()
    for source in java_sources(root):
        relative = source.relative_to(root).as_posix()
        code = blank_comments_and_literals(source.read_text(encoding="utf-8"))
        found, count = check_helper_names(relative, code)
        failures.extend(found)
        checked += count
        for line in access_order_map_lines(code):
            if relative in allowed:
                used.add(relative)
            else:
                failures.append(f"{relative}:{line} builds an access-ordered LinkedHashMap; a weighted LRU cache "
                                f"uses {SHARED_CACHE}, a structure without weight is listed in "
                                f"ACCESS_ORDER_EXCEPTIONS with its reason")
    for entry in sorted(allowed - used):
        kind = "SHARED_CACHE" if entry == shared_cache else "ACCESS_ORDER_EXCEPTIONS entry"
        failures.append(f"{kind} {entry} is absent or builds no access-ordered map; remove the entry "
                        f"(the list only shrinks)")
    return failures, checked


# --- self-test -------------------------------------------------------------------------------------------------

SATURATING_SAFE_ADD = """package com.example;

final class Weights {
    long total(long left, long right) {
        return safeAdd(left, right);
    }

    private static long safeAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }
}
"""
LABELLED_SAFE_ADD = """package com.example;

final class Counters {
    private static long safeAdd(long left, long right, String label) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(label + " exceeds supported range", exception);
        }
    }
}
"""
SAFE_MULTIPLY = """package com.example;

final class Products {
    private static long safeMultiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { return Long.MAX_VALUE; }
    }
}
"""
SATURATING_CHECKED_ADD = """package com.example;

final class Queries {
    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
"""
THROWING_CHECKED_ADD = """package com.example;

import java.io.IOException;

final class Budget {
    private static long checkedAdd(long left, long right) throws IOException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IOException("byte counter overflow", exception);
        }
    }
}
"""
NAMED_BY_BEHAVIOUR = """package com.example;

final class Named {
    // A comment that mentions private static long safeAdd(long left, long right) { return 0L; } is not code.
    private static final String TEXT = "long safeMultiply(long left, long right) { return 0L; }";

    private static long saturatingAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private static long saturatingMultiply(long left, long right) {
        try { return Math.multiplyExact(left, right); }
        catch (ArithmeticException exception) { return Long.MAX_VALUE; }
    }
}
"""
REAL_THROWING_CHECKED_ADD = "minos-integration-git/src/main/java/com/minos/integration/git/RemoteCloneBudget.java"


def write_source(root: Path, module: str, name: str, text: str) -> str:
    path = root / module / "src" / "main" / "java" / "com" / "example" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return path.relative_to(root).as_posix()


def expect(label: str, condition: bool, detail: object) -> list[str]:
    return [] if condition else [f"{label}: {detail}"]


def run_case(text: str, name: str = "Sample.java", module: str = "minos-sample") -> tuple[list[str], int, str]:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        relative = write_source(root, module, name, text)
        failures, checked = check(root, None, {})
        return failures, checked, relative


def refused_at(failures: list[str], relative: str, line: int) -> bool:
    return len(failures) == 1 and failures[0].startswith(f"{relative}:{line} ")


def self_test() -> int:
    problems: list[str] = []

    failures, checked, relative = run_case(SATURATING_SAFE_ADD)
    problems += expect("safeAdd with two parameters is refused at its declaration line",
                       refused_at(failures, relative, 8) and checked == 1, (failures, checked))

    failures, _, relative = run_case(LABELLED_SAFE_ADD)
    problems += expect("safeAdd with three parameters is refused", refused_at(failures, relative, 4), failures)

    failures, _, relative = run_case(SAFE_MULTIPLY)
    problems += expect("safeMultiply is refused", refused_at(failures, relative, 4), failures)

    failures, _, relative = run_case(SATURATING_CHECKED_ADD)
    problems += expect("checkedAdd without a throw is refused",
                       refused_at(failures, relative, 4) and "without a throw" in failures[0], failures)

    failures, checked, _ = run_case(THROWING_CHECKED_ADD)
    problems += expect("checkedAdd that throws is accepted", failures == [] and checked == 1, (failures, checked))

    failures, checked, _ = run_case(NAMED_BY_BEHAVIOUR)
    problems += expect("helpers named by behaviour are accepted, comments and literals ignored",
                       failures == [] and checked == 2, (failures, checked))

    failures, checked, _ = run_case(SATURATING_SAFE_ADD.replace("safeAdd", "saturatingAdd"), module="tools")
    problems += expect("only minos-* modules are read", failures == [] and checked == 0, (failures, checked))

    real = DEFAULT_ROOT / REAL_THROWING_CHECKED_ADD
    if real.is_file():
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            copy = root / REAL_THROWING_CHECKED_ADD
            copy.parent.mkdir(parents=True)
            shutil.copyfile(real, copy)
            failures, checked = check(root, None, {})
            problems += expect("the real throwing checkedAdd is accepted", failures == [] and checked == 1,
                               (failures, checked))
            original = copy.read_text(encoding="utf-8")
            mutated = re.sub(r"throw new IOException\(\"remote cache byte counter overflow\", exception\);",
                             "return Long.MAX_VALUE;", original)
            copy.write_text(mutated, encoding="utf-8")
            failures, _ = check(root, None, {})
            problems += expect("the real checkedAdd mutated to saturate is refused",
                               mutated != original and len(failures) == 1
                               and failures[0].startswith(REAL_THROWING_CHECKED_ADD + ":"), failures)
    else:
        problems.append(f"missing witness file {REAL_THROWING_CHECKED_ADD}")

    problems += self_test_access_order_maps()

    if gate_cli.report_failures(LABEL + " SELF-TEST", problems):
        return 1
    print(f"{LABEL} SELF-TEST SUCCESS (ambiguous names, saturating and throwing checkedAdd, comments, real witness, "
          f"access-ordered maps, closed list, stale and unreasoned entries)")
    return 0


HAND_WRITTEN_CACHE = """package com.example;

import java.util.LinkedHashMap;
import java.util.Map;

final class Cache {
    private final Map<String, Long> insertionOrdered = new LinkedHashMap<>(16, 0.75f, false);
    private final Map<String, Long> plain = new LinkedHashMap<>();
    private final Map<String, Long> accessOrdered = new LinkedHashMap<>(
            32, 0.75f,
            true);
    // new LinkedHashMap<>(16, 0.75f, true) in a comment is not code.
}
"""
ACCESS_ORDERED_LINE = 9
ACCESS_ORDERED_ONE_LINER = (
    "final class X { java.util.Map<String, String> m = new java.util.LinkedHashMap<>(16, 0.75f, true); }\n")
WITNESS_EXCEPTION = "minos-engine/src/main/java/com/minos/hosted/HostedDenialThrottle.java"


def write_at(root: Path, relative: str, text: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def self_test_access_order_maps() -> list[str]:
    """Rule 3 on temporary trees: shared cache and closed list accepted, any other access-ordered map refused at its
    line, stale or unreasoned entries refused; then a copy of a real listed file moved outside the list."""
    problems: list[str] = []
    shared = "minos-sample/src/main/java/com/example/SharedCache.java"
    listed = "minos-sample/src/main/java/com/example/Throttle.java"
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        write_at(root, shared, ACCESS_ORDERED_ONE_LINER)
        write_at(root, listed, ACCESS_ORDERED_ONE_LINER)
        failures, _ = check(root, shared, {listed: "no weight"})
        problems += expect("the shared cache and a listed file are accepted", failures == [], failures)

        hand_written = "minos-sample/src/main/java/com/example/Cache.java"
        write_at(root, hand_written, HAND_WRITTEN_CACHE)
        failures, _ = check(root, shared, {listed: "no weight"})
        problems += expect("a hand-written access-ordered map is refused at its line, other maps and comments are not",
                           refused_at(failures, hand_written, ACCESS_ORDERED_LINE), failures)
        (root / hand_written).unlink()

        gone = "minos-sample/src/main/java/com/example/Gone.java"
        failures, _ = check(root, shared, {listed: "no weight", gone: "no weight"})
        problems += expect("an entry whose file is absent is refused",
                           len(failures) == 1 and gone in failures[0], failures)
        failures, _ = check(root, shared, {listed: " "})
        problems += expect("an entry without a reason is refused",
                           len(failures) == 1 and "needs a written reason" in failures[0], failures)
        write_at(root, listed, "final class Throttle { }\n")
        failures, _ = check(root, shared, {listed: "no weight"})
        problems += expect("an entry that no longer builds an access-ordered map is refused",
                           len(failures) == 1 and listed in failures[0], failures)
        write_at(root, shared, "final class SharedCache { }\n")
        failures, _ = check(root, shared, {})
        problems += expect("a shared cache that builds no access-ordered map is refused",
                           len(failures) == 1 and failures[0].startswith("SHARED_CACHE "), failures)

    real = DEFAULT_ROOT / WITNESS_EXCEPTION
    if real.is_file():
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = real.read_text(encoding="utf-8")
            write_at(root, WITNESS_EXCEPTION, text)
            failures, _ = check(root, None, {WITNESS_EXCEPTION: "no weight"})
            problems += expect("the real listed exception is accepted", failures == [], failures)
            copy = WITNESS_EXCEPTION.replace("HostedDenialThrottle.java", "CopiedThrottle.java")
            write_at(root, copy, text)
            failures, _ = check(root, None, {WITNESS_EXCEPTION: "no weight"})
            problems += expect("a copy of the real exception outside the list is refused",
                               len(failures) == 1 and failures[0].startswith(copy + ":"), failures)
    else:
        problems.append(f"missing witness file {WITNESS_EXCEPTION}")
    return problems


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--self-test", action="store_true", help="run the fixture-based self-test and exit")
    arguments = parser.parse_args(argv)
    if arguments.self_test:
        return self_test()
    failures, checked = check(arguments.root)
    if gate_cli.report_failures(LABEL, failures):
        return 1
    print(f"{LABEL} SUCCESS (bounded helpers checked={checked}, access-ordered maps only in {SHARED_CACHE} and in "
          f"the closed list below)")
    for entry, reason in sorted(ACCESS_ORDER_EXCEPTIONS.items()):
        print(f" - {entry}: {reason}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
