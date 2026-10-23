#!/usr/bin/env python3
"""Fail if a versioned script under scripts/ has no live reference anywhere.

Policy: docs/architecture/adr/GEN-0043-retrait-des-artefacts-de-jalon.md. A script counts as
referenced when its repository-relative path (forward- or backslash form)
appears as a substring in some OTHER tracked file: a GitHub Actions workflow
(direct invocation or path filter), another script (direct execution or a
static read()/Get-Content assertion), or documentation. scripts/history/**
is the explicit list of assumed archives and is exempt by construction: a
file only needs to be moved there to leave this gate's scope.

This gate does not distinguish "executed" from "merely asserted present" -
see the ADR for why that distinction matters for classification, but for
this guard both count as a live reference: either one means removing the
file would break something that runs today.
"""
from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import gate_cli  # noqa: E402 - after the script's directory is added to the path

ROOT = Path(__file__).resolve().parents[2]

SCRIPT_SUFFIXES = {".py", ".ps1", ".sh", ".java", ".json"}
HAYSTACK_GLOBS = ("*.yml", "*.yaml", "*.py", "*.ps1", "*.sh", "*.md")
# Binary or generated content that can legitimately mention a script path
# without being a place a human or CI would look for wiring.
EXCLUDED_DIR_NAMES = {"history", "__pycache__", ".git"}


