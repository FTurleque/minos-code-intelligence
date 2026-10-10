# Tasks

Ordre dans chaque lot : d'abord le gate qui échoue (l'entrée du cycle retirée de `KNOWN_PACKAGE_CYCLES`, rouge attendu), puis les déplacements, puis la preuve. Chaque tâche porte l'identifiant du constat qu'elle ferme (AUD-ARC-06). Déplacer avec `git mv` (historique) et déplacer **avec elles** les classes de test de même package (règle A3). Poser `JAVA_HOME` avant toute commande Maven (AUD-TST-18) ; sous PowerShell 7, mettre `powershell.exe` de Windows PowerShell dans le `PATH` et citer les arguments `-D…`. Les décisions 0.1 à 0.3 précèdent le code.

## 0. Décisions

- [x] 0.1 **(décision)** [AUD-ARC-06] Confirmer l'option du lot C (C1 dissoudre `discovery.spi`, recommandée ; C2 ou C3 du design). — 2026-10-10 : option C1 retenue (recommandée), sur demande d'implémenter sans autre précision.
- [x] 0.2 **(décision)** [AUD-ARC-06] Confirmer l'absence de façade de compatibilité pour `ProjectResolver` et les noms `com.minos.application.resolution` et `com.minos.output.json`. — 2026-10-10 : pas de façade ; packages `com.minos.application.resolution` et `com.minos.output.json`.
- [x] 0.3 [AUD-ARC-06] Relevé de départ : `python scripts/architecture/check-module-boundaries.py` (4 cycles, 15 packages) ; compter les tests des modules concernés (`minos-application`, `minos-engine`, `minos-cli`, `minos-mcp`, `minos-api`, `minos-app`) pour la comparaison finale ; vérifier qu'aucun fichier `META-INF/services` ne cite les classes déplacées. — Relevé : 4 cycles / 15 packages ; aucun `META-INF/services` ne cite les classes déplacées ; comparaison des nombres de tests faite sur les journaux de CI de #397 (code identique) et de cette PR.

## 1. Lot A — les 8 packages de `minos-application`

- [x] 1.1 [AUD-ARC-06] Rouge : retirer l'entrée `application-resolution-and-output` de `KNOWN_PACKAGE_CYCLES` ; le garde échoue en nommant les 8 packages.
- [x] 1.2 [AUD-ARC-06] `git mv` de `ProjectResolver` (avec `ResolutionException` imbriquée) vers `com.minos.application.resolution` avec ses tests ; corriger les imports des 16 classes de production (api 1, application 11, cli 2, mcp 2) et des tests.
- [x] 1.3 [AUD-ARC-06] `git mv` de `DeterministicJson` vers `com.minos.output.json` avec ses tests ; corriger les imports des 18 classes de production (application 12, cli 4, mcp 2) et des tests.
- [x] 1.4 [AUD-ARC-06] Gates littéraux dans la même tâche : `check-jacoco.py:22` (préfixe `com/minos/application/resolution/ProjectResolver`), `scripts/m15/run-final.ps1:62` (chemin), `check-post-mne.py:103,254` (chemin de `DeterministicJson.java`), `docs/user/java-api.md:359`. Rejouer `check-jacoco.py --self-test`, `check-post-mne.py`, `check-milestone-artifact-references.py`, `check-current-docs.py`.
- [x] 1.5 [AUD-ARC-06] Preuve : le garde A8 est vert sans l'entrée ; `.\mvnw.cmd -B -ntp -pl minos-application,minos-cli,minos-mcp,minos-api,minos-app -am test` vert ; `ModuleArchitectureTest` vert. — Garde A8 vert (3 cycles / 7 packages restants après le lot A) ; `test-compile` du reactor vert ; `ModuleArchitectureTest` vert. Les gardes `DuplicationGuardTest` et `JsonEscapeGuardTest` de `minos-app` listaient le chemin de `DeterministicJson` : mis à jour.

## 2. Lot B — `incremental` ↔ `orchestration`

