"""SessionStart : situe la session dans le dépôt (branche, état, changements OpenSpec actifs, audit en cours).

Sortie courte, sans appel réseau. Toute erreur est ignorée.
"""
from __future__ import annotations

import re
import shutil
import subprocess

from _common import add_context, current_branch, git, project_root, read_event


def active_changes() -> str:
    openspec = shutil.which("openspec")
    if not openspec:
        return ""
    try:
        out = subprocess.run([openspec, "list"], cwd=project_root(), capture_output=True, text=True, timeout=10, check=False)
    except Exception:
        return ""
    lines = [line.strip() for line in out.stdout.splitlines() if re.search(r"\d+/\d+ tasks|no tasks", line)]
    return "; ".join(lines[:8])


def audit_progress() -> str:
    suivi = project_root() / "docs" / "audit" / "2026-10-10" / "SUIVI.md"
    if not suivi.exists():
        return ""
    sprints = re.findall(r"^## (Sprint \d+[^\n]*)", suivi.read_text(encoding="utf-8", errors="ignore"), re.MULTILINE)
    return sprints[-1][:120] if sprints else ""


def main() -> None:
    read_event()
    branch = current_branch() or "(détachée)"
    head = git("rev-parse", "--short", "HEAD")
    dirty = len([line for line in git("status", "--short").splitlines() if line.strip()])
    parts = [f"MINOS — branche {branch} @ {head}, {dirty} fichier(s) modifié(s) ou non suivi(s)."]
    if branch in ("main", "develop"):
        parts.append("Branche protégée : créer une branche thématique avant tout commit (jamais de push direct).")
    changes = active_changes()
    if changes:
        parts.append(f"Changements OpenSpec actifs : {changes}.")
    audit = audit_progress()
    if audit:
        parts.append(f"Audit en cours : docs/audit/2026-10-10/SUIVI.md (dernière section : {audit}).")
    parts.append("Consignes : AGENTS.md et .claude/rules/ ; commandes /minos:* ; réponses en français.")
    add_context("SessionStart", " ".join(parts))


if __name__ == "__main__":
    main()
