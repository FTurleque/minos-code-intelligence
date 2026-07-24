# ADR-0014 — N’utiliser l’indexation incrémentale que sous preuve explicite de capacité

- **Statut** : Accepted
- **Date** : 24 juillet 2026
- **Origine** : M7 — Indexation incrémentale

## Contexte

Détecter un changement borné dans un projet ne prouve pas qu’un indexeur sait produire correctement un delta. Une optimisation incrémentale non qualifiée peut laisser un index incohérent.

## Décision

MINOS planifie l’indexation selon `NONE`, `INCREMENTAL` ou `FULL` à partir d’empreintes et d’un `ChangeSet`, mais n’autorise `INCREMENTAL` que si la capacité fournisseur correspondante est explicitement démontrée.

- les empreintes projet/build/fichiers sont reproductibles et persistées ;
- les règles d’invalidation sont conservatrices ;
- un changement de build ou une capacité manquante force `FULL` ;
- l’absence de changement pertinent autorise `NONE` ;
- le fallback complet est une propriété de sûreté, pas un échec.

## Conséquences

MINOS peut optimiser les fournisseurs capables d’incrémental sans généraliser cette hypothèse à tous les indexeurs.

## Preuves

Voir [`../history/milestones/m7/DECISION_M7.md`](../history/milestones/m7/DECISION_M7.md) et les rapports M7 associés.
