# Proposal

## Why

L'auto-test du garde de frontières est réparti en plusieurs fichiers `scripts/architecture/test_check_module_boundaries*.py`, un par famille de règles ; la lecture A11 de la façade `MinosApplication` a le sien (PR 840). Des scénarios de `frontieres-de-modules` lancent un fichier qui ne contient pas le cas qu'ils décrivent : la commande échoue, ou réussit sans exécuter ce cas. Aucun gate ne vérifie le fichier cité par un `WHEN` : un relecteur qui rejoue le scénario croit avoir vérifié une règle qu'il n'a pas exécutée.

## What Changes

- 28 puces `WHEN` désignent l'auto-test qui porte leur cas :
  - 17 scénarios de la façade quittent `…_ratchets.py` pour `…_facade.py` ;
  - 11 scénarios quittent `test_check_module_boundaries.py` pour `…_ratchets.py` (5), `…_adapters.py` (4) ou `…_facade.py` (2).
- Trois puces `GIVEN` qui nomment « l'auto-test des cliquets » pour un cas de façade nomment « l'auto-test de la façade », dont le scénario « Import écrit dans un commentaire ou un littéral » que son `WHEN` (« le même auto-test ») renvoie à ce `GIVEN`.
- Le texte normatif des exigences, les `THEN` et les titres sont inchangés.

Aucun comportement du garde, des auto-tests, de la CLI, de l'API Java, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : dix-neuf exigences reprises en entier, seul l'auto-test désigné par leurs scénarios change.

## Hors périmètre

- Les scénarios dont le cas est dans le fichier qu'ils lancent, dont ceux des surfaces restés sur `…_ratchets.py`.
- Les scénarios qui n'attendent d'un auto-test que son code de sortie (design, Écarts).
- Le code des gates et de leurs auto-tests, le dossier de capacité et `docs/developer/quality-gates.md`.
- Un gate qui vérifierait le fichier cité par un `WHEN`.
- Les changements archivés.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| Les cas de façade sont dans les classes `MinosApplication*Test` de l'auto-test de la façade | [Existant vérifié] | `scripts/architecture/test_check_module_boundaries_facade.py:53`, `:136` |
| Ni l'auto-test des cliquets ni `test_check_module_boundaries.py` ne chargent ces classes | [Existant vérifié] | chargement par `unittest.defaultTestLoader` : 70 et 77 cas, 0 de façade |
| `test_check_module_boundaries.py` échoue sur une classe qu'il ne contient pas | [Existant vérifié] | `AttributeError` pour `ProcessLauncherRuleTest`, `EngineConcreteCodeMeasureTest`, `StoragePackageIsolationRuleTest` |
| Corriger les scénarios de façade | [Exigence] | demande du 2026-11-28 |
| Corriger aussi les scénarios qui lancent `test_check_module_boundaries.py` pour un cas absent | [Exigence] | demande du 2026-11-28, après relevé |
| Corriger aussi les trois `GIVEN` qui nomment le même auto-test | [Décision] | design, D1 |

## Impact

- **Modules du reactor** : aucun.
- **Surfaces publiques** : aucune.
- **ADR** : aucun créé ni amendé.
- **Gates à rejouer** : `python scripts/docs/check-capability-dossiers.py`, `python .claude/scripts/run_gates.py`, `openspec validate --all --strict`. Aucun scope JaCoCo.
- **Plateformes** : sans objet (texte de spécification).
- **Documentation durable** : aucune mise à jour.

## Questions ouvertes

Aucune bloquante.
