# Tasks

## 1. Configuration d'audit (faite pendant l'audit du 8 octobre 2026)

- [x] 1.1 PIT : `targetClasses`/`targetTests` à `com.minos.*` dans le profil `audit-mutation`, propriété `pit.crossModule`. Preuve : exécution E20 de `docs/quality/code-audit-couverture.md`.
- [x] 1.2 ArchUnit 1.5.1 en test de `minos-app`, `ModuleArchitectureTest` (garde d'import, six règles établies, mesure stricte opt-in). Preuve : E14 (vert), E15 (mesure rouge, attendue). Gates : `check-module-boundaries.py`, `check-current-docs.py`, `check-minos-01.py`.
- [x] 1.3 Profils `audit-dependency-check` et `audit-dependency-check-tests` (13.0.0). Preuve : E17, E18.
- [x] 1.4 `audit-report-summary.py` : `spotbugs --reports-dir/--strict`, `pit --all`, `dependency-check`. Preuve : E13.
- [x] 1.5 `code-audit.yml` : PIT par module, job Dependency-Check. Gates : `check-workflow-pins.py`, `check-single-execution.py` (verts).
- [x] 1.6 Documentation : `docs/quality/code-audit.md`, `code-audit-couverture.md`, `code-audit-constats.md` § 5 à § 8.

## 2. À faire

- [ ] 2.1 Clé NVD valide dans l'environnement de l'utilisateur et comme secret `NVD_API_KEY` du dépôt (action de l'utilisateur, H04) ; rejouer `update-only` avec la clé.
- [x] 2.2 Automatiser dans `code-audit.yml` la relecture SpotBugs en ligne de commande et `audit-report-summary.py spotbugs --reports-dir … --strict` (H01). — 2026-10-08 : `scripts/quality/spotbugs-cli.py` et étape « Prouver le périmètre analysé » du job SpotBugs ; local : 14 modules analysés en entier, 197 alertes, `--strict` vert ; `check-workflow-pins.py`, `check-single-execution.py` verts.
- [x] 2.3 `quality/dependency-check-suppressions.xml` limité aux deux faux rapprochements de H02, branché sur les deux profils avec `failBuildOnUnusedSuppressionRule=true` ; test : rejouer E17 (0 alerte `com.minos`). — 2026-10-08 : E17 rejoué (`-DautoUpdate=false`) : 37 dépendances, 0 exception, aucune vulnérabilité ; 43 alertes supprimées, toutes sur `minos-storage-postgresql` ; règle inutilisée vérifiée par l'analyseur.
- [ ] 2.4 Wrapper Gradle de `minos-intellij` et job plugin dans `code-audit.yml` (tests, SpotBugs, Dependency-Check, PIT) (H09) ; qualifier les 6 alertes « à qualifier » de H21. — Wrapper Gradle 9.6.1 ajouté le 2026-10-08 (SHA-256 de la distribution épinglé, `intellij-plugin.yml` passe par `./gradlew`). Restent : job plugin dans `code-audit.yml`, qualification des 6 alertes de H21.
- [ ] 2.5 Décision de l'utilisateur sur Gitleaks en CI (H06) ; si oui : `.gitleaks.toml` à listes d'autorisation par forme, job `pull_request` avec `fetch-depth: 0`, rapport rédigé en artefact.
- [ ] 2.6 Exécuter `code-audit.yml` sur GitHub (SpotBugs, PIT d'un module, Dependency-Check) et consigner le run.
- [ ] 2.7 Après décision ADR sur H08 : rendre la mesure stricte obligatoire, la retirer, ou déclarer les arêtes dans les POM et `ALLOWED_DEPENDENCIES` (rejouer `check-module-boundaries.py` et son auto-test).
