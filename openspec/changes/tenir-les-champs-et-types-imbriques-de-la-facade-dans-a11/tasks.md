# Tasks

S'implémente après `lire-toutes-les-ecritures-de-la-facade-dans-a11`, et s'archive après lui. Le changement des angles morts d'import des cliquets touche les mêmes fichiers : partir de la branche qui est fusionnée la première. Mutations : sur des arbres temporaires ou des copies du dépôt dans le scratchpad, jamais sur le dépôt.

## 1. Champs et types imbriqués publics de la façade

- [x] 1.1 AUD-ARC-17 — Rouge : ajouter à `scripts/architecture/test_check_module_boundaries_ratchets.py` les cas des exigences frontieres-de-modules › La façade n'a aucun champ public typé moteur et › Le `Builder` est le seul type imbriqué public de la façade ; dans `test_declarations_without_an_engine_type_are_accepted`, rendre non publics le `record`, l'enum et l'annotation imbriqués (frontieres-de-modules › L'écriture d'une déclaration de la façade ne contourne pas A11). Vérifier : les cas refusés échouent, les cas acceptés réussissent.
- [x] 1.2 AUD-ARC-17 — Correction (D1 a, D2 a) dans `scripts/architecture/module_boundary_ratchets.py` ; docstrings en anglais, sans numéro de jalon. Vérifier : auto-test vert.
- [x] 1.3 AUD-ARC-17 — Preuve : sur des copies du dépôt, un champ typé moteur et un `record` imbriqué public font rendre 1 à `check-module-boundaries.py` ; sur le dépôt, 0. Gates : les `test_check_module_boundaries*.py`, `check-comment-language.py`, `run_gates.py`. Aucun scope JaCoCo.
- [x] 1.4 AUD-ARC-17 — Documentation : puce A11 des accesseurs de `docs/developer/quality-gates.md`. Vérifier : `check-current-docs.py` et `test_check_module_boundaries_documents.py` verts.

## 2. Suites manuelles

- [ ] 2.1 AUD-ARC-17 — **(manuelle)** Ajouter ces deux refus à la proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement »). Aucun ADR modifié ici.

## 3. Clôture

- [x] 3.1 AUD-ARC-17 — Mettre à jour la matrice de traçabilité du design, preuves manquantes nommées.
- [ ] 3.2 AUD-ARC-17 — `openspec validate --all --strict` vert ; après archivage, lignes des exigences dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` et `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 3.3 AUD-ARC-17 — Documents avant archivage : `quality-gates.md` (1.4), dossier de capacité (3.2), suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après `lire-toutes-les-ecritures-de-la-facade-dans-a11` (l'exigence modifiée doit exister dans la spec de référence), puis vérifier la section `## Documentation liée`.
