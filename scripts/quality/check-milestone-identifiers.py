#!/usr/bin/env python3
"""Fail when a production name carries a milestone number instead of saying what it does.

Policy: docs/architecture/adr/GEN-0043-retrait-des-artefacts-de-jalon.md, extended from scripts to production
names. A name such as `qualifiedM24Providers` or `MinosM21Actions` tells which milestone introduced it, not what
it guarantees: a reader has to know the milestone history to pick the right method.

A milestone segment is `M` or `m` followed by one or more digits, at the start of a name segment (start of the
word, after `_` or `$`, or an upper-case `M` after a lower-case letter or a digit), and followed by the end of the
word, `_`, `$` or an upper-case letter. `arm64`, `ARM64`, `MACOS_ARM64`, `System32` and `windowsSystem32` are not
milestone segments.

Read, under every `minos-*/src/main` tree:

1. each path component (file and directory names);
2. Java and Kotlin identifiers, outside string, text-block and character literals and comments (one lexical pass);
3. class references of XML descriptors (`class=`, `implementation=`, `serviceImplementation=`...);
4. class names listed in `META-INF/services` files;
5. in PowerShell scripts and fragments (`.ps1`, `.ps1frag`, `.ps1.template`), every `MINOS_*` name, literals
   included: these are the environment variables a script sets or reads, embedded C# included.

String literals and comments of Java and Kotlin are not read: public messages and descriptions that quote a
milestone are versioned output, not names.

A name found here fails unless KNOWN_MILESTONE_IDENTIFIERS lists it for that file with a written reason. The list
only shrinks: an entry whose file is missing, or whose name no longer appears in that file, fails; so does an
entry without a reason, and so does a file that cannot be read as UTF-8 (fail-closed). Each failure names the
file, the first line and the name. On success the gate prints how many files it read and how many entries the
list still holds.

What this gate does not prove: that a name says what the code does, nor anything about test sources, fixtures,
scripts outside `src/main`, JaCoCo scope names or gate output, which are data cited elsewhere.

`--root` points the gate at another tree (default: this repository). `--self-test` runs the gate on temporary
trees: every refusal and every acceptance below has its own fixture.
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
LABEL = "MILESTONE IDENTIFIERS GATE"
# Nothing under src/main is build output: no directory is skipped (a package named `build` must be read).
SKIPPED_DIRECTORIES: frozenset[str] = frozenset()

# Closed, shrinking list: "<repository-relative file>::<name>" -> reason the name keeps its milestone for now.
KNOWN_MILESTONE_IDENTIFIERS: dict[str, str] = {}

# Start of a segment: no letter or digit before (start, `_`, `$`, punctuation), or an upper-case M after a lower-case
# letter or a digit. End of a segment: anything but a lower-case letter (the digits are consumed greedily).
MILESTONE_SEGMENT = re.compile(r"(?:(?<![A-Za-z0-9])|(?<=[a-z0-9])(?=M))[Mm][0-9]+(?![a-z])")
# `$` separates names (nested classes in a class= reference), so it is not part of a token.
NAME_TOKEN = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
COMMENTS_AND_LITERALS = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"\"\".*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'", re.DOTALL)
CLASS_ATTRIBUTE = re.compile(
    r"\b(?:class|implementation|implementationClass|serviceImplementation|serviceInterface|factoryClass|instance)"
    r"\s*=\s*\"([^\"]*)\"")
ENVIRONMENT_NAME = re.compile(r"\bMINOS_[A-Za-z0-9_]+")
CODE_SUFFIXES = (".java", ".kt")
POWERSHELL_SUFFIXES = (".ps1", ".ps1frag", ".ps1.template")


def has_milestone(name: str) -> bool:
    """True when one segment of `name` is a milestone number."""
    return MILESTONE_SEGMENT.search(name) is not None


def blank_comments_and_literals(code: str) -> str:
    """Blanks comments and literals but keeps every newline, so that line numbers stay exact."""
    return COMMENTS_AND_LITERALS.sub(lambda match: re.sub(r"[^\n]", " ", match.group(0)), code)


def line_of(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def names_in(relative_to_main: str, text: str) -> list[tuple[str, int]]:
    """(name, line) of every name of one production file that the gate reads, path components first (line 0)."""
    found: list[tuple[str, int]] = []
    for component in relative_to_main.split("/"):
        found.extend((token, 0) for token in NAME_TOKEN.findall(component.replace("-", " ").replace(".", " ")))
    name = relative_to_main.rsplit("/", 1)[-1]
    if name.endswith(CODE_SUFFIXES):
        code = blank_comments_and_literals(text)
        found.extend((match.group(0), line_of(code, match.start())) for match in NAME_TOKEN.finditer(code))
    elif name.endswith(".xml"):
        for match in CLASS_ATTRIBUTE.finditer(text):
            found.extend((token, line_of(text, match.start(1))) for token in NAME_TOKEN.findall(match.group(1)))
    elif "/META-INF/services/" in "/" + relative_to_main:
        for number, line in enumerate(text.splitlines(), start=1):
            entry = line.split("#", 1)[0]
            found.extend((token, number) for token in NAME_TOKEN.findall(entry))
    elif name.endswith(POWERSHELL_SUFFIXES):
        found.extend((match.group(0), line_of(text, match.start())) for match in ENVIRONMENT_NAME.finditer(text))
    return found


def production_files(root: Path) -> list[tuple[Path, Path]]:
    """(file, its src/main directory) for every `minos-*` module under `root`, sorted."""
    files: list[tuple[Path, Path]] = []
    for module in sorted(path for path in root.glob("minos-*") if path.is_dir()):
        main = module / "src" / "main"
        if main.is_dir():
            files.extend((path, main) for path in gate_cli.repository_files(main, SKIPPED_DIRECTORIES))
    return files


def check(root: Path, known: dict[str, str] | None = None) -> tuple[list[str], int, int]:
    """(failures, files read, list entries) for the tree under `root`."""
    known = KNOWN_MILESTONE_IDENTIFIERS if known is None else known
    failures: list[str] = [f"KNOWN_MILESTONE_IDENTIFIERS entry {entry} needs a written reason"
                           for entry, reason in sorted(known.items()) if not reason.strip()]
    seen: set[str] = set()
    files = production_files(root)
    for path, main in files:
        relative = path.relative_to(root).as_posix()
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeError) as error:
            failures.append(f"{relative} cannot be read as UTF-8 ({type(error).__name__}); the gate does not skip it")
            continue
        first_line: dict[str, int] = {}
        occurrences: dict[str, int] = {}
        for name, line in names_in(path.relative_to(main).as_posix(), text):
            if has_milestone(name):
                first_line.setdefault(name, line)
                occurrences[name] = occurrences.get(name, 0) + 1
        for name, line in first_line.items():
            entry = f"{relative}::{name}"
            if entry in known:
                seen.add(entry)
                continue
            where = f"{relative}:{line}" if line else f"{relative} (file name)"
            failures.append(f"{where} names {name} ({occurrences[name]} occurrence(s) in the file), which carries a "
                            f"milestone number; name it after what it does")
    for entry in sorted(set(known) - seen):
        file_part = entry.split("::", 1)[0]
        why = "names a file that does not exist" if not (root / file_part).is_file() else "no longer matches anything"
        failures.append(f"KNOWN_MILESTONE_IDENTIFIERS entry {entry} {why}; remove it (the list only shrinks)")
    return failures, len(files), len(known)


# --- self-test -------------------------------------------------------------------------------------------------

JAVA_MAIN = "minos-sample/src/main/java/com/example"


def write(root: Path, relative: str, content: str | bytes) -> str:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    # Bytes as given: a text-mode write on Windows would double the carriage returns of a CRLF fixture.
    path.write_bytes(content if isinstance(content, bytes) else content.encode("utf-8"))
    return relative


def run_case(files: dict[str, str | bytes], known: dict[str, str] | None = None) -> list[str]:
    with tempfile.TemporaryDirectory() as directory:
        root = Path(directory)
        for relative, content in files.items():
            write(root, relative, content)
        failures, _, _ = check(root, known or {})
        return failures


def expect(label: str, condition: bool, detail: object) -> list[str]:
    return [] if condition else [f"{label}: {detail}"]


def single(failures: list[str], *fragments: str) -> bool:
    return len(failures) == 1 and all(fragment in failures[0] for fragment in fragments)


CATALOG = """package com.example;

