# Tasks

Ordre dans chaque section : rouge, correction, preuve. Les tests vivent dans la même tâche que le code. Gates à rejouer à chaque tâche : `python scripts/architecture/check-module-boundaries.py`, `python scripts/architecture/test_check_module_boundaries.py -v`, `python scripts/remediation/check-p0-p2.py`, `python .claude/scripts/run_gates.py --fast`. Aucune portée JaCoCo concernée (aucun code Java modifié).

## 1. A12 voit un `Runtime` tenu par un nom (AUD-ARC-05)

- [x] 1.1 [AUD-ARC-05] Rouge : `ProcessLauncherRuleTest.test_runtime_exec_through_a_variable_a_parameter_or_a_method_reference_is_refused` (variable locale, `var`, champ, paramètre `java.lang.Runtime`, `Runtime.getRuntime()::exec`). **Preuve du rouge au HEAD** : 5 sous-tests sur 5 échouent (« RuntimeError not raised »).
- [x] 1.2 [AUD-ARC-05] `process_launch_sites` et `RUNTIME_HOLDER` dans `check-module-boundaries.py` ; `PROCESS_LAUNCH` et `PROCESS_LAUNCH_SITE` acceptent `::exec` après `getRuntime()` ; test `test_runtime_queries_without_exec_are_not_launchers`. Preuve : `ProcessLauncherRuleTest` 15/15 ; A12 sur le dépôt : 0 classe, 0 site.

## 2. Mesure du code concret du moteur (AUD-ARC-05, D1)

- [x] 2.1 [AUD-ARC-05] `engine_concrete_code` et ligne `AUD-ARC-05 minos-engine concrete code` dans `main()`, sans échec ; `EngineConcreteCodeMeasureTest` (arbre temporaire, module vide, dépôt sans fichier `unclassified`). Preuve : sortie du garde au HEAD, 69 fichiers de classes, 12 836 lignes sur 17 526 (73,2 %).

## 3. Sous-packages de `com.minos.storage` (AUD-ARC-12, D2)

- [x] 3.1 [AUD-ARC-12] Rouge : `StoragePackageIsolationRuleTest.test_a_package_below_the_storage_root_outside_minos_storage_is_refused` (moteur et application en production, `minos-bootstrap` en test). **Preuve du rouge au HEAD** : 3 sous-tests sur 3 échouent.
- [x] 3.2 [AUD-ARC-12] Règle ajoutée à `check_storage_package_isolation`, message « package below com.minos.storage outside minos-storage ». Preuve : `StoragePackageIsolationRuleTest` 13/13 ; garde vert sur le dépôt (4 règles).

## 4. ADR et vérification

- [x] 4.1 [AUD-ARC-05, AUD-ARC-12] ADR 0068, « Mise en œuvre » : mesure et raison de l'absence de cliquet (D1), A12 étendu et sa limite (D3), règle des sous-packages du stockage et raison du non-déplacement (D2) ; « Compliance and Enforcement » : A12 et contrôle des sous-packages. Preuve : `archgate check`.
- [x] 4.2 Preuve d'ensemble : `python scripts/architecture/test_check_module_boundaries.py -v` ; `python .claude/scripts/run_gates.py --fast` ; aucun build Maven (aucune source Java ni aucun POM modifié) ; relecture par l'agent `minos-architecture-reviewer`.

## 5. Clôture

- [ ] 5.1 **(manuelle)** Mettre à jour le suivi de l'audit (`docs/project/audits/2026-10-10/SUIVI.md`, `findings.json`, `ETAT-2026-10-13.md`) : AUD-ARC-05 et AUD-ARC-12 fermés par ce changement. Fait par la session de clôture du sprint 10, pas dans cette PR.
- [ ] 5.2 `openspec validate --all --strict`, puis archivage après fusion ; `docs/developer/quality-gates.md` (A12, A14, mesure du moteur) est mis à jour dans la même PR.
