"""Java source reading shared by the rules of check-module-boundaries.py: package declarations,
imports, qualified names, comments and literals blanked, call arguments and production types.

This module is not a gate: check-module-boundaries.py imports it and runs its rules; its self-tests are
the test_check_module_boundaries*.py files.
"""

from __future__ import annotations

import re
from pathlib import Path

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
    match = PACKAGE.search(COMMENTS_AND_LITERALS.sub(" ", source.read_text(encoding="utf-8")))
    return match.group(1) if match else ""
