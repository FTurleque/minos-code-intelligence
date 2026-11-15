"""Shared support of the literal consistency gates: reads through an in-memory overlay, recorded facts and the
``--self-test`` driver that refuses one mutation per asserted fact (AUD-TST-09).

A literal gate separates its evaluation from its command line. The evaluation is a function without arguments
that reads files with ``read`` and asserts facts with ``require``, ``forbid``, ``require_pattern``,
``forbid_pattern``, ``require_file``, ``forbid_file`` or ``check``; it raises on the first missing fact and returns
its success message. ``main`` parses the arguments (``--self-test`` among them) and calls ``run`` or
``self_test``.

``self_test`` evaluates the gate once on the repository and records every fact it asserted, with the files whose
content the fact reads. Then, for each fact, it re-evaluates the gate on an in-memory copy of those files in which
the fact is false: a required literal or pattern removed, a forbidden literal or a witness of a forbidden pattern
inserted, a required file removed, a forbidden file created. The gate must fail and name the fact's label (the
file, most of the time; for a file fact, its path is enough, with either separator). A structured rule (``check``) has no mutation that can be derived from its arguments: the
gate declares its mutations in a table, and a rule without one is a failure. Nothing is written to disk.

The driver fails when the gate fails on the repository, when it asserts no fact, when a fact has no mutation
(its text is held by no file the gate read, its pattern matches an empty text) and when a mutation is accepted.

This module is not a gate (no ``check-`` in its name); its self-test is ``test_literal_gate.py``. A gate imports
it by adding its own directory to ``sys.path`` (``scripts/quality``) before ``import literal_gate``.
"""
from __future__ import annotations

import re
import sys
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path

Normalise = Callable[[str], str]
Mutation = tuple[str, Callable[[str], str]]

# Characters that the presentation normalisations of the gates drop or collapse: a removed literal may be spread
# over them in the file (``**fail-closed**``, a line break inside a sentence).
_GAP = "[\\s`* ﻿]*"
_IGNORED = frozenset("`* ﻿")
_MAX_PATTERN_PASSES = 100


class GateFailure(RuntimeError):
    """A fact of the gate does not hold."""


class ReportedFailure(GateFailure):
    """A failure already formatted by another gate: ``run`` prints it as is, without its own prefix."""


@dataclass(frozen=True)
class Fact:
    """One fact asserted by an evaluation: what is checked, on which label, and which files hold its text."""

    kind: str
    label: str
    value: str
    files: tuple[str, ...]
    flags: int = 0
    witness: str = ""
    text: str = field(default="", compare=False, repr=False)

    def describe(self) -> str:
        return f"{self.kind} '{self.value}' ({self.label})"


class _State:
    def __init__(self) -> None:
        self.root = Path.cwd()
        self.overlay: dict[str, str | None] = {}
        self.disk: dict[str, str | None] = {}
        self.reads: dict[str, str] = {}
        self.facts: list[Fact] = []

    def reset(self, root: Path, overlay: dict[str, str | None]) -> None:
        if root != self.root:
            self.disk = {}
        self.root = root
        self.overlay = overlay
        self.reads = {}
        self.facts = []


_STATE = _State()


def _content(relative: str) -> str | None:
    """The text of a file as the evaluation sees it: the overlay first, then the disk (cached, read once)."""
    if relative in _STATE.overlay:
        return _STATE.overlay[relative]
    if relative not in _STATE.disk:
        path = _STATE.root / relative
        _STATE.disk[relative] = path.read_text(encoding="utf-8") if path.is_file() else None
    return _STATE.disk[relative]


def _holders(text: str) -> tuple[str, ...]:
    """The files read so far whose content makes up ``text``, or else the files that contain it."""
    if not text:
        return ()
    parts = tuple(relative for relative, content in _STATE.reads.items() if content and content in text)
    if parts:
        return parts
    return tuple(relative for relative, content in _STATE.reads.items() if text in content)


def _record(kind: str, label: str, value: str, text: str = "", files: tuple[str, ...] | None = None,
            flags: int = 0, witness: str = "") -> None:
    _STATE.facts.append(Fact(kind, label, value, _holders(text) if files is None else files, flags, witness, text))


def _same(value: str) -> str:
    return value


# Primitives: they record the fact and return whether it holds, for the gates that compose their own message.

