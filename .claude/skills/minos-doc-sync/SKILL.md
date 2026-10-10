---
name: minos-doc-sync
description: Mettre à jour la documentation courante de MINOS après un changement fusionné ou prêt à l'être — docs/STATUS.md, docs/ROADMAP.md, documentation utilisateur et développeur, index des ADR, suivi d'audit — sans toucher à l'historique figé, puis rejouer les gates documentaires. À utiliser après la fusion d'une PR visible pour l'utilisateur, à la clôture d'un changement OpenSpec, ou quand l'utilisateur dit « mets à jour STATUS/ROADMAP » ou « synchronise la doc ».
---

# Synchronisation documentaire

## Où écrire quoi
| Information | Document |
|---|---|
| Ce qui est **livré** et garanti | `docs/STATUS.md` |
| Progression, travaux ouverts, prochains sprints | `docs/ROADMAP.md` |
| Utilisation (CLI, MCP, installation, plugin, diagnostics) | `docs/user/` |
| Architecture, gates, tests, supply-chain | `docs/developer/` (gates : `quality-gates.md`) |
| Décisions durables | `docs/adr/` (skill `minos-adr`) |
| Avancement d'un audit | `docs/audit/<date>/SUIVI.md` |
| Preuves d'époque | **ne pas modifier** : `docs/history/`, `docs/audit/archive/`, `openspec/changes/archive/` |

## Étapes
1. Relever ce qui a changé (`git log`, `git diff`, PR fusionnées) et **vérifier chaque affirmation** contre le code ou la CI (commande, run id) avant de l'écrire. Le statut d'un check, une version, un nombre : lus, pas supposés.
2. Mettre à jour `STATUS.md` (en-tête « mis à jour le … », section de l'audit/sprint, garanties, checks exigés) et `ROADMAP.md` (sprints restants) ; ne pas dupliquer un fait chiffré ailleurs.
3. Mettre à jour la page `docs/user/` ou `docs/developer/` de la fonctionnalité si une sortie, une option, un diagnostic ou une règle change.
4. Index des ADR si un ADR change de statut ou apparaît.
5. Cocher les tâches OpenSpec avec leur **preuve** (commande/run), pas seulement « fait ».
6. Rejouer : `python scripts/docs/check-current-docs.py`, `python scripts/docs/product-facts.py --check`, `python scripts/quality/check-milestone-artifact-references.py`, et vérifier les liens relatifs des fichiers modifiés.
7. Commit `docs(status): …` ou `docs(<portée>): …` ; PR séparée si le code n'est pas encore fusionné.

## Style
Français, phrases courtes, tableaux pour les listes de checks ou de capacités, dates absolues (« 10 octobre 2026 »), SHA et numéros de PR quand ils prouvent. Dire ce qui reste à faire et par qui (décision du propriétaire, action manuelle GitHub).

## Interdits
Annoncer une capacité non qualifiée ; réécrire un document historique pour « corriger » un ancien état ; laisser dans une doc courante un renvoi vers un chemin archivé sans le mentionner comme historique.
