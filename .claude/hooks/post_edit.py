"""PostToolUse (Edit, Write, MultiEdit) : rappelle au modèle ce que la modification peut casser.

1. Gates littéraux : les scripts `scripts/**/check-*.py` affirment des chaînes exactes lues dans les
   sources. Si le fichier modifié est cité par l'un d'eux, le hook le dit et nomme la commande à
   rejouer (voir le skill minos-literal-gates).
2. Workflow YAML : un nom d'étape contenant « : » invalide tout le workflow (aucun job ne démarre).
   Le fichier modifié est chargé par yaml.safe_load quand PyYAML est disponible.
3. Script PowerShell : `.gitattributes` impose eol=crlf ; rappel si l'arbre de travail est en LF.
4. Nouveau gate ou auto-test : rappel du câblage obligatoire dans le job `invariants`.

Le hook n'échoue jamais : il n'émet que du contexte.
"""
from __future__ import annotations

import re
from pathlib import Path

from _common import add_context, project_root, read_event, relative_posix

GENERIC_NAMES = {"README.md", "pom.xml", "__init__.py", "SUIVI.md", "CHANGELOG.md", "tasks.md", "design.md", "proposal.md", "spec.md"}
SCRIPT_DIRS = ("scripts",)
SKIP_PARTS = ("/history/", "/__pycache__/")


def gates_reading(relative: str) -> list[tuple[str, int]]:
    name = Path(relative).name
    if name in GENERIC_NAMES or len(name) < 6:
        return []
    root = project_root()
    found: list[tuple[str, int]] = []
    for base in SCRIPT_DIRS:
        for script in (root / base).rglob("*.py"):
            posix = script.as_posix()
            if any(part in posix for part in SKIP_PARTS):
                continue
            script_rel = script.relative_to(root).as_posix()
            if script_rel == relative:
                continue
            try:
                text = script.read_text(encoding="utf-8", errors="ignore")
            except OSError:
                continue
            hits = text.count(relative) + len(re.findall(r"(?<![\w.-])" + re.escape(name), text))
            if hits:
                found.append((script_rel, hits))
    found.sort(key=lambda item: (-item[1], item[0]))
    return found[:8]


def workflow_problem(relative: str) -> str | None:
    if not (relative.startswith(".github/workflows/") and relative.endswith((".yml", ".yaml"))):
        return None
    try:
        import yaml  # type: ignore
    except ImportError:
        return None
    try:
        yaml.safe_load((project_root() / relative).read_text(encoding="utf-8"))
    except Exception as error:  # noqa: BLE001 - on rapporte, quelle qu'en soit la cause
        return f"{relative} n'est plus un YAML valide ({str(error).splitlines()[0]}). Un workflow invalide ne lance aucun job : le corriger avant tout push."
    return None


def main() -> None:
    event = read_event()
    tool_input = event.get("tool_input") or {}
    relative = relative_posix(tool_input.get("file_path") or "")
    if not relative:
        return
    notes: list[str] = []

    problem = workflow_problem(relative)
    if problem:
        notes.append(problem)

    if relative.endswith(".ps1"):
        data = (project_root() / relative).read_bytes() if (project_root() / relative).exists() else b""
        if b"\n" in data and b"\r\n" not in data:
            notes.append(f"{relative} est en LF dans l'arbre de travail ; .gitattributes impose eol=crlf pour les .ps1 "
                         "(git renormalise au commit). Préférer CRLF si le script est comparé octet pour octet par un test.")

    if re.search(r"^scripts/.*/(check-[^/]+|test_[^/]+)\.py$", relative) or relative.endswith("/check-ci-wiring.py"):
        notes.append(f"{relative} est un gate ou un auto-test : tout test_*.py et tout script à --self-test doit être exécuté par une "
                     "étape du job `invariants` de pr-ci.yml (python scripts/quality/check-ci-wiring.py le vérifie).")

    readers = gates_reading(relative)
    if readers:
        listed = "; ".join(f"{script} ({count})" for script, count in readers)
        notes.append(f"Des gates citent {relative} : {listed}. Les rejouer (python <script>) avant de conclure ; "
                     "ils affirment des chaînes littérales, pas seulement un comportement.")

    if notes:
        add_context("PostToolUse", " | ".join(notes))


if __name__ == "__main__":
    main()
