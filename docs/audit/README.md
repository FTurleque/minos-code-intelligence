# Audits MINOS

Ce dossier regroupe les audits du dépôt `minos-code-intelligence`. **Seul le dossier de l'audit en cours reste à la racine ; tout audit dont les constats sont traités est rangé sous [`archive/`](archive/).** Un document archivé conserve l'état et les formulations du moment (statuts « en cours », anciens SHA, anciens nombres de tests) : il n'est pas l'état actuel du produit, qui se lit dans [`../STATUS.md`](../STATUS.md) et [`../ROADMAP.md`](../ROADMAP.md).

## Audit en cours

| Dossier | Audit | État |
|---|---|---|
| `2026-10-10/` | Audit technique complet du 10 octobre 2026 (6 axes, 11 sprints) : `README.md`, `findings.md` / `findings.json`, `sprints.md`, `plan-action.md`, `architecture.md`, `SUIVI.md` | Sprint 1 corrigé et archivé ; sprint 2 spécifié ; sprints 3 à 11 à traiter. Le suivi vivant est `SUIVI.md` de ce dossier. |

L'audit outillé du 8 octobre 2026 (SpotBugs, PIT, ArchUnit, Dependency-Check, Gitleaks) est tenu dans [`../quality/`](../quality/code-audit-couverture.md), non ici : il n'est pas clos (changement OpenSpec `etendre-audit-outille-a-tout-le-perimetre` actif).

## Archive

| Dossier | Contenu | Pourquoi archivé |
|---|---|---|
| [`archive/2026-10-06/`](archive/2026-10-06/README.md) | Audit d'octobre 2026 (101 constats) : [`README.md`](archive/2026-10-06/README.md), [`constats.md`](archive/2026-10-06/constats.md), [`annexes/`](archive/2026-10-06/annexes/), [`architecture-actuelle.md`](archive/2026-10-06/architecture-actuelle.md), [`CAPACITES.md`](archive/2026-10-06/CAPACITES.md), [`plan-de-remediation.md`](archive/2026-10-06/plan-de-remediation.md) | Ses huit changements OpenSpec sont implémentés, fusionnés dans `develop` et archivés (`openspec/changes/archive/2026-10-07-*`). L'audit du 10 octobre le revérifie. |
| [`archive/2026-09/`](archive/2026-09/AUDIT-2026-09.md) | Audit de septembre 2026 ([`AUDIT-2026-09.md`](archive/2026-09/AUDIT-2026-09.md)), les suivis de ses chantiers (`*-SUIVI.md`, `S15-REGRESSION-RUNTIME-GRANT.md`) et les prompts d'orchestration (`PROMPT-*.md`) | Les chantiers sont fusionnés dans `develop` (PR #305 à #332, promotion `develop` → `main` en PR #322) ; ce qui restait ouvert est repris et requalifié par l'audit du 10 octobre. Les ADR 0039 à 0047 renvoient à ces suivis pour les mesures et les preuves. |

L'archive de l'audit du 10 octobre suivra la même règle quand ses 11 sprints seront traités ; le prompt du sprint 1 (terminé) est déjà dans `2026-10-10/archive/`.

## Règle de rangement

1. Un audit est **en cours** tant qu'il a un constat ouvert ou un changement OpenSpec actif.
2. À la clôture, on déplace le dossier (ou ses fichiers) sous `archive/<date>/` avec `git mv`, on corrige les liens relatifs, et on ajoute la ligne du tableau ci-dessus.
3. Les prompts d'orchestration (`PROMPT-*.md`) sont des documents à usage unique : ils sont archivés avec le chantier qu'ils pilotaient.
4. Les changements OpenSpec déjà archivés (`openspec/changes/archive/`) ne sont pas réécrits : leurs références aux anciens chemins de ce dossier sont de l'historique.
