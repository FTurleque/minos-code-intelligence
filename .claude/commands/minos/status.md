---
description: Tableau de bord du dépôt — branche, travail local, PR ouvertes, changements OpenSpec actifs, avancement de l'audit, état de develop
allowed-tools: Bash(git status*), Bash(git log*), Bash(git branch*), Bash(gh pr list*), Bash(gh pr view*), Bash(gh pr checks*), Bash(openspec list*), Read, Grep
---

Donne un état court et factuel du dépôt, sans rien modifier.

1. Git : branche courante, `git status --short`, retard/avance sur `origin/develop`, stashes existants (ne pas y toucher).
2. GitHub : PR ouvertes (`gh pr list`) avec leur état de checks (`gh pr checks <n>`), en signalant `BEHIND`. (`rtk proxy gh` si le JSON est filtré.)
3. OpenSpec : `openspec list` (changements actifs, tâches faites/total).
4. Audit : dernière section de `docs/audit/2026-10-10/SUIVI.md` (sprint, statuts, décisions en attente).
5. Terminer par **les 3 prochaines actions utiles** (par exemple : PR à fusionner, décision à demander, sprint à préparer).
