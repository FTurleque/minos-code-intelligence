# Tasks

S'applique après `lire-les-ecritures-d-import-dans-les-regles-de-frontieres` (même code, même ordre d'archivage). Mutations : sur des arbres temporaires ou des copies du dépôt dans le scratchpad, jamais sur le dépôt.

## 1. Noms coupés autour d'un point (AUD-ARC-17)

- [x] 1.1 AUD-ARC-17 — Rouge : cas de l'exigence frontieres-de-modules › Un nom coupé autour d'un point est lu en entier dans `test_check_module_boundaries_ratchets.py`, `_adapters.py` et `_packages.py`. Vérifier : les cas refusés échouent, les cas acceptés réussissent.
- [x] 1.2 AUD-ARC-17 — Correction (D1 a) : `name_reading_code` et `QUALIFIER_GAP` dans `scripts/architecture/module_boundary_sources.py`, appelés par les lecteurs de noms ; docstrings et commentaires en anglais, sans numéro de jalon. Vérifier : auto-tests verts.
- [x] 1.3 AUD-ARC-17 — Preuve : une copie du dépôt par forme, `check-module-boundaries.py` rend 1 ; sur le dépôt, rend 0, sortie identique à celle de la base.
- [x] 1.4 AUD-ARC-17 — Documentation : `docs/developer/quality-gates.md`. Vérifier : `check-current-docs.py` et `test_check_module_boundaries_documents.py` verts.

## 2. Clôture

- [x] 2.1 AUD-ARC-17 — Mettre à jour la matrice de traçabilité du design, preuves manquantes nommées.
- [x] 2.2 AUD-ARC-17 — `python .claude/scripts/run_gates.py` et `openspec validate --all --strict` verts ; après archivage, ligne de l'exigence dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` et `python scripts/docs/check-capability-dossiers.py` vert. Constat : archivé le 2026-11-28, lignes ajoutées au dossier, `openspec validate --all --strict`, `check-capability-dossiers.py` et `run_gates.py` verts sur la branche d'archivage.
- [x] 2.3 AUD-ARC-17 — Documents avant archivage : `quality-gates.md` (1.4), dossier de capacité (2.2), suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste. Constat : `quality-gates.md` fait à l'implémentation ; dossier et suivi de l'audit mis à jour à l'archivage.
- [ ] 2.4 AUD-ARC-17 — **(manuelle)** Ajouter ce refus à la proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement »). Aucun ADR modifié ici. Constat : non faite ; le texte à soumettre au propriétaire est dans la section « Sprint 12 » du suivi de l'audit du 2026-11-15, aucun ADR modifié.

## Workflow follow-up

- Archiver après `lire-les-ecritures-d-import-dans-les-regles-de-frontieres`, puis vérifier que la spec de référence garde sa section `## Documentation liée`.