final class Catalog {
    static int qualifiedProviders() {
        return 1;
    }

    static int qualifiedM31Providers() {
        return 2;
    }
}
"""
SINGLE_DIGIT = "package com.example;\n\nfinal class Registry {\n    static void registerQualifiedM1() {\n    }\n}\n"
ACCEPTED = """package com.example;

final class Platforms {
    // qualifiedM24Providers in a comment is not a name.
    /* MinosM21Actions in a block comment is not a name either. */
    static final String LIMITATION = "not claimed by M24";
    static final String BLOCK = \"\"\"
            M17 Kotlin fixture
            \"\"\";
    static final char M = 'M';
    static final String MACOS_ARM64 = "macos-arm64";
    static boolean arm64;
    static String windowsSystem32;
    static String System32;
    static int ARM64;
}
"""
RUNNER = "$saved = $env:MINOS_SCIP_JAVA_MAVEN_CMD\r\n$env:MINOS_M14_MAVEN_CMD = $maven\r\n"
PLUGIN_XML = """<idea-plugin>
  <actions>
    <action id="Minos.ProgramGraph" class="com.example.actions.MinosM21Actions$ProgramGraph" text="Program graph"/>
    <action id="Minos.M21Label" class="com.example.actions.EditorActions$Open" text="M21 label"/>
  </actions>