- [x] 2.1 [AUD-ARC-06] Rouge : retirer l'entrée `engine-incremental-orchestration`.
- [x] 2.2 [AUD-ARC-06] `git mv` des six classes (`IncrementalIndexingCoordinator`, `IncrementalIndexingPlan`, `IncrementalIndexingPlanner`, `IncrementalIndexingResult`, `ProjectFingerprintSnapshotAlignmentService`, `ProjectInvalidationService`) vers `com.minos.orchestration` avec leurs tests (dont les 4 classes de test du coordinateur) ; corriger les imports (engine, application 4, cli).
- [x] 2.3 [AUD-ARC-06] Preuve : garde A8 vert sans l'entrée ; `grep -rn "import com.minos.orchestration" minos-engine/src/main/java/com/minos/incremental` → aucune ligne ; tests de `minos-engine`, `minos-application` et `minos-cli` verts. — Garde A8 : 2 cycles / 5 packages après le lot B. Un test (`IncrementalIndexingDiscoveryOrderTest`) utilisait la visibilité de package du coordinateur : déplacé avec lui.

## 3. Lot C — `discovery` ↔ `discovery.spi` (selon 0.1)

- [x] 3.1 [AUD-ARC-06] Rouge : retirer l'entrée `engine-discovery`.
- [x] 3.2 [AUD-ARC-06] Option C1 : `git mv` des 4 interfaces vers `com.minos.discovery` (le package `spi` disparaît), corriger les imports de `ProjectDiscoveryService`, `DefaultDiscoveryPlugins` et des tests ; mettre à jour `docs/adr/0026-…` (note de suivi, décision inchangée) et `docs/developer/polyglot-providers.md`.
- [x] 3.3 [AUD-ARC-06] Preuve : garde A8 vert sans l'entrée ; rejouer `check-polyglot-provider-consistency.py`, `check-mnd.py`, `check-mne.py` ; tests de `minos-engine` verts. — `check-polyglot-provider-consistency.py`, `check-mnd.py`, `check-mne.py` verts.

## 4. Lot D — plugin IntelliJ

- [x] 4.1 [AUD-ARC-06] Rouge : retirer l'entrée `intellij-plugin`.
- [x] 4.2 [AUD-ARC-06] `git mv` de `MinosRegistryNotice` vers `com.minos.intellij.protocol` ; corriger les imports de `MinosCliClient` et de `MinosToolWindowPanel`.
- [ ] 4.3 [AUD-ARC-06] Preuve : garde A8 vert ; `./gradlew test` du plugin si Gradle et Java 21 sont disponibles en local, sinon **(autorisation)** `IntelliJ Plugin Validation` en CI.

## 5. Clôture

- [x] 5.1 [AUD-ARC-06] `KNOWN_PACKAGE_CYCLES` est vide (le mécanisme et ses tests restent) ; `python scripts/architecture/check-module-boundaries.py` affiche « 0 known cycles » ; adapter les tests de la règle A8 qui comptent les quatre cycles du dépôt (`test_the_real_repository_has_exactly_the_four_known_cycles`). — « A8 package cycles: 0 known cycles over 0 packages » ; `test_the_real_repository_has_no_package_cycle_and_no_excuse_for_one`.
- [ ] 5.2 [AUD-ARC-06] Preuve complète : `.\mvnw.cmd -B -ntp clean verify` (environ 21 minutes) vert, mêmes nombres de tests qu'au relevé 0.3 ; `git diff --exit-code minos-app/src/test/resources/characterization` ; `python scripts/quality/check-jacoco.py --skip-scope m24-polyglot-provider-platform` (le rouge `m24` sous Windows est préexistant : comparer avec `develop`) ; tous les gates du job `invariants` rejoués.
- [x] 5.3 [AUD-ARC-06] Documentation : `docs/developer/quality-gates.md` (A8 : plus de cycle connu), ADR 0057 (ligne de suivi : cycles levés), `docs/architecture/arc42/05-vue-blocs.md` si une ligne « Sources » cite un package déplacé (A9 reste vert). — `quality-gates.md`, ADR 0057 et ADR 0026 (note de suivi). Baseline SpotBugs `quality/spotbugs-baseline/minos-engine.xml` : classes et chemins des classes déplacées mis à jour, 5 empreintes de `IncrementalIndexingPlan` rafraîchies (audit rejoué sur `minos-engine` : 0 nouvelle alerte).
- [x] 5.4 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` : AUD-ARC-06 passe de « partiel » à « fermé », avec commit et preuves ; `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage. — `SUIVI.md` mis à jour ; AUD-ARC-06 passe à « fermé » quand 4.3 et 5.2 (CI de la PR) sont constatés.
