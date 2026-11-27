# Tasks

S'applique après `lire-les-noms-coupes-autour-d-un-point-dans-les-frontieres` (même code, même ordre d'archivage). Mutations : sur des arbres temporaires ou des copies du dépôt dans le scratchpad, jamais sur le dépôt.

## 1. Échappements Unicode (AUD-ARC-17)

- [x] 1.1 AUD-ARC-17 — Rouge : cas de l'exigence frontieres-de-modules › Un échappement Unicode ne cache rien aux règles de frontières dans `test_check_module_boundaries_ratchets.py` et `_adapters.py`. Vérifier : les cas refusés échouent, les cas acceptés réussissent.
- [x] 1.2 AUD-ARC-17 — Correction (D1 a) : `java_escapes.py`, `read_java`, appelé par toute lecture de source Java d'une règle ; `check-private-io.py` importe le même décodage. Vérifier : auto-tests verts, dont `test_check_private_io.py`.

## 2. Annotation dans un nom qualifié (AUD-ARC-17)

- [x] 2.1 AUD-ARC-17 — Rouge : cas de l'exigence frontieres-de-modules › Une annotation dans un nom qualifié ne le cache pas dans les trois auto-tests.
- [x] 2.2 AUD-ARC-17 — Correction (D2 a) dans `module_boundary_sources.py` et `module_boundary_ratchets.py` ; docstrings en anglais, sans numéro de jalon. Vérifier : auto-tests verts.

## 3. Joker d'infrastructure (AUD-ARC-17)

- [x] 3.1 AUD-ARC-17 — Rouge : cas de l'exigence frontieres-de-modules › A19 lit le joker sur une API d'infrastructure dans `test_check_module_boundaries_adapters.py`.
- [x] 3.2 AUD-ARC-17 — Correction (D3) dans `module_boundary_adapters.py`. Vérifier : auto-tests verts.

## 4. Preuve et clôture

- [x] 4.1 AUD-ARC-17 — Preuve : une copie du dépôt par écriture, `check-module-boundaries.py` rend 1 ; sur le dépôt, `check-module-boundaries.py` et `check-private-io.py` rendent 0, sortie identique à celle de la base.
- [x] 4.2 AUD-ARC-17 — Documentation : `docs/developer/quality-gates.md`. Vérifier : `check-current-docs.py` et `test_check_module_boundaries_documents.py` verts.
- [x] 4.3 AUD-ARC-17 — Mettre à jour la matrice de traçabilité du design, preuves manquantes nommées.
- [ ] 4.4 AUD-ARC-17 — `python .claude/scripts/run_gates.py` et `openspec validate --all --strict` verts ; après archivage, lignes des trois exigences dans `docs/architecture/diagrams/capacites/frontieres-de-modules.md` et `python scripts/docs/check-capability-dossiers.py` vert.
- [ ] 4.5 AUD-ARC-17 — Documents avant archivage : `quality-gates.md` (4.2), dossier de capacité (4.4), suivi de l'audit (coordinateur, après fusion). Vérifier : chacun est fait ou nommé comme reste.
- [ ] 4.6 AUD-ARC-17 — **(manuelle)** Ajouter ces refus à la proposition de mise à jour d'ARCH-0087 (« Compliance and Enforcement »). Aucun ADR modifié ici.

## Workflow follow-up

- Archiver après `lire-les-noms-coupes-autour-d-un-point-dans-les-frontieres`, puis vérifier que la spec de référence garde sa section `## Documentation liée`.
