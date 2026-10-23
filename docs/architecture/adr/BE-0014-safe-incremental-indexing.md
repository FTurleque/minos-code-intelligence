---
id: BE-0014
title: "N’utiliser l’indexation incrémentale que sous preuve explicite de capacité"
domain: backend
rules: false
---

# N’utiliser l’indexation incrémentale que sous preuve explicite de capacité

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M7 — Indexation incrémentale

## Context

Détecter un changement borné dans un projet ne prouve pas qu’un indexeur sait produire correctement un delta. Une optimisation incrémentale non qualifiée peut laisser un index incohérent.

## Decision

MINOS planifie l’indexation selon `NONE`, `INCREMENTAL` ou `FULL` à partir d’empreintes et d’un `ProjectChangeSet`, mais n’autorise `INCREMENTAL` que si la capacité fournisseur correspondante est explicitement démontrée.

- les empreintes projet/build/fichiers sont reproductibles et persistées ;
- les règles d’invalidation sont conservatrices ;
- un changement de build ou une capacité manquante force `FULL` ;
- l’absence de changement pertinent autorise `NONE` ;
- le fallback complet est une propriété de sûreté, pas un échec.

## Do's and Don'ts

### Do

- Planifier `NONE`, `INCREMENTAL` ou `FULL` à partir d'empreintes et d'un `ProjectChangeSet`.
- Rendre les empreintes projet, build et fichiers reproductibles et persistées.
- Forcer `FULL` sur un changement de build ou une capacité manquante.

### Don't

- Ne pas autoriser `INCREMENTAL` sans capacité fournisseur explicitement démontrée.
- Ne pas traiter le repli complet comme un échec.

## Consequences

MINOS peut optimiser les fournisseurs capables d’incrémental sans généraliser cette hypothèse à tous les indexeurs.

## Compliance and Enforcement

- Test `IncrementalIndexingPlannerTest` (`minos-engine`) — choix entre `NONE`, `INCREMENTAL` et `FULL`.
- Revue : `INCREMENTAL` conditionné à `IndexerCapability.INCREMENTAL_INDEXING`, règles d'invalidation conservatrices.

## Preuves

Voir [`../../project/history/milestones/m7/DECISION_M7.md`](../../project/history/milestones/m7/DECISION_M7.md) et les rapports M7 associés.
