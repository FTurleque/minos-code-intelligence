# Proposal

## Why

La lecture A11 de la façade `MinosApplication` a son module de règles et son auto-test, `scripts/architecture/test_check_module_boundaries_facade.py`, séparés des cliquets (PR 840). Des scénarios de `frontieres-de-modules` lancent encore `test_check_module_boundaries_ratchets.py` pour un cas de façade : la commande réussit sans exécuter le cas décrit, et aucun gate ne vérifie le fichier cité par un `WHEN`. Un relecteur qui rejoue le scénario croit avoir vérifié une règle qu'il n'a pas exécutée.

## What Changes

- Les scénarios de la façade lancent `test_check_module_boundaries_facade.py` : 17 puces `WHEN` dans 10 exigences.
- Trois puces `GIVEN` qui nomment « l'auto-test des cliquets » pour un cas de façade nomment « l'auto-test de la façade », dont le scénario « Import écrit dans un commentaire ou un littéral » que son `WHEN` (« le même auto-test ») renvoie à ce `GIVEN`.
- Le texte normatif des exigences, les `THEN` et les titres sont inchangés.

Aucun comportement du garde, des auto-tests, de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : dix exigences reprises en entier, seul l'auto-test désigné par leurs scénarios de façade change.

## Hors périmètre

- Les scénarios des surfaces, d'A12 et d'A13 qui lancent `test_check_module_boundaries_ratchets.py` : leurs cas sont dans cet auto-test.
- Les scénarios qui lancent `test_check_module_boundaries.py` pour un cas porté par un autre auto-test (design, Écarts) : un autre changement.
- Le code des gates et de leurs auto-tests, le dossier de capacité et `docs/developer/quality-gates.md`.
- Un gate qui vérifierait le fichier cité par un `WHEN`.
- Les changements archivés.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les cas de façade sont dans les classes `MinosApplication*Test` de l'auto-test de la façade | [Existant vérifié] | `scripts/architecture/test_check_module_boundaries_facade.py:53`, `:136` |
| L'auto-test des cliquets ne charge aucune de ces classes | [Existant vérifié] | chargement par `unittest.defaultTestLoader` : 70 cas, dont 0 de façade ; 55 cas dans l'auto-test de la façade |
| Corriger les seuls scénarios de façade, sans toucher aux surfaces, à A12 ni à A13 | [Exigence] | demande du 2026-11-28 |
| Corriger aussi les trois `GIVEN` qui nomment le même auto-test | [Décision] | design, D1 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/docs/check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`, `openspec validate --all --strict`. Aucun scope JaCoCo.
- **Plateformes** : sans objet (texte de spécification).
- **Documentation durable** : aucune mise à jour ; les preuves du dossier `docs/architecture/diagrams/capacites/frontieres-de-modules.md` citent l'auto-test de la façade.

## Questions ouvertes

Aucune bloquante.
