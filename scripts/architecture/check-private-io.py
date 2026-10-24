#!/usr/bin/env python3
"""Forbid raw file creation, writing, reading and locking in production code outside the I/O primitives.

MINOS_HOME accumulates material as confidential as the repositories it indexes. Java's defaults leave
it world-readable (Files.createDirectories, Files.write), follow links (Files.newInputStream) and
wait for a lock without end (FileChannel.lock). The four primitives that must touch the raw API to do
their job -- ConfinedFileOpener, BoundedFileLease, BoundedProperties, DurableAtomicFile -- are the one
place that decides what private, confined and bounded mean. PrivateLocalStorage creates files and
directories with private rights and is not a primitive of this gate: it is checked like any other file
and carries its raw calls as allowlist entries, so one more raw call there fails. FileTreeOperations is
checked like any other file; SharedCacheLeaseRegistry holds channels and locks per key and is bounded
by an allowlist entry.

Forbidden in src/main/java of every module except minos-intellij, outside those primitives:

  Files.createDirectories   Files.createDirectory   Files.createFile   Files.createTempFile
  Files.createTempDirectory   Files.write   Files.writeString   Files.newInputStream
  Files.newOutputStream   Files.newBufferedWriter   Files.newByteChannel   Files.copy   Files.move
                            (call, method reference or named static import)
  ProcessBuilder.redirect(file)   redirectOutput / redirectError given anything but a Redirect constant,
                            Redirect.to, Redirect.appendTo (a process output written to a file);
                            Redirect.DISCARD / PIPE / INHERIT and redirectInput are not files written
  import static java.nio.file.Files.*      (a wildcard import hides every call above)
  FileChannel.open          AsynchronousFileChannel.open
  FileChannel.lock          any use of FileChannel, AsynchronousFileChannel, FileLock, RandomAccessFile or
                            getChannel(), and lock/tryLock on a receiver named *channel*: a channel can only
                            come from the primitives, so holding one outside them is the finding, whatever
                            its variable is called. A bare import is not a use.
  message(Path)             an exception constructed (new ...Exception( / new ...Error() or a logger called by
                            level (warn, error, info, debug, ...) whose message renders a Path or a File: operand
                            of +, toString(), String.valueOf, an argument of String.format / formatted, or the
                            result of toAbsolutePath / toRealPath / getAbsolutePath / getCanonicalPath. A
                            message names the entry by its role or by its file name (getFileName(),
                            PublicErrorMessages.leafName), never by its absolute path.

The two path rules (LOGGER.log(Path) and message(Path)) also apply to the four primitives, whose messages
reach their callers, and to minos-intellij, which shows its exception messages in the IDE and writes them to
idea.log. The I/O rules apply to neither: the primitives are the raw I/O, the plugin is a client outside
MINOS_HOME.

The tests (src/test) and the resources (src/main/resources, e.g. embedded scripts) are excluded by
construction: the rule governs the code MINOS ships and runs, not its fixtures. Comments and string
literals are ignored; unicode escapes are decoded first, as the Java compiler does.

Exceptions are the nominative allowlist scripts/architecture/private-io-allowlist.json: one entry per
(file, forbidden method) with the maximum number of occurrences and a written justification. Never a
directory, never a wildcard. The list is a ratchet: an occurrence outside the list, or one more in a
listed file, fails; an entry whose maximum is above the real count fails too, so the list only shrinks.

--inventory lists every site of the two path rules with its line and its exposure: (a) public, an exception
message (it can reach the CLI, MCP, the Java API or the IDE through a caller) or a log line at WARNING or
above; (b) internal, a log line below WARNING. It reports and exits 0; the gate itself is the plain run.

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

# The primitives that must use the raw API: the only files allowed to do so without a list entry.
PRIMITIVES = frozenset({
    "minos-engine/src/main/java/com/minos/io/ConfinedFileOpener.java",
    "minos-engine/src/main/java/com/minos/io/DurableAtomicFile.java",
    "minos-engine/src/main/java/com/minos/io/BoundedFileLease.java",
    "minos-engine/src/main/java/com/minos/io/BoundedProperties.java",
})

REDIRECT_RULE = "ProcessBuilder.redirect(file)"

FORBIDDEN = (
    "Files.createDirectories",
    "Files.write",
    "Files.writeString",
    "Files.newInputStream",
    "Files.newOutputStream",
    "Files.newBufferedWriter",
    "Files.newByteChannel",
    "Files.createFile",
    "Files.createDirectory",
    "Files.createTempFile",
    "Files.createTempDirectory",
    "Files.copy",
    "Files.move",
    REDIRECT_RULE,
    "Files.*",
    "FileChannel.open",
    "AsynchronousFileChannel.open",
    "FileChannel.lock",
    "LOGGER.log(Path)",
    "LOGGER.log(cause)",
    "message(Path)",
)

# The two logging rules read the arguments of every log call (LOGGER, LOG, logger or an inline
# System.getLogger(...)). A Path concatenated into a message
# has no exception: a log line carries a path relative to the managed root, never the absolute one. A cause
# (a Throwable as last argument, or getMessage()) is what an AccessDeniedException turns into a path, so it
# is allowed only where it is deliberate, by nominative entry (the sandbox diagnostics).
LOG_PATH_RULE = "LOGGER.log(Path)"
LOG_CAUSE_RULE = "LOGGER.log(cause)"
RULES_WITHOUT_EXCEPTION = frozenset({LOG_PATH_RULE})
# The log call is recognised through the constant names the code base uses and through an inline
# System.getLogger(...).log(...), so a logger that is not called LOGGER is not a way around the rules.
_LOG_CALL = re.compile(
    r"\b(?:LOGGER|LOG|logger)\s*\.\s*log\s*\("
    r"|\bSystem\s*\.\s*getLogger\s*\((?:[^()]|\([^()]*\))*\)\s*\.\s*log\s*\(")
_PATH_DECLARATION = re.compile(r"\bPath\s+(\w+)\b")
_THROWABLE_DECLARATION = re.compile(r"\b(?:\w*Exception|Throwable|\w*Error)\s+(\w+)\b")
_BARE_IDENTIFIER = re.compile(r"\s*\w+\s*")
_GET_MESSAGE = re.compile(r"\.\s*getMessage\s*\(")

# An exception message or a log line by level may reach a public output: the CLI, MCP, the Java API, the IDE,
# or a journal at WARNING and above. It names an entry by role or by file name, never by absolute path. A site
# that is internal and needs the path for diagnosis takes a nominative entry with its justification.
MESSAGE_PATH_RULE = "message(Path)"
# The rules about what a message says: they also apply to the plugin and to the primitives.
PATH_RULES = frozenset({LOG_PATH_RULE, MESSAGE_PATH_RULE})
_EXCEPTION_CONSTRUCTION = re.compile(r"\bnew\s+([A-Z]\w*(?:Exception|Error))\s*\(")
_LEVEL_LOG_CALL = re.compile(
    r"\b(?:LOGGER|LOG|logger|log)\s*\.\s*"
    r"(warn|warning|error|severe|info|debug|trace|fine|finer|finest|config)\s*\(")
_PUBLIC_LEVELS = frozenset({"warn", "warning", "error", "severe"})
_PUBLIC_LEVEL_ARGUMENT = re.compile(r"\bLevel\s*\.\s*(?:WARNING|ERROR|SEVERE)\b")
_FILE_OR_PATH_DECLARATION = re.compile(r"\b(?:Path|File)\s+(\w+)\b")
_ABSOLUTE_CALL = r"\.\s*(?:toAbsolutePath|toRealPath|getAbsolutePath|getCanonicalPath|getAbsoluteFile)\s*\(" \
                 r"(?:[^()]|\([^()]*\))*\)"
_ABSOLUTE_RENDERED = re.compile(
    rf"\+\s*(?:[\w.\s]|\(\s*\))*?{_ABSOLUTE_CALL}(?!\s*\.)|{_ABSOLUTE_CALL}\s*\+(?!\+)")
_FORMAT_CALL = re.compile(r"\b(?:String\s*\.\s*format|formatted)\s*\(")

_FILES_METHODS = ("createDirectories", "write", "writeString", "newInputStream", "newOutputStream",
                  "newBufferedWriter", "newByteChannel", "createFile", "createDirectory", "createTempFile",
                  "createTempDirectory", "copy", "move")
_STATIC_IMPORT = re.compile(r"\bimport\s+static\s+java\s*\.\s*nio\s*\.\s*file\s*\.\s*Files\s*\.\s*(\*|\w+)\s*;")
_IMPORT = re.compile(r"^\s*import\b[^;]*;", re.MULTILINE)
_NOT_OPEN = r"(?!\s*(?:\.|::)\s*open\b)"
_PATTERNS: dict[str, tuple[re.Pattern[str], ...]] = {
    **{
        f"Files.{name}": (
            re.compile(rf"\bFiles\s*\.\s*{name}\s*\("),
            re.compile(rf"\bFiles\s*::\s*{name}\b"),
        )
        for name in _FILES_METHODS
    },
    # A process output sent to a file: Redirect.to / appendTo, or redirectOutput / redirectError given anything
    # but a Redirect constant (DISCARD, PIPE, INHERIT are not files). redirectInput reads, redirectErrorStream
    # and the no-argument getters are other names or empty calls.
    REDIRECT_RULE: (
        re.compile(r"\bRedirect\s*\.\s*(?:to|appendTo)\s*\("),
        re.compile(r"\bredirect(?:Output|Error)\s*\((?!\s*\))(?!\s*(?:ProcessBuilder\s*\.\s*)?Redirect\s*\.)"),
    ),
    "FileChannel.open": (re.compile(r"\bFileChannel\s*(?:\.|::)\s*open\b"),),
    "AsynchronousFileChannel.open": (re.compile(r"\bAsynchronousFileChannel\s*(?:\.|::)\s*open\b"),),
    # A channel can only come from the primitives: holding one, or a lock on one, is the finding whatever
    # the variable is named. FileChannel.open is counted above, not twice.
    "FileChannel.lock": (
        re.compile(r"\bFileLock\b"),
        re.compile(r"\bRandomAccessFile\b"),
        re.compile(r"\.\s*getChannel\s*\("),
        re.compile(r"\b\w*[cC]hannel\w*\s*\.\s*(?:lock|tryLock)\s*\("),
        re.compile(r"\bFileChannel\b" + _NOT_OPEN),
        re.compile(r"\bAsynchronousFileChannel\b" + _NOT_OPEN),
    ),
}
_ADVICE = {
    LOG_PATH_RULE: "log the path relative to the managed root and the exception class, never the absolute path",
    LOG_CAUSE_RULE: "log the exception class only; a cause can carry a path (add a nominative entry only for a "
                    "diagnostic where the cause is deliberate)",
    MESSAGE_PATH_RULE: "name the entry by its role or its file name (getFileName(), PublicErrorMessages.leafName), "
                       "never by its absolute path",
}
_UNICODE_ESCAPE = re.compile(r"(?<!\\)((?:\\\\)*)\\u+([0-9a-fA-F]{4})")

_MIN_JUSTIFICATION_CHARS = 20


def decode_unicode_escapes(source: str) -> str:
    """Decode unicode escapes before lexing, as the Java compiler does (an escaped backslash is not one)."""
    return _UNICODE_ESCAPE.sub(lambda match: match.group(1) + chr(int(match.group(2), 16)), source)


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


def _log_call_arguments(code: str) -> list[str]:
    """The argument text of each LOGGER.log( ... ) call, found by balancing the parentheses."""
    calls: list[str] = []
    for match in _LOG_CALL.finditer(code):
        depth, i = 1, match.end()
        while i < len(code) and depth:
            depth += {"(": 1, ")": -1}.get(code[i], 0)
            i += 1
        calls.append(code[match.end():i - 1])
    return calls


def _split_top_level(arguments: str) -> list[str]:
    parts: list[str] = []
    depth, start = 0, 0
    for i, char in enumerate(arguments):
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "," and depth == 0:
            parts.append(arguments[start:i])
            start = i + 1
    parts.append(arguments[start:])
    return parts


def _renders(arguments: str, names: set[str]) -> bool:
    """A declared name rendered as text: operand of +, toString() or String.valueOf; a method call on it is not."""
    for name in names:
        n = re.escape(name)
        if (re.search(rf"\b{n}\s*\.\s*toString\s*\(", arguments)
                or re.search(rf"\bString\s*\.\s*valueOf\s*\(\s*{n}\s*\)", arguments)
                or re.search(rf"\b{n}\s*\+(?!\+)", arguments)
                or re.search(rf"\+\s*{n}\b(?!\s*[.(\w])", arguments)):
            return True
    return False


def _passes_a_cause(arguments: str, throwable_names: set[str]) -> bool:
    """A Throwable as last argument, getMessage(), or a Throwable rendered into the text (its message with it)."""
    parts = _split_top_level(arguments)
    return (len(parts) >= 3 and _BARE_IDENTIFIER.fullmatch(parts[-1]) is not None) \
        or _GET_MESSAGE.search(arguments) is not None \
        or _renders(arguments, throwable_names)


def count_log_findings(code: str) -> dict[str, int]:
    """Log calls that render a Path into the text, and calls that pass or render a cause (or getMessage()).

    The gate does not know types: a Path (or a Throwable) is a name the file declares as `Path name` /
    `...Exception name` (parameter, local, loop or catch variable), so one reached through another call is not
    seen. Documented limit, fixed by the self-test.
    """
    # A Path whose name says it is relative (relative, relativePath, ...) is a path under a root the code already
    # confined: the message the rule asks for. The name is the only thing the gate can read.
    path_names = {name for name in _PATH_DECLARATION.findall(code) if "relative" not in name.lower()}
    throwable_names = set(_THROWABLE_DECLARATION.findall(code))
    arguments = _log_call_arguments(code)
    return {
        LOG_PATH_RULE: sum(1 for call in arguments if _renders(call, path_names)),
        LOG_CAUSE_RULE: sum(1 for call in arguments if _passes_a_cause(call, throwable_names)),
    }


def _arguments_from(code: str, start: int) -> str:
    """The text between an opening parenthesis (just before start) and its matching closing one."""
    depth, i = 1, start
    while i < len(code) and depth:
        depth += {"(": 1, ")": -1}.get(code[i], 0)
        i += 1
    return code[start:i - 1]


def _renders_path(arguments: str, names: set[str]) -> bool:
    """A Path or File rendered into a message: _renders, this.name, a format argument, or an absolute form."""
    if _renders(arguments, names) or _ABSOLUTE_RENDERED.search(arguments):
        return True
    if not names:
        return False
    alternatives = "|".join(re.escape(name) for name in sorted(names))
    if re.search(rf"\+\s*this\s*\.\s*(?:{alternatives})\b(?!\s*[.(\w])", arguments):
        return True
    bare = re.compile(rf"\s*(?:this\s*\.\s*)?(?:{alternatives})\s*")
    for match in _FORMAT_CALL.finditer(arguments):
        if any(bare.fullmatch(part) for part in _split_top_level(_arguments_from(arguments, match.end()))):
            return True
    return False


def path_rule_sites(code: str) -> list[tuple[int, str, str, str]]:
    """(line, rule, construct, exposure) of each site of the two path rules in lexed code.

    Exposure is "a" (public: an exception message, a log line at WARNING or above) or "b" (internal: a log
    line below WARNING). Like the logging rules, the gate does not know types: a Path or a File is a name the
    file declares as `Path name` / `File name`, so one reached through an accessor is not seen (documented
    limit, fixed by the self-test). A name that says it is relative is a path under a confined root.
    """
    names = {name for name in _FILE_OR_PATH_DECLARATION.findall(code) if "relative" not in name.lower()}
    log_names = {name for name in _PATH_DECLARATION.findall(code) if "relative" not in name.lower()}
    sites: list[tuple[int, str, str, str]] = []

    def line_of(index: int) -> int:
        return code.count("\n", 0, index) + 1

    for match in _EXCEPTION_CONSTRUCTION.finditer(code):
        if _renders_path(_arguments_from(code, match.end()), names):
            sites.append((line_of(match.start()), MESSAGE_PATH_RULE, f"new {match.group(1)}", "a"))
    for match in _LEVEL_LOG_CALL.finditer(code):
        if _renders_path(_arguments_from(code, match.end()), names):
            exposure = "a" if match.group(1) in _PUBLIC_LEVELS else "b"
            sites.append((line_of(match.start()), MESSAGE_PATH_RULE, f"log.{match.group(1)}", exposure))
    for match in _LOG_CALL.finditer(code):
        arguments = _arguments_from(code, match.end())
        if _renders(arguments, log_names):
            exposure = "a" if _PUBLIC_LEVEL_ARGUMENT.search(arguments) else "b"
            sites.append((line_of(match.start()), LOG_PATH_RULE, "LOGGER.log", exposure))
    return sorted(sites)


def lex(source: str) -> str:
    """Unicode escapes decoded, comments and literals blanked: the text every rule reads."""
    return strip_comments_and_literals(decode_unicode_escapes(source))


def count_occurrences(source: str, rules: frozenset[str] | tuple[str, ...] = FORBIDDEN) -> dict[str, int]:
    """Occurrences of each forbidden call in one Java source, comments and literals ignored."""
    code = lex(source)
    counts = {name: 0 for name in FORBIDDEN}
    counts.update(count_log_findings(code))
    counts[MESSAGE_PATH_RULE] = sum(1 for site in path_rule_sites(code) if site[1] == MESSAGE_PATH_RULE)
    for match in _STATIC_IMPORT.finditer(code):
        key = "Files.*" if match.group(1) == "*" else f"Files.{match.group(1)}"
        if key in counts:
            counts[key] += 1
    code = _IMPORT.sub("", code)
    for name, patterns in _PATTERNS.items():
        for pattern in patterns:
            counts[name] += len(pattern.findall(code))
    return {name: count for name, count in counts.items() if count and name in rules}


def production_sources(root: Path) -> list[Path]:
    """Every src/main/java source of every module except the excluded ones."""
    sources: list[Path] = []
    for module in sorted(root.glob("minos-*")):
        if not module.is_dir() or module.name in EXCLUDED_MODULES:
            continue
        sources.extend(sorted((module / "src" / "main" / "java").rglob("*.java")))
    return sources


def scanned_sources(root: Path) -> list[tuple[Path, frozenset[str] | tuple[str, ...]]]:
    """Each scanned source with the rules that apply to it: all of them, or the path rules alone for a
    primitive (it is the raw I/O) and for an excluded module (it is outside MINOS_HOME)."""
    scanned: list[tuple[Path, frozenset[str] | tuple[str, ...]]] = []
    for source in production_sources(root):
        relative = source.relative_to(root).as_posix()
        scanned.append((source, PATH_RULES if relative in PRIMITIVES else FORBIDDEN))
    for module in sorted(EXCLUDED_MODULES):
        for source in sorted((root / module / "src" / "main" / "java").rglob("*.java")):
            scanned.append((source, PATH_RULES))
    return scanned


def _path_rules_only(file: str) -> bool:
    return file in PRIMITIVES or file.split("/", 1)[0] in EXCLUDED_MODULES


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
        if method not in FORBIDDEN:
            raise ValueError(f"{where}: unknown forbidden method {method!r}")
        if file in PRIMITIVES and method not in PATH_RULES:
            raise ValueError(f"{where}: a primitive needs no entry: {file}")
        if _path_rules_only(file) and method not in PATH_RULES:
            raise ValueError(f"{where}: only the path rules apply to {file}")
        if method in RULES_WITHOUT_EXCEPTION:
            raise ValueError(f"{where}: {method} admits no exception, fix the log call instead")
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
    sources = scanned_sources(root)
    for source, rules in sources:
        relative = source.relative_to(root).as_posix()
        text = source.read_text(encoding="utf-8")
        counts = count_occurrences(text, rules)
        for method, count in counts.items():
            found[(relative, method)] = count
            maximum = allowed.get((relative, method), (0, ""))[0]
            if count > maximum:
                what = "not in the allowlist" if maximum == 0 else f"allowlist maximum is {maximum}"
                advice = _ADVICE.get(method, "use PrivateLocalStorage / ConfinedFileOpener / "
                                             "DurableAtomicFile / BoundedFileLease instead")
                lines = [str(site[0]) for site in path_rule_sites(lex(text)) if site[1] == method]
                where = f" at line(s) {', '.join(lines)}" if lines else ""
                violations.append(f"{relative}: {count} x {method}{where} ({what}); {advice}")
    for (file, method), (maximum, _why) in sorted(allowed.items()):
        actual = found.get((file, method), 0)
        if actual < maximum:
            violations.append(
                f"{file}: allowlist says {maximum} x {method} but there are {actual}; lower or remove the entry "
                f"(the list is a ratchet and only shrinks)")
    return violations, len(sources), sum(found.get(key, 0) for key in allowed)


def inventory(root: Path, allowlist: Path) -> list[str]:
    """One line per site of the two path rules: file:line, rule, construct, exposure, allowlisted or not."""
    allowed = load_allowlist(allowlist, root)
    lines: list[str] = []
    for source, _rules in scanned_sources(root):
        relative = source.relative_to(root).as_posix()
        for line, rule, construct, exposure in path_rule_sites(lex(source.read_text(encoding="utf-8"))):
            listed = "allowlisted" if (relative, rule) in allowed else "-"
            lines.append(f"{relative}:{line}\t{rule}\t{construct}\t({exposure})\t{listed}")
    public = sum(1 for line in lines if "\t(a)\t" in line)
    lines.append(f"TOTAL {len(lines)} site(s): {public} (a) public, {len(lines) - public} (b) internal")
    return lines


def main() -> int:
    try:
        if sys.argv[1:] == ["--inventory"]:
            print("\n".join(inventory(ROOT, ALLOWLIST)))
            return 0
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