def candidate_scripts(scripts_dir: Path) -> list[Path]:
    candidates: list[Path] = []
    for path in sorted(scripts_dir.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix not in SCRIPT_SUFFIXES:
            continue
        if any(part in EXCLUDED_DIR_NAMES for part in path.relative_to(scripts_dir).parts):
            continue
        candidates.append(path)
    return candidates


HAYSTACK_SUFFIXES = {".py", ".ps1", ".sh", ".md", ".yml", ".yaml", ".java", ".template", ".iss", ".xml"}
HAYSTACK_EXCLUDED_DIR_NAMES = EXCLUDED_DIR_NAMES | {"target", "node_modules", "build"}


def haystack_files(root: Path) -> list[Path]:
    # Only the files of this repository: a reference in a worktree under .claude/worktrees/ (git-ignored) or in any
    # nested checkout is not a live reference.
    return [path for path in gate_cli.repository_files(root, HAYSTACK_EXCLUDED_DIR_NAMES)
            if path.is_file() and path.suffix in HAYSTACK_SUFFIXES]


def _unique_names(candidates: list[Path]) -> tuple[set[str], set[str]]:
    """Filenames and .py module stems that identify exactly one candidate script."""
    names: dict[str, int] = {}
    stems: dict[str, int] = {}
    for candidate in candidates:
        names[candidate.name] = names.get(candidate.name, 0) + 1
        if candidate.suffix == ".py":
            stems[candidate.stem] = stems.get(candidate.stem, 0) + 1
    unique_names = {name for name, count in names.items() if count == 1}
    unique_stems = {stem for stem, count in stems.items() if count == 1}
    return unique_names, unique_stems


def find_orphans(root: Path) -> list[str]:
    scripts_dir = root / "scripts"
    if not scripts_dir.is_dir():
        return []
    candidates = candidate_scripts(scripts_dir)
    unique_names, unique_stems = _unique_names(candidates)

    haystack_text: dict[Path, str] = {}
    for path in haystack_files(root):
        try:
            haystack_text[path] = path.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue

    orphans: list[str] = []
    for candidate in candidates:
        relative = candidate.relative_to(root)
        forward = relative.as_posix()
        backward = str(relative).replace("/", "\\")
        # A same-directory PowerShell caller (`& "$PSScriptRoot\name.ps1"`), an Inno Setup
        # template staging a flattened install tree, or a Python `from <module> import ...`
        # never spell out the repository-relative path - only the bare filename or, for a
        # Python module, its stem without extension. That fallback is only trustworthy when
        # the name uniquely identifies this one script among all live candidates; scripts that
        # share a filename across milestones (run-final.ps1, run-final.sh, ...) must still be
        # found by their full path, exactly like every genuine caller in this repo does today.
        needles = [forward, backward]
        if candidate.name in unique_names:
            needles.append(candidate.name)
        if candidate.suffix == ".py" and candidate.stem in unique_stems:
            needles.append(candidate.stem)
        referenced = any(
            path != candidate and any(needle in text for needle in needles)
            for path, text in haystack_text.items()
        )
        if not referenced:
            orphans.append(forward)
    return orphans


def self_test() -> int:
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        (root / "scripts" / "quality").mkdir(parents=True)
        (root / "scripts" / "history" / "m0").mkdir(parents=True)
        (root / ".github" / "workflows").mkdir(parents=True)
        (root / "docs").mkdir(parents=True)

        referenced = root / "scripts" / "quality" / "check-referenced.py"
        referenced.write_text("print('referenced')\n", encoding="utf-8")

        orphan = root / "scripts" / "quality" / "check-orphan.py"
        orphan.write_text("print('orphan')\n", encoding="utf-8")

        archived = root / "scripts" / "history" / "m0" / "check-archived.py"
        archived.write_text("print('archived, no reference needed')\n", encoding="utf-8")

        (root / ".github" / "workflows" / "sample.yml").write_text(
            "on: [pull_request]\njobs:\n  x:\n    steps:\n"
            "      - run: python scripts/quality/check-referenced.py\n",
            encoding="utf-8",
        )

        # The root may itself be a worktree (a `.git` file), and .claude/ holds tracked scripts next to worktrees/:
        # a reference written there is a live one.
        (root / ".git").write_text("gitdir: /somewhere/.git/worktrees/root\n", encoding="utf-8")
        (root / "scripts" / "quality" / "check-referenced-from-claude.py").write_text("print('referenced')\n", encoding="utf-8")
        (root / ".claude" / "scripts").mkdir(parents=True)
        (root / ".claude" / "scripts" / "run.py").write_text(
            "GATES = ['scripts/quality/check-referenced-from-claude.py']\n", encoding="utf-8")

        # A developer worktree (.claude/worktrees/, git-ignored) or any nested checkout is another tree: a
        # reference written there must not make an orphan of this repository look referenced.
        for nested in (".claude/worktrees/x", "elsewhere/clone"):
            (root / nested / ".github" / "workflows").mkdir(parents=True)
            (root / nested / ".github" / "workflows" / "sample.yml").write_text(
                "      - run: python scripts/quality/check-orphan.py\n", encoding="utf-8")
        (root / "elsewhere" / "clone" / ".git").write_text("gitdir: /somewhere/.git/worktrees/clone\n", encoding="utf-8")

        found = find_orphans(root)
        if found != ["scripts/quality/check-orphan.py"]:
            print(
                "MILESTONE ARTIFACT REFERENCE GATE SELF-TEST FAILED: "
                f"expected exactly the unreferenced fixture, got {found}",
                file=sys.stderr,
            )
            return 1

    print("MILESTONE ARTIFACT REFERENCE GATE SELF-TEST SUCCESS (referenced/archived/orphan scenarios)")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--self-test", action="store_true", help="run the gate's own fixture-based self-test and exit")
    args = parser.parse_args()

    if args.self_test:
        return self_test()

    orphans = find_orphans(ROOT)
    if orphans:
        print("MILESTONE ARTIFACT REFERENCE GATE FAILED", file=sys.stderr)
        for orphan in orphans:
            print(f" - {orphan}: no workflow, script or documentation reference found anywhere; "
                  f"move it to scripts/history/ if it is retired, or wire it in if it is new", file=sys.stderr)
        return 1
    print(f"MILESTONE ARTIFACT REFERENCE GATE SUCCESS (scripts checked={len(candidate_scripts(ROOT / 'scripts'))})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