</idea-plugin>
"""


def self_test() -> int:
    problems: list[str] = []

    failures = run_case({f"{JAVA_MAIN}/Catalog.java": CATALOG})
    problems += expect("a method with a milestone is refused with file, line and name",
                       single(failures, f"{JAVA_MAIN}/Catalog.java:8 ", "qualifiedM31Providers"), failures)

    failures = run_case({f"{JAVA_MAIN}/Registry.java": SINGLE_DIGIT})
    problems += expect("a one-digit milestone is refused", single(failures, ":4 ", "registerQualifiedM1"), failures)

    failures = run_case({f"{JAVA_MAIN}/MinosM21Actions.java": "package com.example;\n\nfinal class Actions {\n}\n"})
    problems += expect("a file named after a milestone is refused",
                       single(failures, "MinosM21Actions.java (file name)", "names MinosM21Actions"), failures)

    failures = run_case({"minos-sample/src/main/resources/runner.ps1": RUNNER})
    problems += expect("a MINOS_M14_* variable of a PowerShell script is refused with its line",
                       single(failures, "runner.ps1:2 ", "MINOS_M14_MAVEN_CMD"), failures)

    failures = run_case({"minos-sample/src/main/resources/fragment.ps1frag": RUNNER.replace("M14", "M7")})
    problems += expect("a PowerShell fragment is read too", single(failures, "MINOS_M7_MAVEN_CMD"), failures)

    failures = run_case({"minos-sample/src/main/resources/META-INF/plugin.xml": PLUGIN_XML})
    problems += expect("a class= reference is refused, an action id and a label are not read",
                       single(failures, "plugin.xml:3 ", "MinosM21Actions"), failures)

    failures = run_case({"minos-sample/src/main/resources/META-INF/services/com.example.Port":
                         "# com.example.M9Legacy in a comment\ncom.example.adapters.M9Adapter\n"})
    problems += expect("a service registration is refused", single(failures, ":2 ", "M9Adapter"), failures)

    failures = run_case({f"{JAVA_MAIN}/Platforms.java": ACCEPTED,
                         "minos-sample/src/main/resources/arm64-runner.ps1": "$env:MINOS_SCIP_JAVA_BASH_EXE = $b\r\n",
                         "minos-sample/src/main/resources/kernel32-imports.ps1frag": "# kernel32\r\n"})
    problems += expect("arm64, MACOS_ARM64, windowsSystem32, literals and comments are accepted", failures == [],
                       failures)

    failures = run_case({"tools/src/main/java/com/example/Catalog.java": CATALOG,
                         "minos-sample/src/test/java/com/example/M24CatalogTest.java": CATALOG})
    problems += expect("only src/main of minos-* modules is read", failures == [], failures)

    listed = f"{JAVA_MAIN}/Catalog.java::qualifiedM31Providers"
    failures = run_case({f"{JAVA_MAIN}/Catalog.java": CATALOG}, {listed: "kept until the caller moves"})
    problems += expect("a listed name with its reason is accepted", failures == [], failures)

    failures = run_case({f"{JAVA_MAIN}/Catalog.java": CATALOG.replace("qualifiedM31Providers", "nextProviders")},
                        {listed: "kept until the caller moves"})
    problems += expect("a stale entry is refused", single(failures, listed, "no longer matches anything"), failures)

    failures = run_case({f"{JAVA_MAIN}/Other.java": "package com.example;\n"},
                        {f"{JAVA_MAIN}/Gone.java::qualifiedM31Providers": "kept"})
    problems += expect("an entry naming a missing file is refused",
                       single(failures, "Gone.java::qualifiedM31Providers", "does not exist"), failures)

    failures = run_case({f"{JAVA_MAIN}/Catalog.java": CATALOG}, {listed: "  "})
    problems += expect("an entry without a reason is refused",
                       len(failures) == 1 and "needs a written reason" in failures[0], failures)

    failures = run_case({f"{JAVA_MAIN}/Broken.java": b"package com.example;\nclass Broken { \xff\xfe }\n"})
    problems += expect("a file that cannot be read as UTF-8 is refused",
                       single(failures, "Broken.java cannot be read"), failures)

    if gate_cli.report_failures(LABEL + " SELF-TEST", problems):
        return 1
    print(f"{LABEL} SELF-TEST SUCCESS (methods, one-digit milestones, file names, PowerShell variables, class= "
          f"references, service files, technical acronyms, literals and comments, stale, missing, unreasoned and "
          f"unreadable)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--self-test", action="store_true")
    arguments = parser.parse_args(argv)
    if arguments.self_test:
        return self_test()
    failures, files, entries = check(arguments.root)
    if gate_cli.report_failures(LABEL, failures):
        return 1
    print(f"{LABEL} SUCCESS (production files read={files}, milestone names left in the list={entries})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