def read(relative: str, missing: str = "missing required file: {path}") -> str:
    """The text of a required file; ``missing`` is the failure message, ``{path}`` replaced by ``relative``."""
    _record("file", relative, relative, files=(relative,))
    content = _content(relative)
    if content is None:
        raise GateFailure(missing.format(path=relative))
    _STATE.reads[relative] = content
    return content


def exists(relative: str) -> bool:
    """Whether a file exists as the evaluation sees it; records no fact."""
    return _content(relative) is not None


def contains(label: str, text: str, literal: str, normalise: Normalise | None = None) -> bool:
    normalise = normalise or _same
    _record("require", label, literal, text)
    return normalise(literal) in normalise(text)


def lacks(label: str, text: str, literal: str, normalise: Normalise | None = None) -> bool:
    normalise = normalise or _same
    _record("forbid", label, literal, text)
    return normalise(literal) not in normalise(text)


def matches(label: str, text: str, pattern: str, flags: int = 0) -> bool:
    _record("pattern", label, pattern, text, flags=flags)
    return re.search(pattern, text, flags) is not None


def lacks_match(label: str, text: str, pattern: str, witness: str, flags: int = 0) -> bool:
    """Whether ``pattern`` does not match; ``witness`` is a text it matches, inserted by the self-test."""
    _record("forbid-pattern", label, pattern, text, flags=flags, witness=witness)
    return re.search(pattern, text, flags) is None


def file_present(label: str, relative: str) -> bool:
    _record("file", label, relative, files=(relative,))
    return exists(relative)


def file_absent(label: str, relative: str) -> bool:
    _record("absent", label, relative, files=(relative,))
    return not exists(relative)


def rule(name: str) -> None:
    """Record a structured rule: its mutations are declared by the gate."""
    _record("rule", name, name, files=())


# Assertions: the primitive, then a failure with the gate's own message.

def require(label: str, text: str, literal: str, message: str, normalise: Normalise | None = None) -> None:
    if not contains(label, text, literal, normalise):
        raise GateFailure(message)


def forbid(label: str, text: str, literal: str, message: str, normalise: Normalise | None = None) -> None:
    if not lacks(label, text, literal, normalise):
        raise GateFailure(message)


def require_pattern(label: str, text: str, pattern: str, message: str, flags: int = 0) -> None:
    if not matches(label, text, pattern, flags):
        raise GateFailure(message)


def forbid_pattern(label: str, text: str, pattern: str, message: str, witness: str, flags: int = 0) -> None:
    if not lacks_match(label, text, pattern, witness, flags):
        raise GateFailure(message)


def require_file(label: str, relative: str, message: str) -> None:
    if not file_present(label, relative):
        raise GateFailure(message)


def forbid_file(label: str, relative: str, message: str) -> None:
    if not file_absent(label, relative):
        raise GateFailure(message)


def check(name: str, condition: bool, message: str) -> None:
    rule(name)
    if not condition:
        raise GateFailure(message)


# Running the gate.

def _evaluate(root: Path, evaluate: Callable[[], str], overlay: dict[str, str | None]
              ) -> tuple[str | None, str | None, list[Fact]]:
    """``(success message, failure message, facts)`` of one evaluation on ``root`` seen through ``overlay``."""
    _STATE.reset(root, overlay)
    try:
        message = evaluate()
    except Exception as exception:  # a gate fails on any exception, as the gates did before this module
        return None, str(exception), list(_STATE.facts)
    return message, None, list(_STATE.facts)


def run(root: Path, evaluate: Callable[[], str], failure_prefix: str) -> int:
    """Evaluate the gate: print its success message, or ``<failure_prefix>: <failure>`` on stderr."""
    _STATE.reset(root, {})
    try:
        print(evaluate())
        return 0
    except ReportedFailure as failure:
        print(str(failure), file=sys.stderr)
    except Exception as exception:
        print(f"{failure_prefix}: {exception}", file=sys.stderr)
    return 1


def _tolerant(literal: str) -> re.Pattern[str] | None:
    """A pattern for ``literal`` that also matches its variants under case and presentation changes."""
    characters = [character for character in literal if not character.isspace() and character not in _IGNORED]
    if not characters:
        return None
    return re.compile(_GAP.join(re.escape(character) for character in characters), re.IGNORECASE)


def _insert(content: str, segment: str, inserted: str) -> str:
    """``content`` with ``inserted`` on its own line inside ``segment``, or appended when ``segment`` is not in it."""
    start = content.find(segment) if segment else -1
    if start < 0 or segment == content or content in segment:
        return f"{content}\n{inserted}\n"
    newline = content.find("\n", start, start + len(segment))
    if newline < 0:
        return f"{content[:start]}{inserted} {content[start:]}"
    return f"{content[:newline + 1]}{inserted}\n{content[newline + 1:]}"


