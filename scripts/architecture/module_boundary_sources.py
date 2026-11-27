"""Java source reading shared by the rules of check-module-boundaries.py: package declarations,
imports, qualified names, comments and literals blanked, call arguments and production types.

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import re
from pathlib import Path

from java_escapes import decode_unicode_escapes
from module_boundary_policy import fail


PACKAGE = re.compile(r"^\s*package\s+([A-Za-z_][\w.]*)\s*;", re.MULTILINE)


# Java does not tie an import to the start of a line. `import` is a reserved word and every reader blanks comments and
# literals before applying this pattern, so the word alone opens a declaration.
SOURCE_IMPORT = re.compile(r"(?<![\w$])import\s+(static\s+)?([\w.]+?)(\.\*)?\s*;")


def qualified_name_pattern(group_id: str) -> re.Pattern[str]:
    """A fully qualified class name under the reactor groupId (read from the root POM, never written here)."""
    return re.compile(r"\b" + re.escape(group_id) + r"(?:\.[a-z_]\w*)+\.[A-Z]\w*")


SOURCE_WORD = re.compile(r"\b[A-Z]\w*\b")
COMMENTS_AND_LITERALS = re.compile(
    r"//[^\n]*|/\*.*?\*/|\"\"\"(?:\\.|[^\\])*?\"\"\"|\"(?:\\.|[^\"\\\n])*\"|'(?:\\.|[^'\\\n])*'", re.DOTALL)
# Java accepts blanks, line breaks and comments around the dots of a name, the `.*` of a wildcard included. The three
# dots of a variable arity parameter are never joined.
QUALIFIER_GAP = re.compile(r"(?<=[\w$])\s*\.\s*(?=[\w$*])")


# `@interface` declares a type: it is no annotation. Both patterns read code whose literals are gone, so every
# parenthesis of the arguments counts.
ANNOTATION_NAME = re.compile(r"@(?!\s*interface\b)\s*[\w$]+(?:\s*\.\s*[\w$]+)*")
ANNOTATION_ARGUMENTS = re.compile(r"\s*\(")
# The dot of a qualified name followed by a type annotation (`a.b.@Nullable C`).
ANNOTATED_PART = re.compile(r"(?<=[\w$])\s*\.\s*(?=@)")
BLANKS = re.compile(r"\s*")


def read_java(source: Path) -> str:
    """The text of a Java source as the compiler reads it, unicode escapes decoded: the reading of every rule."""
    return decode_unicode_escapes(source.read_text(encoding="utf-8"))


def annotation_end(code: str, annotation: re.Match[str], label: str) -> int:
    """The index after the annotation matched by ANNOTATION_NAME, its arguments included; unbalanced arguments fail the
    guard with the given label."""
    arguments = ANNOTATION_ARGUMENTS.match(code, annotation.end())
    if not arguments:
        return annotation.end()
    depth = 0
    for index in range(arguments.end() - 1, len(code)):
        depth += {"(": 1, ")": -1}.get(code[index], 0)
        if depth == 0:
            return index + 1
    fail(label)
    return len(code)


def annotations_moved_out_of_names(code: str, file_name: str) -> str:
    """code with each annotation written between the parts of a qualified name moved to the end of the text: the name
    reads in one piece, and the type of the annotation and its arguments are still read. Unbalanced arguments fail the
    guard, naming the file."""
    kept: list[str] = []
    moved: list[str] = []
    position = 0
    for part in ANNOTATED_PART.finditer(code):
        if part.start() < position:  # inside the arguments of the previous annotation
            continue
        end = part.end()
        while annotation := ANNOTATION_NAME.match(code, end):
            after = annotation_end(code, annotation, f"unbalanced annotation inside a qualified name in {file_name}")
            moved.append(annotations_moved_out_of_names(code[end:after], file_name))
            end = BLANKS.match(code, after).end()
        kept += [code[position:part.start()], "."]
        position = end
    return "".join(kept) + code[position:] + "".join("\n" + annotation for annotation in moved)


def name_reading_code(text: str, source: Path) -> str:
    """The code a reader of names works on, from the text read_java gives for source: comments and literals blanked,
    the blanks around each dot between two words dropped and the annotations moved out of the names, which joins the
    parts of every name. Line numbers are lost: a rule that reports them reads blank_comments_and_literals."""
    joined = QUALIFIER_GAP.sub(".", COMMENTS_AND_LITERALS.sub(" ", text))
    return annotations_moved_out_of_names(joined, source.name)


def resolve_adapter_class(name: str, by_fqn: dict[str, str]) -> str | None:
    parts = name.split(".")
    for size in range(len(parts), 0, -1):
        candidate = ".".join(parts[:size])
        if candidate in by_fqn:
            return candidate
    return None


def production_sources(root: Path, modules) -> list[tuple[str, Path]]:
    """The production Java sources of the given modules, as (module, source) pairs in a stable order."""
    sources: list[tuple[str, Path]] = []
    for module in modules:
        source_root = root / module / "src" / "main" / "java"
        if source_root.is_dir():
            sources.extend((module, source) for source in sorted(source_root.rglob("*.java")))
    return sources


def production_types(root: Path, module: str) -> dict[str, str]:
    """Top-level production types of one module: qualified name -> package."""
    source_root = root / module / "src" / "main" / "java"
    if not source_root.is_dir():
        return {}
    return {".".join(source.relative_to(source_root).with_suffix("").parts):
            ".".join(source.relative_to(source_root).parts[:-1]) for source in sorted(source_root.rglob("*.java"))}


def literals_as_tokens(text: str) -> str:
    """Blanks comments and replaces each string or character literal by one token, so that a literal argument still
    counts as an argument."""
    return COMMENTS_AND_LITERALS.sub(lambda match: " " if match.group(0).startswith("/") else "0", text)


def blank_comments_and_literals(code: str) -> str:
    """Blanks comments and string literals but keeps every newline, so that line numbers stay exact."""
    return COMMENTS_AND_LITERALS.sub(lambda match: re.sub(r"[^\n]", " ", match.group(0)), code)


def call_argument_count(code: str, open_parenthesis: int, label: str = "A16 unbalanced ServiceLoader.load call") -> int:
    """Top-level arguments of the call whose opening parenthesis is at the given index (0 for an empty call); an
    unbalanced call fails the guard with the given label."""
    depth = 0
    commas = 0
    empty = True
    for character in code[open_parenthesis + 1:]:
        if character in "([{":
            depth += 1
        elif character in ")]}":
            if depth == 0:
                return 0 if empty else commas + 1
            depth -= 1
        elif character == "," and depth == 0:
            commas += 1
        if not character.isspace():
            empty = False
    fail(label)
    return 0


def declared_package(source: Path) -> str:
    match = PACKAGE.search(COMMENTS_AND_LITERALS.sub(" ", read_java(source)))
    return match.group(1) if match else ""
