# Audits MINOS

Ce dossier regroupe les audits du dépôt `minos-code-intelligence`. **Seul le dossier de l'audit en cours reste à la racine ; tout audit dont les constats sont traités est rangé sous [`archive/`](archive/).** Un document archivé conserve l'état et les formulations du moment (statuts « en cours », anciens SHA, anciens nombres de tests) : il n'est pas l'état actuel du produit, qui se lit dans [`../governance/STATUS.md`](../governance/STATUS.md) et [`../governance/ROADMAP.md`](../governance/ROADMAP.md).

## Audit en cours

| Dossier | Audit | État |
|---|---|---|
| [`2026-11-15/`](2026-11-15/README.md) | Audit technique complet du 15 novembre 2026 sur `main` `89be3fb4` (6 axes, aucun constat Critique ni Élevé) et ses vérifications successives : `README.md`, `findings.md` / `findings.json`, `sprints.md`, `plan-action.md`, `architecture.md`, `SUIVI.md`, `ETAT-*.md` | Ouvert. Les 12 sprints sont fusionnés et leurs changements archivés (2026-11-28) ; aucun changement OpenSpec n'est actif. La dernière vérification indépendante, [`ETAT-2026-11-27.md`](2026-11-15/ETAT-2026-11-27.md), compte 8 constats Faibles à traiter, « Ouvert » dans `findings.json` : le sprint 12 les traite et n'est pas revérifié. Les mesures, preuves manuelles et décisions du propriétaire restantes sont nommées dans le suivi vivant, [`SUIVI.md`](2026-11-15/SUIVI.md). |
| [`2026-10-10/`](2026-10-10/README.md) | Audit technique complet du 10 octobre 2026 (6 axes, 11 sprints) : `README.md`, `findings.md` / `findings.json`, `sprints.md`, `plan-action.md`, `architecture.md`, `SUIVI.md`, `ETAT-2026-10-13.md` | Traité, archivage à décider. Les 11 sprints sont fusionnés et aucun changement OpenSpec n'est actif. `findings.json` porte « Ouvert » pour AUD-PERF-16 et AUD-PERF-17 : l'audit du 2026-11-15 les reprend sous AUD-PERF-05 et AUD-PERF-06, fermés avec réserve (PR #769). Suivi : [`SUIVI.md`](2026-10-10/SUIVI.md). |

L'audit outillé du 8 octobre 2026 (SpotBugs, PIT, ArchUnit, Dependency-Check, Gitleaks) est tenu dans [`../quality/`](../../developer/quality/code-audit-couverture.md), non ici. Ses changements OpenSpec sont tous archivés, le dernier (`etendre-audit-outille-a-tout-le-perimetre`) le 2026-11-03.

## Archive

| Dossier | Contenu | Pourquoi archivé |
|---|---|---|
| [`archive/2026-10-06/`](archive/2026-10-06/README.md) | Audit d'octobre 2026 (101 constats) : [`README.md`](archive/2026-10-06/README.md), [`constats.md`](archive/2026-10-06/constats.md), [`annexes/`](archive/2026-10-06/annexes/), [`architecture-actuelle.md`](archive/2026-10-06/architecture-actuelle.md), [`CAPACITES.md`](archive/2026-10-06/CAPACITES.md), [`plan-de-remediation.md`](archive/2026-10-06/plan-de-remediation.md) | Ses huit changements OpenSpec sont implémentés, fusionnés dans `develop` et archivés (`openspec/changes/archive/2026-10-07-*`). L'audit du 10 octobre le revérifie. |
| [`archive/2026-09/`](archive/2026-09/AUDIT-2026-09.md) | Audit de septembre 2026 ([`AUDIT-2026-09.md`](archive/2026-09/AUDIT-2026-09.md)), les suivis de ses chantiers (`*-SUIVI.md`, `S15-REGRESSION-RUNTIME-GRANT.md`) et les prompts d'orchestration (`PROMPT-*.md`) | Les chantiers sont fusionnés dans `develop` (PR #305 à #332, promotion `develop` → `main` en PR #322) ; ce qui restait ouvert est repris et requalifié par l'audit du 10 octobre. Les ADR 0039 à 0047 renvoient à ces suivis pour les mesures et les preuves. |

L'audit du 10 octobre n'a plus de constat à traiter ni de changement OpenSpec actif : son dossier reste à la racine tant que son archivage n'est pas décidé. Le prompt de son sprint 1 est dans `2026-10-10/archive/`.

## Règle de rangement

1. Un audit est **en cours** tant qu'il a un constat ouvert ou un changement OpenSpec actif.
2. À la clôture, on déplace le dossier (ou ses fichiers) sous `archive/<date>/` avec `git mv`, on corrige les liens relatifs, et on ajoute la ligne du tableau ci-dessus.
3. Les prompts d'orchestration (`PROMPT-*.md`) sont des documents à usage unique : ils sont archivés avec le chantier qu'ils pilotaient.
4. Les changements OpenSpec déjà archivés (`openspec/changes/archive/`) ne sont pas réécrits : leurs références aux anciens chemins de ce dossier sont de l'historique.
