# Tasks

## 1. Renommages comptés dans les portées (AUD-DEP-08)

- [x] 1.1 AUD-DEP-08 — Rouge : ajouter à `scripts/ci/test_docker_gate.py` le renommage `src/main` → `src/test`, `src/main` → `docs/` et un renommage `docs/` → `openspec/`, et à `scripts/ci/test_plugin_gate.py` un rendu déplacé vers `docs/` (`controles-de-fusion › Les deux chemins d'un renommage comptent dans une portée calculée`). Vérifié : les deux tests de déplacement échouent au HEAD (portée `false`)
- [x] 1.2 AUD-DEP-08 — Correction : `--no-renames` dans `changed_files` de `scripts/ci/plugin-gate.py` (D1). Vérifié : `test_docker_gate.py` (28 tests) et `test_plugin_gate.py` (21 tests) verts ; `python scripts/quality/check-ci-wiring.py` vert
- [x] 1.3 Documentation : `docs/architecture/diagrams/capacites/controles-de-fusion.md` dit que les deux chemins d'un renommage comptent

## 2. Clôture

- [x] 2.1 Matrice de traçabilité du design : chaque scénario nomme son test écrit
- [x] 2.2 Dossier durable mis à jour en prose ; `python scripts/docs/check-capability-dossiers.py` vert. La ligne de traçabilité de l'exigence ajoutée s'écrit à l'archivage
- [x] 2.3 Valider : `openspec validate --all --strict` et `python .claude/scripts/run_gates.py --fast`
- [x] 2.4 Documents avant archivage : ligne AUD-DEP-08 de `docs/project/audits/2026-11-15/SUIVI.md` (faite) ; STATUS, ROADMAP : sans objet (aucune capacité produit)

## Workflow follow-up

- Archiver le changement après revue (`openspec archive compter-l-ancien-chemin-d-un-renommage-dans-la-portee -y`), puis vérifier que la spec de référence garde sa section `## Documentation liée`.
