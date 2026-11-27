# Tasks

S'applique après `lire-les-methodes-generiques-de-la-facade-dans-a11` (même code, même ordre d'archivage). Mutations : sur des arbres temporaires ou des copies du dépôt dans le scratchpad, jamais sur le dépôt.

## 1. Écritures de la façade lues par A11 (AUD-ARC-17)

- [x] 1.1 AUD-ARC-17 — Rouge : ajouter à `scripts/architecture/test_check_module_boundaries_ratchets.py` les cas des exigences frontieres-de-modules › L'écriture d'une déclaration de la façade ne contourne pas A11 et › Un import qui passe par un type du moteur ne contourne pas A11. Vérifier : les cas refusés échouent, les cas acceptés réussissent.
- [x] 1.2 AUD-ARC-17 — Correction (D1 a, D2 a) dans `scripts/architecture/module_boundary_ratchets.py` ; docstrings en anglais, sans numéro de jalon. Vérifier : auto-test vert.
- [x] 1.3 AUD-ARC-17 — Preuve : une copie du dépôt par forme, `check-module-boundaries.py` rend 1 et nomme la méthode ou la cible ; sur le dépôt, rend 0, sortie comparée à celle de la base. Gates : les cinq `test_check_module_boundaries*.py`, `test_gate_runner.py`, `check-ci-wiring.py`, `check-comment-language.py`, `run_gates.py`. Aucun scope JaCoCo.
- [x] 1.4 AUD-ARC-17 — frontieres-de-modules › La façade ne rend pas la racine de composition par une méthode statique : éprouver `test_a_public_static_composition_root_is_refused` sur une copie du garde qui ignore les méthodes statiques. Vérifier : le test échoue sur la copie, réussit sur le dépôt.
- [x] 1.5 AUD-ARC-17 — Documentation : puce A11 des accesseurs de `docs/developer/quality-gates.md`. Vérifier : `check-current-docs.py` et `test_check_module_boundaries_documents.py` verts.

## 2. Suites manuelles

- [ ] 2.1 AUD-ARC-17 — **(manuelle)** Ajouter ces refus à la proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement »). Aucun ADR modifié ici.

## 3. Clôture

- [x] 3.1 AUD-ARC-17 — Mettre à jour la matrice de traçabilité du design (rouge, auto-test, mutations, garde au HEAD), preuves manquantes nommées.
- [ ] 3.2 AUD-ARC-17 — `openspec validate --all --strict` vert ; après archivage, lignes des trois exigences dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` et `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 3.3 AUD-ARC-17 — Documents avant archivage : `quality-gates.md` (1.5), dossier de capacité (3.2), suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste.

## Workflow follow-up

- Archiver après `lire-les-methodes-generiques-de-la-facade-dans-a11`, puis vérifier que la spec de référence garde sa section `## Documentation liée`.
