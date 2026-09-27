# Résidus du sprint 1 — suivi de la remédiation (audit 2026-09)

> Branche : `residus/sprint1` (depuis `develop`, base `31524fa8`, après fusion des PR #297 et #298).
> Constats : S12, S13 (audit hosted, résidus de S2), R2, R3 (cgroups, résidus de S3), Q19 (CLI, résidu de Q1).
> Méthode identique aux sprints précédents : test rouge avant correctif, un commit par constat, inspection continue par `verif-qualite`, builds ciblés par `verif-build`. Aucun push avant la PR finale.

## 1. Tableau de bord

| Constat | Agent | Branche | Statut | Test rouge | Test vert | Commit |
|---|---|---|---|---|---|---|
| S12 | impl-hosted | `residus/impl-hosted` | en cours | — | — | — |
| S13 | impl-hosted | `residus/impl-hosted` | en cours | — | — | — |
| Q19 | impl-hosted | `residus/impl-hosted` | en cours | — | — | — |
| R2 | impl-runtime | `residus/impl-runtime` | en cours | — | — | — |
| R3 | impl-runtime | `residus/impl-runtime` | en cours | — | — | — |

## 2. Constats de l'inspection continue (verif-qualite)

| # | Constat | Fichier:ligne | Sévérité | Ce qui casse | Résolution |
|---|---|---|---|---|---|

## 3. À traiter plus tard (hors périmètre)

| Origine | Description | Renvoi |
|---|---|---|

## 4. Journal

- 2026-09-27 — ménage : worktrees `minos-wt-hautes/*` et branches `hautes/*` supprimés (tous fusionnés par #297 et #298). Ouverture du chantier des résidus du sprint 1 sur demande de l'utilisateur ; la PR sera créée une fois les cinq résidus corrigés.

## 5. Bilan

| Constat | Fichiers de production | Tests ajoutés | Rouge → vert |
|---|---|---|---|
