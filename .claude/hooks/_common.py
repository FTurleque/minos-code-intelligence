"""Aide commune des hooks Claude Code de MINOS (Python 3, bibliothèque standard seule).

Règle de conduite : un hook ne doit jamais bloquer le travail par accident. Toute erreur interne
(entrée illisible, git absent, délai dépassé) laisse passer l'action ; seuls les refus explicites
écrits dans les gardes sont émis.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path, PurePosixPath

PROTECTED_BRANCHES = ("main", "develop")


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


def relative_posix(file_path: str) -> str | None:
    """Chemin relatif au dépôt, séparateurs `/`, ou None s'il est hors du dépôt."""
    if not file_path:
        return None
    root = project_root().resolve()
    candidate = Path(file_path)
    if not candidate.is_absolute():
        candidate = root / candidate
    try:
        return PurePosixPath(candidate.resolve().relative_to(root).as_posix()).as_posix()
    except ValueError:
        return None


def git(*arguments: str, timeout: float = 5.0) -> str:
    try:
        completed = subprocess.run(
            ["git", *arguments], cwd=project_root(), capture_output=True, text=True,
            timeout=timeout, check=False)
        return completed.stdout.strip() if completed.returncode == 0 else ""
    except Exception:
        return ""


def current_branch() -> str:
    return git("branch", "--show-current")


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
