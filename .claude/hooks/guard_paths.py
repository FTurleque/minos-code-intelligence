"""PreToolUse (Edit, Write, MultiEdit, NotebookEdit) : protège ce qui ne se modifie pas à la main.

- refuse les fichiers générés (`target/`, `build/`, `.intellijPlatform/`, `docs/generated/`) : on régénère ;
- refuse l'écriture d'un secret apparent (clé privée, jeton GitHub, clé AWS, clé MINOS_TEAM_KEY_*) ;
- demande confirmation pour les surfaces de gouvernance : historique figé (archives OpenSpec, audits
  archivés, docs/history), spécifications OpenSpec courantes (elles se modifient par archivage),
  goldens de caractérisation, workflows, liste des checks exigés, listes de tolérance des gardes,
  licence et sécurité.

Voir docs/developer/ai-configuration.md.
"""
from __future__ import annotations

import re

from _common import permission, project_root, read_event, relative_posix

DENY_PREFIXES = (
    ("target/", "fichier généré par Maven : le régénérer par le build"),
    (".intellijPlatform/", "répertoire généré par Gradle"),
    ("docs/generated/", "document généré : le régénérer par son script (voir docs/developer/quality-gates.md)"),
)
DENY_CONTAINS = (
    ("/target/", "fichier généré par Maven : le régénérer par le build"),
    ("/build/", "répertoire généré par Gradle"),
)

ASK_RULES = (
    (re.compile(r"^openspec/changes/archive/"),
     "Historique OpenSpec figé : les changements archivés ne se réécrivent pas (ses références à d'anciens chemins sont de l'historique)."),
    (re.compile(r"^docs/audit/archive/|^docs/audit/[^/]+/archive/"),
     "Audit archivé : état du moment, à ne corriger que pour un lien ou une erreur factuelle."),
    (re.compile(r"^docs/history/"),
     "Documentation historique (ADR 0043) : conservée comme état d'époque."),
    (re.compile(r"^openspec/specs/"),
     "Les spécifications courantes se modifient par `openspec archive` d'un changement (deltas ADDED/MODIFIED), pas à la main."),
    (re.compile(r"^minos-app/src/test/resources/characterization/"),
     "Golden de caractérisation : ne le régénérer qu'avec une justification écrite (changement voulu, additif) et indépendante de l'hôte."),
    (re.compile(r"^\.github/workflows/"),
     "Workflow GitHub : surface de gouvernance. Valider le YAML (yaml.safe_load), garder les actions épinglées par SHA, rejouer check-workflow-pins, check-single-execution et check-ci-wiring."),
    (re.compile(r"^\.github/required-checks\.json$"),
     "Liste déclarée des checks exigés par le ruleset : un changement doit être suivi de scripts/quality/verify-ruleset.py et d'une action manuelle sur GitHub."),
    (re.compile(r"^scripts/architecture/private-io-allowlist\.json$"),
     "Liste de tolérance en cliquet : elle ne fait que se resserrer ; chaque entrée porte une justification nominative."),
    (re.compile(r"^docs/adr/\d{4}-"),
     "ADR : une décision Accepted ne se réécrit pas ; compléter la section « Mise en œuvre » ou écrire un ADR qui la remplace."),
    (re.compile(r"^(LICENSE|SECURITY\.md|CONTRIBUTING\.md|\.gitleaks\.toml)$"),
     "Document juridique ou de sécurité du dépôt : modification à confirmer par le propriétaire."),
)

SECRET_PATTERNS = (
    (re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA |PGP )?PRIVATE KEY-----"), "clé privée"),
    (re.compile(r"\bAKIA[0-9A-Z]{16}\b"), "identifiant d'accès AWS"),
    (re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,}\b"), "jeton GitHub"),
    (re.compile(r"\bgithub_pat_[A-Za-z0-9_]{50,}\b"), "jeton GitHub à granularité fine"),
    (re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b"), "jeton Slack"),
    (re.compile(r"\bMINOS_TEAM_KEY_[A-Z0-9_]+\s*=\s*[A-Za-z0-9+/=_-]{16,}"), "clé maîtresse MINOS"),
)


def content_of(tool_input: dict) -> str:
    parts = [tool_input.get("content"), tool_input.get("new_string"), tool_input.get("new_source")]
    for edit in tool_input.get("edits") or []:
        parts.append(edit.get("new_string"))
    return "\n".join(part for part in parts if isinstance(part, str))


def main() -> None:
    event = read_event()
    tool_input = event.get("tool_input") or {}
    relative = relative_posix(tool_input.get("file_path") or tool_input.get("notebook_path") or "")
    if relative is None:
        return
    for prefix, reason in DENY_PREFIXES:
        if relative.startswith(prefix):
            permission("deny", f"{relative} : {reason}.")
    for fragment, reason in DENY_CONTAINS:
        if fragment in "/" + relative:
            permission("deny", f"{relative} : {reason}.")
    text = content_of(tool_input)
    for pattern, label in SECRET_PATTERNS:
        if pattern.search(text):
            permission("deny", f"Secret apparent ({label}) dans le contenu à écrire dans {relative} : "
                               "ne jamais versionner de secret (Gitleaks est un check exigé).")
    for pattern, reason in ASK_RULES:
        if pattern.search(relative):
            if relative.startswith("docs/adr/") and not (project_root() / relative).exists():
                continue  # un nouvel ADR se crée librement (skill minos-adr)
            permission("ask", f"{relative} : {reason}")


if __name__ == "__main__":
    main()
