"""Squelette commun des gates de `scripts/quality` : l'option `--root` et le rapport des échecs.

Ce module n'est pas un gate : il n'a ni `check-` dans son nom ni auto-test propre. Il est exercé par
les auto-tests des gates qui l'utilisent (`test_check_single_execution.py`, `test_check_ci_wiring.py`).

Un gate l'importe ainsi, que le script soit lancé directement ou chargé par un auto-test :

    sys.path.insert(0, str(Path(__file__).resolve().parent))
    import gate_cli
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path


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
