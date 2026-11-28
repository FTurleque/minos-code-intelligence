# Tasks

Changement de texte seul, sans constat d'audit propre (source : PR 840). Aucun code, aucun scope JaCoCo.

## 1. Scénarios de la façade

- [x] 1.1 Rouge : `git grep -c 'test_check_module_boundaries_ratchets' -- openspec/specs/frontieres-de-modules/spec.md` rend 20 ; le chargement de `test_check_module_boundaries_ratchets` par `unittest.defaultTestLoader` rend 70 cas, 0 d'une classe `MinosApplication*Test`.
- [x] 1.2 Correction : reprendre en entier les dix exigences dans `specs/frontieres-de-modules/spec.md` et désigner l'auto-test de la façade (17 puces `WHEN`, 3 puces `GIVEN`), chaque scénario relu contre les deux auto-tests (design, Traçabilité). Exigences : frontieres-de-modules › les dix titres du delta.
- [x] 1.3 Preuve : `python scripts/architecture/test_check_module_boundaries_facade.py` vert ; sur une copie hors du dépôt, `openspec archive` modifie 10 exigences, les seules 20 puces visées changent, 3 `WHEN` des surfaces restent sur l'auto-test des cliquets.

## 2. Clôture

- [x] 2.1 Matrice de traçabilité du design : une ligne par puce modifiée, avec le cas de l'auto-test.
- [x] 2.2 `openspec validate --all --strict`, `python scripts/docs/check-capability-dossiers.py` et `python .claude/scripts/run_gates.py` verts.
- [x] 2.3 Documents à mettre à jour avant archivage : aucun (design, Documentation durable). Restes nommés : les `WHEN` du tableau des Écarts du design.

## Workflow follow-up

- Archiver le changement après revue (`openspec archive viser-l-auto-test-de-la-facade-dans-les-scenarios -y`) : la spécification courante ne change qu'à ce moment.
