# Tasks

Constat d'origine : relecture de sécurité de la PR #416 (invariant de frontière de propriété tenu par convention). Commandes : `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/test_check_module_boundaries.py`.

## 1. Règle A10

- [x] 1.1 Écrire les tests de refus et d'acceptation de la règle sur arbres temporaires (`RuntimeLocalExportRuleTest` : import simple, statique, joker, nom qualifié, source de test, membre réservé, commentaire et littéral, module lui-même, entrée sans fichier, entrée sans utilisateur, arbre réel) et le test de câblage de `main()`, puis écrire `check_runtime_local_exports` et l'appeler depuis `main()`.
- [x] 1.2 Rejouer `check-module-boundaries.py` sur le dépôt (11 types exportés, aucun refus) et `test_check_module_boundaries.py` (104 cas).

## 2. Preuve

- [x] 2.1 Rejouer `python .claude/scripts/run_gates.py` (dont `check-ci-wiring.py`, `check-single-execution.py`, `check-p0-p2.py`, `check-current-docs.py`, `product-facts.py --check`).
- [x] 2.2 Mettre à jour `docs/developer/quality-gates.md`, `docs/STATUS.md`, `docs/ROADMAP.md`, `docs/architecture/arc42/05-vue-blocs.md` et les règles `.claude/` qui listent les règles du gate.

## 3. Clôture

- [x] 3.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (reste 1 du rangement traité ; PR #416 fusionnée).
- [x] 3.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage.