def _derived_mutation(fact: Fact, baseline: dict[str, str]) -> dict[str, str | None] | None:
    """The overlay that makes ``fact`` false, or None when it cannot be derived."""
    if fact.kind == "file":
        return {fact.value: None}
    if fact.kind == "absent":
        return {fact.value: ""}
    holders = [relative for relative in fact.files if relative in baseline]
    if not holders:
        return None
    overlay: dict[str, str | None] = {}
    if fact.kind == "require":
        pattern = _tolerant(fact.value)
        if pattern is None:
            return None
        for relative in holders:
            mutated = pattern.sub("", baseline[relative])
            if mutated != baseline[relative]:
                overlay[relative] = mutated
        return overlay or None
    if fact.kind == "pattern":
        if re.search(fact.value, "", fact.flags):
            return None
        for relative in holders:
            mutated = baseline[relative]
            for _ in range(_MAX_PATTERN_PASSES):
                if not re.search(fact.value, mutated, fact.flags):
                    break
                mutated = re.sub(fact.value, "", mutated, flags=fact.flags)
            if mutated != baseline[relative]:
                overlay[relative] = mutated
        return overlay or None
    if fact.kind == "forbid":
        inserted = fact.value
    elif fact.kind == "forbid-pattern":
        if not fact.witness or not re.search(fact.value, fact.witness, fact.flags):
            return None
        inserted = fact.witness
    else:
        return None
    relative = holders[0]
    return {relative: _insert(baseline[relative], fact.text, inserted)}


def _names(failure: str, label: str) -> bool:
    """Whether ``failure`` names ``label``, a path being written with either separator (``Path`` on Windows)."""
    return label in failure or label.replace("/", "\\") in failure


def self_test(root: Path, evaluate: Callable[[], str], mutations: dict[str, list[Mutation]]) -> int:
    """Refuse one mutation per fact of the gate; print the count, or each problem on stderr."""
    problems: list[str] = []
    refused = 0
    message, failure, recorded = _evaluate(root, evaluate, {})
    baseline = dict(_STATE.reads)
    if failure is not None:
        problems.append(f"the gate fails on the repository: {failure}")
    elif not recorded:
        problems.append("the gate records no fact: a self-test on zero mutations proves nothing")
    facts = list(dict.fromkeys(recorded)) if failure is None else []
    rules = {fact.label for fact in facts if fact.kind == "rule"}
    for name in sorted(set(mutations) - rules) if failure is None else []:
        problems.append(f"mutations are declared for the rule '{name}', which the gate never checks")
    for fact in facts:
        if fact.kind == "rule":
            declared = mutations.get(fact.label, [])
            if not declared:
                problems.append(f"no mutation for the rule '{fact.label}': declare one in the gate's table")
            for relative, mutate in declared:
                original = baseline.get(relative, _content(relative))
                mutated = mutate(original if original is not None else "")
                if mutated == original:
                    problems.append(f"a mutation of the rule '{fact.label}' changes nothing in {relative}")
                    continue
                accepted, _failure, _ = _evaluate(root, evaluate, {relative: mutated})
                if accepted is not None:
                    problems.append(f"mutation accepted: the rule '{fact.label}' holds after {relative} changed")
                else:
                    refused += 1
            continue
        overlay = _derived_mutation(fact, baseline)
        if overlay is None:
            problems.append(f"no mutation for {fact.describe()}: no file the gate read holds its text")
            continue
        accepted, mutated_failure, _ = _evaluate(root, evaluate, overlay)
        if accepted is not None:
            problems.append(f"mutation accepted: {fact.describe()} holds after {', '.join(overlay)} changed")
        elif not (_names(mutated_failure or "", fact.label)
                  or (fact.kind in ("file", "absent") and _names(mutated_failure or "", fact.value))):
            problems.append(f"mutation of {fact.describe()} refused by a failure that does not name "
                            f"'{fact.label}': {mutated_failure}")
        else:
            refused += 1
    _STATE.reset(root, {})
    for problem in problems:
        print(f"LITERAL GATE SELF-TEST FAILED: {problem}", file=sys.stderr)
    if problems:
        return 1
    print(f"LITERAL GATE SELF-TEST SUCCESS ({refused} mutations refused for {len(facts)} facts)")
    return 0
