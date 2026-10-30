"""Squelette commun des gates de `scripts/quality` : l'option `--root`, le rapport des échecs, le
parcours des fichiers du dépôt (sans les checkouts imbriqués) et la vérification qu'un gate et son
auto-test sont des étapes sans condition du job `invariants` (`find_ci_wiring_violations`).

Ce module n'est pas un gate : il n'a ni `check-` dans son nom ni auto-test propre. Il est exercé par
les auto-tests des gates qui l'utilisent (`test_check_single_execution.py`, `test_check_ci_wiring.py`,
`test_check_image_pins.py`, `test_check_compose_limits.py`, `test_check_twin_script_functions.py`,
`check-milestone-artifact-references.py --self-test`). Un gate qui l'importe n'a pas de repli : si le
module manque, l'import échoue et le gate se termine avec un code non nul.

Un gate l'importe ainsi, que le script soit lancé directement ou chargé par un auto-test :

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import gate_cli
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from collections.abc import Callable, Collection
from pathlib import Path


# Dossier ignoré par git où vivent les arbres de travail des développeurs : des copies entières du dépôt.
NESTED_CHECKOUT_DIRECTORIES = (".claude/worktrees",)

# Le workflow dont le job `invariants` exécute les gates.
PR_CI = ".github/workflows/pr-ci.yml"


def is_nested_checkout(directory: Path) -> bool:
    """Un arbre de travail (fichier `.git`) ou un clone (dossier `.git`) sous la racine : un autre arbre, pas ce dépôt."""
    return (directory / ".git").exists()


def repository_files(root: Path, skipped_directories: Collection[str]) -> list[Path]:
    """Les fichiers du dépôt sous `root`, triés ; ni les dossiers exclus ni les checkouts imbriqués ne sont parcourus.

    Un dossier qui ne peut pas être listé lève son `OSError` : le sauter rendrait un succès sur un arbre partiel.
    """
    def refuse(error: OSError) -> None:
        raise error

    def belongs(directory: Path) -> bool:
        return (directory.name not in skipped_directories
                and directory.relative_to(root).as_posix() not in NESTED_CHECKOUT_DIRECTORIES
                and not is_nested_checkout(directory))

    found: list[Path] = []
    for current, directories, names in os.walk(root, onerror=refuse):
        here = Path(current)
        directories[:] = [name for name in directories if belongs(here / name)]
        found.extend(here / name for name in names)
    return sorted(found)


def find_ci_wiring_violations(root: Path, steps: Collection[str]) -> list[str]:
    """Each of ``steps`` must be run by a step of the ``invariants`` job, with no ``if:`` on the step or the job.

    A step is a script path, matched against ``run: python <path>``. Line-based (no YAML parser): the job is
    the block under ``  invariants:``; a step is a ``- `` item of that block; ``if:`` is looked for at job
    level and at step level. What it does not prove: that the job runs on the events you care about
    (``on:``), or that an enclosing workflow-level condition is absent.
    """
    workflow = root / PR_CI
    if not workflow.is_file():
        return [f"{PR_CI} not found: the gate would not run in CI"]
    lines = [line for line in workflow.read_text(encoding="utf-8").splitlines() if not line.lstrip().startswith("#")]
    start = next((i for i, line in enumerate(lines) if re.match(r"^  invariants:\s*$", line)), None)
    if start is None:
        return [f"{PR_CI}: job `invariants` not found"]
    end = next((i for i in range(start + 1, len(lines)) if re.match(r"^  [A-Za-z0-9_-]+:\s*$", lines[i])), len(lines))
    job = lines[start + 1:end]
    failures: list[str] = []
    if any(re.match(r"^    if:", line) for line in job):
        failures.append(f"{PR_CI}: job `invariants` has an `if:` condition; its gates may be skipped")
    items = [len(line) - len(line.lstrip()) for line in job if re.match(r"^\s*- ", line)]
    step_indent = min(items) if items else 0
    blocks: list[list[str]] = []
    for line in job:
        if re.match(r"^\s*- ", line) and len(line) - len(line.lstrip()) == step_indent:
            blocks.append([line])
        elif blocks:
            blocks[-1].append(line)
    for step in steps:
        owner = [block for block in blocks
                 if any(line.strip().removeprefix("- ").startswith(f"run: python {step}") for line in block)]
        if not owner:
            failures.append(f"{PR_CI}: job `invariants` has no step running `python {step}`")
        elif any(re.match(r"^\s*(- )?if:", line) for line in owner[0]):
            failures.append(f"{PR_CI}: the step running `python {step}` has an `if:` condition; it may be skipped")
    return failures


def parse_root(doc: str, default_root: Path, argv: list[str] | None = None) -> Path:
    """Lit l'option `--root` ; la description de l'aide est la première ligne de la docstring du gate."""
    parser = argparse.ArgumentParser(description=(doc or "").splitlines()[0] if doc else None)
    parser.add_argument("--root", type=Path, default=default_root)
    return parser.parse_args(argv).root


def report_failures(label: str, failures: list[str]) -> bool:
    """Écrit chaque échec sur stderr sous la forme `<label> FAILED: <échec>` ; rend True s'il y en a."""
    for failure in failures:
        print(f"{label} FAILED: {failure}", file=sys.stderr)
    return bool(failures)


def run(doc: str, default_root: Path, check: Callable[[Path], tuple], label: str,
        success: Callable[..., str]) -> int:
    """Exécute un gate : `check(root)` rend `(échecs, *compteurs)`, `success(*compteurs)` compose le message final.

    Code de sortie 1 avec les échecs sur stderr, sinon 0 avec le message de succès sur stdout.
    """
    failures, *counters = check(parse_root(doc, default_root))
    if report_failures(label, failures):
        return 1
    print(success(*counters))
    return 0
