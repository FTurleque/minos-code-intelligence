---
description: Archiver les audits finalisés dans docs/audit/archive/ (git mv, liens relatifs corrigés, index mis à jour)
argument-hint: "[dossier ou fichiers d'audit à archiver]"
---

Range `docs/audit/` selon la règle de son `README.md` : `$ARGUMENTS`.

1. Inventorier `docs/audit/` et établir pour chaque document s'il est **finalisé** : constats traités, PR fusionnées, changements OpenSpec archivés, aucune case ouverte dans son suivi. Un audit avec un constat ouvert ou un changement actif reste à la racine. Prouver chaque classement (PR, archive OpenSpec, ligne du suivi).
2. Archiver sous `docs/audit/archive/<date>/` avec `git mv` (jamais copier/supprimer).
3. Corriger **tous** les renvois : liens relatifs des fichiers déplacés et de ceux qui les citent (ADR, STATUS, ROADMAP, docs, commentaires de code et de scripts). Ne pas réécrire `openspec/changes/archive/` ni les relevés d'audit (`findings.*`).
4. Mettre à jour le tableau de `docs/audit/README.md`.
5. Vérifier : aucun lien relatif cassé dans les fichiers modifiés ; `python .claude/scripts/run_gates.py --fast` ; `openspec validate --all --strict`.
6. Commit `docs(audit): archiver …`. Ne pas pousser sans demande.
