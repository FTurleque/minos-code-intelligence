"""Aide commune des hooks Claude Code de MINOS (Python 3, bibliothèque standard seule).

Règle de conduite : un hook ne doit jamais bloquer le travail par accident. Toute erreur interne
(entrée illisible, git absent, délai dépassé) laisse passer l'action ; seuls les refus explicites
écrits dans les gardes sont émis. L'un d'eux est fermé par défaut : un push dont la branche source
ne peut pas être lue est refusé (`guard_bash.py`).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

PROTECTED_BRANCHES = ("main",)


def read_event() -> dict:
    try:
        return json.loads(sys.stdin.buffer.read().decode("utf-8"))
    except Exception:  # entrée absente ou illisible : ne rien décider
        return {}


def project_root() -> Path:
    env = os.environ.get("CLAUDE_PROJECT_DIR")
    if env:
        return Path(env)
    return Path(__file__).resolve().parents[2]


def working_tree(location: Path) -> Path | None:
    """Racine de l'arbre de travail qui contient `location`, ou None hors du dossier principal.

    Le premier dossier, de `location` vers le dossier principal, dont `.git` est un fichier : la marque d'un
    arbre de travail lié (`.claude/worktrees/<nom>`). À défaut, le dossier principal.
    """
    root = project_root().resolve()
    location = location.resolve()
    if location != root and root not in location.parents:
        return None
    return next(folder for folder in (location, *location.parents) if folder == root or (folder / ".git").is_file())


def locate(file_path: str, cwd: str = "") -> tuple[Path, str] | None:
    """L'arbre de travail qui contient le fichier et son chemin dans cet arbre (séparateurs `/`).

    Un chemin relatif part de `cwd`, le dossier de l'événement. None si le fichier est hors du dépôt.
    """
    if not file_path:
        return None
    candidate = Path(file_path)
    if not candidate.is_absolute():
        candidate = Path(cwd or project_root()) / candidate
    candidate = candidate.resolve()
    tree = working_tree(candidate.parent)
    return None if tree is None else (tree, candidate.relative_to(tree).as_posix())


def git(*arguments: str, timeout: float = 5.0, directory: Path | None = None) -> str:
    """Sortie d'une commande git lancée dans `directory` (par défaut le dossier principal), "" en cas d'échec."""
    try:
        completed = subprocess.run(
            ["git", *arguments], cwd=directory or project_root(), capture_output=True, text=True,
            timeout=timeout, check=False)
        return completed.stdout.strip() if completed.returncode == 0 else ""
    except Exception:
        return ""


def current_branch(directory: Path | None = None) -> str:
    """Branche extraite dans `directory` ; "" si elle est illisible ou si HEAD est détachée."""
    return git("branch", "--show-current", directory=directory)


def permission(decision: str, reason: str) -> None:
    """Émet une décision PreToolUse (`deny`, `ask` ou `allow`) puis termine."""
    json.dump({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": decision,
            "permissionDecisionReason": reason,
        }
    }, sys.stdout)
    sys.exit(0)


def add_context(event_name: str, text: str) -> None:
    """Ajoute du contexte pour le modèle (PostToolUse, SessionStart) puis termine."""
    json.dump({
        "hookSpecificOutput": {"hookEventName": event_name, "additionalContext": text}
    }, sys.stdout)
    sys.exit(0)
