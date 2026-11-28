"""SessionStart : situe la session dans le dépôt (branche, état, changements OpenSpec actifs, audit en cours).

Tout est lu dans l'arbre de travail du `cwd` de l'événement, à défaut dans le dossier principal.

Sortie courte, sans appel réseau. Toute erreur est ignorée.
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from pathlib import Path

from _common import add_context, current_branch, git, project_root, read_event, working_tree


def active_changes(tree: Path) -> str:
    openspec = shutil.which("openspec")
    if not openspec:
        return ""
    try:
        out = subprocess.run([openspec, "list"], cwd=tree, capture_output=True, text=True, timeout=10, check=False)
    except Exception:
        return ""
    lines = [line.strip() for line in out.stdout.splitlines() if re.search(r"\d+/\d+ tasks|no tasks", line)]
    return "; ".join(lines[:8])


def audit_progress(tree: Path) -> str:
    suivi = tree / "docs" / "project" / "audits" / "2026-10-10" / "SUIVI.md"
    if not suivi.exists():
        return ""
    sprints = re.findall(r"^## (Sprint \d+[^\n]*)", suivi.read_text(encoding="utf-8", errors="ignore"), re.MULTILINE)
    return sprints[-1][:120] if sprints else ""


def main() -> None:
    cwd = read_event().get("cwd") or ""
    tree = (working_tree(Path(cwd)) if os.path.isdir(cwd) else None) or project_root()
    branch = current_branch(tree) or "(détachée)"
    head = git("rev-parse", "--short", "HEAD", directory=tree)
    dirty = len([line for line in git("status", "--short", directory=tree).splitlines() if line.strip()])
    parts = [f"MINOS — branche {branch} @ {head}, {dirty} fichier(s) modifié(s) ou non suivi(s)."]
    if branch == "main":
        parts.append("Branche protégée : créer une branche thématique avant tout commit (jamais de push direct).")
    changes = active_changes(tree)
    if changes:
        parts.append(f"Changements OpenSpec actifs : {changes}.")
    audit = audit_progress(tree)
    if audit:
        parts.append(f"Audit en cours : docs/project/audits/2026-10-10/SUIVI.md (dernière section : {audit}).")
    parts.append("Consignes : AGENTS.md et .claude/rules/ ; commandes /minos:* ; réponses en français.")
    add_context("SessionStart", " ".join(parts))


if __name__ == "__main__":
    main()
