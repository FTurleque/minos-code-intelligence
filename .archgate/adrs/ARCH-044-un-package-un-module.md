---
id: ARCH-044
title: Pont vers l'ADR 0044 — liste des modules gouvernés et reactor (A7)
domain: architecture
rules: true
---

# ARCH-044 — Pont vers l'ADR 0044

Ce fichier n'est pas une décision. Il porte les règles Archgate d'un invariant déjà gardé par un gate Python. Le registre des décisions est [`docs/architecture/adr/`](../../docs/architecture/adr/README.md) ; ce pont n'a de sens que tant que l'ADR canonique ci-dessous reste en vigueur.

- **ADR canonique** : [ADR 0044 — Un package, un module](../../docs/architecture/adr/0044-un-package-un-module.md), constat A7.
- **Gate de référence** : `scripts/architecture/check-module-boundaries.py`, fonction `check_reactor_modules`, exécuté par le job `invariants` de la CI.
- **Fiche d'usage** : [`docs/developer/archgate.md`](../../docs/developer/archgate.md).

## Context

La liste `MODULES` du gate est écrite en dur. Un module ajouté au reactor et absent de cette liste échappe à toutes les règles de frontière, POM comme sources.

## Decision

La liste `MODULES` de `scripts/architecture/check-module-boundaries.py` est exactement l'ensemble des `<modules>` du `pom.xml` racine, profils compris. La source de vérité reste le script Python ; la règle Archgate le lit au lieu de recopier la liste.

## Do's and Don'ts

- **DO** ajouter un module du reactor à `MODULES` dans le même changement que son `<module>` du `pom.xml` racine.
- **DO** retirer de `MODULES` un module qui quitte le reactor.
- **DON'T** copier la liste des modules dans une règle Archgate.
- **DON'T** modifier la décision ici : elle se modifie dans l'ADR 0044.

## Consequences

Une divergence entre le reactor et `MODULES` est détectée par `archgate check` en plus du gate Python. Les règles A2, A3, A8, A9 et A10 de ce même gate ne sont pas portées (voir la fiche d'usage).
