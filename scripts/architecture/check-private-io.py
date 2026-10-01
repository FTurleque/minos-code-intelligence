#!/usr/bin/env python3
"""Forbid raw file creation, writing, reading and locking in production code outside the I/O primitives.

MINOS_HOME accumulates material as confidential as the repositories it indexes. Java's defaults leave
it world-readable (Files.createDirectories, Files.write), follow links (Files.newInputStream) and
wait for a lock without end (FileChannel.lock). The primitives of com.minos.io -- PrivateLocalStorage,
ConfinedFileOpener, DurableAtomicFile, FileTreeOperations, BoundedFileLease, BoundedProperties,
SharedCacheLeaseRegistry -- are the one place that decides what private, confined and bounded mean.

Forbidden in src/main/java of every module except minos-intellij, outside those primitives:

  Files.createDirectories   Files.write   Files.writeString   Files.newInputStream
  FileChannel.open          FileChannel.lock / tryLock (a receiver named *channel*, or any FileLock)

The tests (src/test) and the resources (src/main/resources, e.g. embedded scripts) are excluded by
construction: the rule governs the code MINOS ships and runs, not its fixtures. Comments and string
literals are ignored.

Exceptions are the nominative allowlist scripts/architecture/private-io-allowlist.json: one entry per
(file, forbidden method) with the maximum number of occurrences and a written justification. Never a
directory, never a wildcard. The list is a ratchet: an occurrence outside the list, or one more in a
listed file, fails; an entry whose maximum is above the real count fails too, so the list only shrinks.

Self-test: scripts/architecture/test_check_private_io.py.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ALLOWLIST = Path(__file__).resolve().parent / "private-io-allowlist.json"

EXCLUDED_MODULES = frozenset({"minos-intellij"})

# The primitives themselves: the only files allowed to use the forbidden calls without a list entry.
PRIMITIVES = frozenset({
    "minos-engine/src/main/java/com/minos/io/PrivateLocalStorage.java",
    "minos-engine/src/main/java/com/minos/io/ConfinedFileOpener.java",
    "minos-engine/src/main/java/com/minos/io/DurableAtomicFile.java",
    "minos-engine/src/main/java/com/minos/io/FileTreeOperations.java",
    "minos-engine/src/main/java/com/minos/io/BoundedFileLease.java",
    "minos-engine/src/main/java/com/minos/io/BoundedProperties.java",
    "minos-engine/src/main/java/com/minos/io/SharedCacheLeaseRegistry.java",
})

FORBIDDEN = (
    "Files.createDirectories",
    "Files.write",
    "Files.writeString",
    "Files.newInputStream",
    "FileChannel.open",
    "FileChannel.lock",
)

_STATIC_IMPORT = re.compile(
    r"\bimport\s+static\s+java\.nio\.file\.Files\.(createDirectories|writeString|write|newInputStream)\b")
_PATTERNS: dict[str, tuple[re.Pattern[str], ...]] = {
    "Files.createDirectories": (re.compile(r"\bFiles\s*\.\s*createDirectories\s*\("),),
    "Files.write": (re.compile(r"\bFiles\s*\.\s*write\s*\("),),
    "Files.writeString": (re.compile(r"\bFiles\s*\.\s*writeString\s*\("),),
    "Files.newInputStream": (re.compile(r"\bFiles\s*\.\s*newInputStream\s*\("),),
    "FileChannel.open": (re.compile(r"\bFileChannel\s*\.\s*open\s*\("),),
    # A receiver named after a channel, or the FileLock type that every stored lock mentions.
    "FileChannel.lock": (
        re.compile(r"\b\w*[cC]hannel\w*\s*\.\s*(?:lock|tryLock)\s*\("),
        re.compile(r"\bFileLock\b"),
    ),
}
_STATIC_IMPORT_KEY = {
    "createDirectories": "Files.createDirectories",
    "write": "Files.write",
    "writeString": "Files.writeString",
    "newInputStream": "Files.newInputStream",
}

_MIN_JUSTIFICATION_CHARS = 20


def strip_comments_and_literals(source: str) -> str:
    """Blank out comments, string/char literals and text blocks, keeping the line structure."""
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if c == "/" and nxt == "/":
            while i < n and source[i] != "\n":
                i += 1
        elif c == "/" and nxt == "*":
            i += 2
            while i < n and not (source[i] == "*" and i + 1 < n and source[i + 1] == "/"):
                out.append("\n" if source[i] == "\n" else " ")
                i += 1
            i += 2
        elif source.startswith('"""', i):
            i += 3
            while i < n and not source.startswith('"""', i):
                if source[i] == "\\":
                    i += 1
                out.append("\n" if i < n and source[i] == "\n" else " ")
                i += 1
            i += 3
        elif c == '"' or c == "'":
            quote = c
            i += 1
            while i < n and source[i] != quote and source[i] != "\n":
                if source[i] == "\\":
                    i += 1
                i += 1
            i += 1
            out.append(quote + quote)
        else:
            out.append(c)
            i += 1
    return "".join(out)


