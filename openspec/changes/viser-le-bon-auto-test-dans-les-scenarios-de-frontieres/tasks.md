# Tasks

Changement de texte seul, sans constat d'audit propre (source : PR 840). Aucun code, aucun scope JaCoCo.

## 1. Scénarios de la façade

- [x] 1.1 Rouge : `git grep -c 'test_check_module_boundaries_ratchets' -- openspec/specs/frontieres-de-modules/spec.md` rend 20 ; le chargement de `test_check_module_boundaries_ratchets` par `unittest.defaultTestLoader` rend 70 cas, 0 d'une classe `MinosApplication*Test`.
- [x] 1.2 Correction : reprendre en entier les exigences de la façade dans `specs/frontieres-de-modules/spec.md` et désigner l'auto-test de la façade (17 puces `WHEN`, 3 puces `GIVEN`), chaque scénario relu contre les deux auto-tests (design, Traçabilité). Exigences : frontieres-de-modules › les titres marqués D1 dans la traçabilité.
- [x] 1.3 Preuve : `python scripts/architecture/test_check_module_boundaries_facade.py` vert ; 3 `WHEN` des surfaces restent sur l'auto-test des cliquets.

## 2. Scénarios qui lancent le point d'entrée de l'auto-test pour un cas absent

- [x] 2.1 Rouge : `python scripts/architecture/test_check_module_boundaries.py <classe>` échoue (`AttributeError`) pour `ProcessLauncherRuleTest`, `StoragePackageIsolationRuleTest` et `EngineConcreteCodeMeasureTest`.
- [x] 2.2 Correction : désigner le fichier qui contient le cas (11 puces `WHEN`), après lecture de chaque cas. Exigences : frontieres-de-modules › les titres marqués D2 dans la traçabilité.
- [x] 2.3 Preuve : les commandes corrigées sortent en code 0 ; sur une copie hors du dépôt, `openspec archive` modifie 19 exigences et les seules 31 puces visées changent.

## 3. Clôture

- [x] 3.1 Matrice de traçabilité du design : une ligne par scénario modifié, avec le cas de l'auto-test.
- [x] 3.2 `openspec validate --all --strict`, `python scripts/docs/check-capability-dossiers.py` et `python .claude/scripts/run_gates.py` verts.
- [x] 3.3 Documents à mettre à jour avant archivage : aucun (design, Documentation durable).

## Workflow follow-up

- Archiver le changement après revue (`openspec archive viser-le-bon-auto-test-dans-les-scenarios-de-frontieres -y`) : la spécification courante ne change qu'à ce moment.
