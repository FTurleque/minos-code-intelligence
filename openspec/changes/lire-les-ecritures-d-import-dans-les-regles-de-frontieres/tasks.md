# Tasks

S'applique après `lire-toutes-les-ecritures-de-la-facade-dans-a11` (même code, même ordre d'archivage). Mutations : sur des arbres temporaires ou des copies du dépôt dans le scratchpad, jamais sur le dépôt.

## 1. Position d'un import dans sa ligne (AUD-ARC-17)

- [x] 1.1 AUD-ARC-17 — Rouge : cas de l'exigence frontieres-de-modules › Un import est lu quelle que soit sa position dans la ligne dans `test_check_module_boundaries_ratchets.py`, `_adapters.py` et `_packages.py`. Vérifier : les cas refusés échouent, les cas acceptés réussissent.
- [x] 1.2 AUD-ARC-17 — Correction (D1 a) : `SOURCE_IMPORT` et les blocs de texte de `COMMENTS_AND_LITERALS` dans `scripts/architecture/module_boundary_sources.py` ; commentaire en anglais. Vérifier : auto-tests verts.
- [x] 1.3 AUD-ARC-17 — Preuve : une copie du dépôt par forme, `check-module-boundaries.py` rend 1 ; sur le dépôt, rend 0, sortie identique à celle de la base.

## 2. Joker sur un type (AUD-ARC-17)

- [x] 2.1 AUD-ARC-17 — Rouge : cas des exigences frontieres-de-modules › Un joker sur un type du module visé échoue en A11 et en A13 et › Un joker sur une classe d'adaptateur échoue en A2. Vérifier : les cas refusés échouent, les cas acceptés réussissent.
- [x] 2.2 AUD-ARC-17 — Correction (D2 a, D3) dans `module_boundary_ratchets.py` et `module_boundary_adapters.py` ; docstrings en anglais, sans numéro de jalon. Vérifier : auto-tests verts, mutations 1 à 3.
- [x] 2.3 AUD-ARC-17 — Documentation : `docs/developer/quality-gates.md`. Vérifier : `check-current-docs.py` et `test_check_module_boundaries_documents.py` verts.

## 3. Clôture

- [x] 3.1 AUD-ARC-17 — Mettre à jour la matrice de traçabilité du design, preuves manquantes nommées.
- [ ] 3.2 AUD-ARC-17 — `python .claude/scripts/run_gates.py` et `openspec validate --all --strict` verts ; après archivage, lignes des trois exigences dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` et `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 3.3 AUD-ARC-17 — Documents avant archivage : `quality-gates.md` (2.3), dossier de capacité (3.2), suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste.
- [ ] 3.4 AUD-ARC-17 — **(manuelle)** Ajouter ces refus à la proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement »). Aucun ADR modifié ici.

## Workflow follow-up

- Archiver après `lire-les-methodes-generiques-de-la-facade-dans-a11` et `lire-toutes-les-ecritures-de-la-facade-dans-a11`, puis vérifier que la spec de référence garde sa section `## Documentation liée`.