def count_occurrences(source: str) -> dict[str, int]:
    """Occurrences of each forbidden call in one Java source, comments and literals ignored."""
    code = strip_comments_and_literals(source)
    counts = {name: 0 for name in FORBIDDEN}
    for name, patterns in _PATTERNS.items():
        for pattern in patterns:
            counts[name] += len(pattern.findall(code))
    for match in _STATIC_IMPORT.finditer(code):
        counts[_STATIC_IMPORT_KEY[match.group(1)]] += 1
    return {name: count for name, count in counts.items() if count}


def production_sources(root: Path) -> list[Path]:
    """Every src/main/java source of every module except the excluded ones."""
    sources: list[Path] = []
    for module in sorted(root.glob("minos-*")):
        if not module.is_dir() or module.name in EXCLUDED_MODULES:
            continue
        sources.extend(sorted((module / "src" / "main" / "java").rglob("*.java")))
    return sources


def load_allowlist(path: Path, root: Path) -> dict[tuple[str, str], tuple[int, str]]:
    """The nominative allowlist, validated: no directory, no wildcard, no blank justification."""
    if not path.is_file():
        return {}
    entries = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(entries, list):
        raise ValueError("the allowlist must be a JSON array of entries")
    allowed: dict[tuple[str, str], tuple[int, str]] = {}
    for position, entry in enumerate(entries):
        where = f"allowlist entry {position}"
        if not isinstance(entry, dict) or set(entry) != {"file", "method", "max", "justification"}:
            raise ValueError(f"{where}: exactly file, method, max and justification are required")
        file, method, maximum, why = entry["file"], entry["method"], entry["max"], entry["justification"]
        if not isinstance(file, str) or any(ch in file for ch in "*?[]{}") or file.endswith("/"):
            raise ValueError(f"{where}: file must be one nominative path, never a directory or a wildcard")
        if not file.endswith(".java") or "/src/main/java/" not in file:
            raise ValueError(f"{where}: file must be a src/main/java source: {file}")
        if not (root / file).is_file():
            raise ValueError(f"{where}: no such file: {file}")
        if file in PRIMITIVES:
            raise ValueError(f"{where}: a primitive needs no entry: {file}")
        if method not in FORBIDDEN:
            raise ValueError(f"{where}: unknown forbidden method {method!r}")
        if not isinstance(maximum, int) or isinstance(maximum, bool) or maximum < 1:
            raise ValueError(f"{where}: max must be a positive integer")
        if not isinstance(why, str) or len(why.strip()) < _MIN_JUSTIFICATION_CHARS:
            raise ValueError(f"{where}: a written justification is required")
        if (file, method) in allowed:
            raise ValueError(f"{where}: duplicate entry for {file} / {method}")
        allowed[(file, method)] = (maximum, why.strip())
    return allowed


def check(root: Path, allowlist: Path) -> tuple[list[str], int, int]:
    """Returns (violations, files scanned, allowlisted occurrences)."""
    allowed = load_allowlist(allowlist, root)
    violations: list[str] = []
    found: dict[tuple[str, str], int] = {}
    sources = production_sources(root)
    for source in sources:
        relative = source.relative_to(root).as_posix()
        if relative in PRIMITIVES:
            continue
        counts = count_occurrences(source.read_text(encoding="utf-8"))
        for method, count in counts.items():
            found[(relative, method)] = count
            maximum = allowed.get((relative, method), (0, ""))[0]
            if count > maximum:
                what = "not in the allowlist" if maximum == 0 else f"allowlist maximum is {maximum}"
                violations.append(
                    f"{relative}: {count} x {method} ({what}); use PrivateLocalStorage / ConfinedFileOpener / "
                    f"DurableAtomicFile / BoundedFileLease instead")
    for (file, method), (maximum, _why) in sorted(allowed.items()):
        actual = found.get((file, method), 0)
        if actual < maximum:
            violations.append(
                f"{file}: allowlist says {maximum} x {method} but there are {actual}; lower or remove the entry "
                f"(the list is a ratchet and only shrinks)")
    return violations, len(sources), sum(found.get(key, 0) for key in allowed)


def main() -> int:
    try:
        if sys.argv[1:]:
            raise ValueError(f"unknown arguments: {', '.join(sys.argv[1:])}")
        violations, scanned, listed = check(ROOT, ALLOWLIST)
        if violations:
            for violation in violations:
                print(f"  - {violation}", file=sys.stderr)
            raise ValueError(f"{len(violations)} violation(s)")
        print(f"PRIVATE I/O PRIMITIVES GATE SUCCESS (sources={scanned}, allowlisted-occurrences={listed}, "
              f"forbidden={len(FORBIDDEN)}, primitives={len(PRIMITIVES)})")
        return 0
    except Exception as exception:
        print(f"PRIVATE I/O PRIMITIVES GATE FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
