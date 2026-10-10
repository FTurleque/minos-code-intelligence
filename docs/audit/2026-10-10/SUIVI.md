# Suivi de l'audit du 10 octobre 2026

Suivi de la correction des constats de [`findings.md`](findings.md), dans l'ordre de [`sprints.md`](sprints.md). Une ligne par constat traité ; la preuve est une commande ou une assertion de test, pas « la CI est verte ».

## Sprint 1 — Remettre les dispositifs de mesure en marche

| Constat | Changement OpenSpec | Statut | Commit | Preuve |
|---|---|---|---|---|
| AUD-TST-06 | `rendre-visibles-les-tests-de-confinement-sautes` | **Fermé** (local + CI) | `258de1d4`, branche `ci/confinement-requis` | `grep -rnE 'if \(.*(currentPlatform\(\)\|isWindows).*\) *return;' minos-runtime-local/src/test` → 0 ligne (21 avant). Poste Windows : `LinuxBubblewrapWorkerSandboxBackendTest` 4 tests / 4 sautés, `LinuxCgroupJobOwnershipIsolationTest` 10 / 10, `WindowsAppContainerWorkerSandboxBackendTest` 15 / 0. |
| AUD-TST-03 | idem | **Fermé** (preuve CI du 2026-10-10) | `258de1d4`, branche `ci/confinement-requis` (poussée, sans PR) | `SandboxTestSupportTest` 8 tests (1 sauté : propre au job `verify` de CI). Mode requis sous Windows : `-Dminos.sandbox.tests.required=true` → 135 tests, 0 échec ; classes `Windows*` listées dans la spec : 0 sauté. `verify -pl minos-runtime-local -am` : 351 tests (343 + 8), 0 échec, 43 sautés (38 + 4 Bubblewrap + 1 test de CI). Scopes JaCoCo `provider-sandbox-windows` 0,838 / 0,649 (identique au rapport agrégé), `provider-execution-trust-boundary` 0,753 / 0,538 sur le rapport du seul module (0,793 / 0,583 sur l'agrégat), `provider-sandbox-linux` SKIPPED par plateforme. Gate de présence : `python scripts/docs/check-current-docs.py` exige l'argument sur chaque `mvnw … verify` de `pr-ci.yml`. |
| AUD-TST-07 | `imposer-les-gates-et-leurs-auto-tests` | **Implémenté** (preuve CI à la PR) | branche `ci/imposer-gates-auto-tests` | `python scripts/quality/check-jacoco.py --self-test` (11 scénarios) et `check-milestone-artifact-references.py --self-test` rejoués par `invariants` ; `check-single-execution.py` ignore les lignes `--self-test` (3 cas ajoutés à son auto-test) ; `check-audit-remediation-v2.py` n'interdit plus que l'exécution du gate JaCoCo sans `--self-test` (refus prouvé sur une copie). `python scripts/quality/check-ci-wiring.py` : 15 auto-tests câblés. |
| AUD-TST-08 | idem | **Fermé (local)** | idem | `check-jacoco.py` : scope `m29-backend-routing` en `report_of: minos-app`, rapport dérivé du POM (`<directory>` redirigé → `target/site/jacoco/jacoco.xml`) ; auto-test scénarios 9 à 11 ; exécution sur les rapports locaux : PASS ligne 0,914 / branche 0,676. |
| AUD-TST-13 | idem | **Fermé** (preuve CI du 2026-10-10, tâche 6.3) | idem | `python scripts/ci/test_plugin_gate.py` (19 tests) : fichier de `com/minos/output/`, golden, domaine → portée vraie ; `README.md` → fausse ; chaque préfixe existe. `intellij-plugin.yml` sans `paths:`, jobs `Plugin scope` et `IntelliJ plugin (gate)`. Sur GitHub : PR #393 (plugin dans la portée) gate verte avec les deux jobs exécutés ; sonde hors portée (run 38057621549) `plugin=false`, jobs sautés, gate verte. |
| AUD-DEP-09 | idem | **Fermé** (ruleset mis à jour par le propriétaire le 2026-10-10 ; `verify-ruleset.py` → code 0) | idem | `.github/required-checks.json`, `check-ci-wiring.py` (22 cas d'auto-test), `verify-ruleset.py` (14 cas). Lecture réelle du ruleset le 2026-10-10 : `verify-ruleset.py` → code 1, nomme exactement `Static invariants (single run)`, `Gitleaks`, `IntelliJ plugin (gate)`. À fermer par : ajout des trois contextes au ruleset (après un premier run), puis `verify-ruleset.py` → code 0. |
| AUD-ARC-10 | `durcir-les-gardes-d-architecture` | **Fermé (local)** | branche `ci/durcir-gardes-architecture` | `python scripts/architecture/test_check_module_boundaries.py` : 90 tests (13 avant), un cas refusé et un accepté par règle A2, mutations sur une copie des 14 POM réels, `EveryRuleHasATest` ; groupId interne lu dans le POM racine (`${…}` et groupe voisin refusés) ; règles de mise en page appliquées aux profils (la règle « tout `<build>` d'un profil » de l'audit a été écartée : 6 profils légitimes). Sortie du garde identique à l'ancienne version sur les lignes `M21 module-boundary` et `A3`, `--write-doc` sans diff. |
| AUD-ARC-03 | idem | **Fermé (local)** | idem | Règle A9 : rouge sur exactement les 7 modules du constat avant correction, vert après ; `arc42/05` corrigé (7 listes, `InMemoryCodeKnowledgeStore` sous `minos-engine`, note PostgreSQL), `SYNTHESE.md` passé en document historique, arêtes de dette déclarée ajoutées à `c4-container.md` et `arc42/05 § 5.1`. Non gardés (limites assumées) : prose de `SYNTHESE.md`, diagrammes de couches. |
| AUD-ARC-06 | `durcir-les-gardes-d-architecture` puis `casser-les-cycles-de-packages` | **Fermé** (PR #394 : garde A8 ; PR #398 : cycles levés, fusionnée le 2026-10-10) | `bf1a11ff` | Règle A8 : `python scripts/architecture/check-module-boundaries.py` → « 4 known cycles over 15 packages, none new (51 packages analysed, plugin included) » ; refus prouvé sur une copie des vraies sources (import réciproque ajouté, entrée retirée, entrée élargie). Les quatre cycles subsistent dans `KNOWN_PACKAGE_CYCLES`; leur levée relève de `casser-les-cycles-de-packages` (analyse dans le design : `ProjectResolver` + `DeterministicJson` lèvent le cycle de 8 packages). |

Écarts avec l'audit, constatés à l'analyse (voir `openspec/changes/rendre-visibles-les-tests-de-confinement-sautes/design.md`) :

- 26 sites de disponibilité répartis sur 9 classes (dont quatre classes Windows non citées), et non 7 ; 21 gardes de plateforme comme annoncé.
- Les journaux du run `pr-ci` 38008850294 montrent que ces tests s'exécutent déjà sur les runners GitHub (0 sauté) : la propriété ne rougit pas la CI actuelle.
- `LinuxCgroupJobOwnershipIsolationTest` n'avait aucune garde de plateforme : la conversion TST-06 est un préalable de TST-03.
- Le test `requiredModeIsActiveOnThePullRequestVerificationJob` est limité au job `verify` du workflow `PR Validation` (la variable `CI` est aussi définie dans les workflows de rejeu et d'audit, qui ne passent pas la propriété).

Preuve CI : [run 38054989409](https://github.com/FTurleque/minos-code-intelligence/actions/runs/38054989409) (`workflow_dispatch` de `pr-ci.yml` sur la branche, tête `258de1d4`), vert. Linux : les cinq classes de confinement rapportent 0 sauté, `SandboxTestSupportTest` 8/0 (le test propre au job `verify` s'exécute), `minos-runtime-local` 351 tests / 54 sautés. Windows : classes AppContainer 0 sauté, hors l'exemption nommée de `WindowsNonElevatedIndexingTest` (2/1), 351 tests / 43 sautés. Compteurs conformes aux prévisions (37 + 17 sous Linux, 39 + 4 sous Windows). AUD-DEP-01, AUD-PERF-08 et AUD-SEC-06 sont débloqués.

Remarque d'outillage : sur le poste, `mvnw.cmd` lancé depuis PowerShell 7 exige `powershell.exe` (Windows PowerShell 5.1) dans le `PATH` et un `PSModulePath` de Windows PowerShell (sinon `PrivateLocalStorageWindowsAclTest` échoue sur `Set-Acl`) ; les arguments `-D…` à point doivent être cités.

## Écarts constatés pendant `imposer-les-gates-et-leurs-auto-tests`

- AUD-DEP-09 : le ruleset exige déjà `Verify` ×2, OSV et SonarCloud ; trois contextes seulement manquent.
- AUD-TST-07 : l'action de l'audit (ajouter `check-jacoco.py --self-test` à `invariants`) cassait `check-single-execution.py` et `check-audit-remediation-v2.py`; les deux sont corrigés avec leurs tests. Le garde de jalons avait lui aussi un auto-test non rejoué.
- AUD-TST-08 : la redirection du `target/` de `minos-app` est conservée (14 consommateurs du jar racine) ; le rapport agrégé donne 0 % sur ces classes.
- Le format de `.github/required-checks.json` groupe les contextes par ruleset avec leurs branches (le design a été aligné).

## Écarts constatés pendant `durcir-les-gardes-d-architecture`

- AUD-ARC-10 : la règle « tout `<build>` dans un `<profile>` » aurait fait échouer six profils légitimes ; seuls `sourceDirectory`, `testSourceDirectory` et `includes`/`excludes` sont refusés dans un profil.
- AUD-ARC-06 : le cliquet est un garde Python (le plugin est hors reactor, le garde tourne en secondes dans `invariants`), pas une règle ArchUnit ; deux déplacements de classes lèveraient la plus grosse composante (simulation du design).
- AUD-ARC-03 : le contrôle est dans `check-module-boundaries.py` (qui détient déjà le graphe des POM) et non dans `check-current-docs.py`.

## `casser-les-cycles-de-packages` (PR #398)

- 14 classes déplacées dans leur module (`ProjectResolver` → `application.resolution`, `DeterministicJson` → `output.json`, 6 classes d'`incremental` → `orchestration`, 4 interfaces SPI → `discovery`, `MinosRegistryNotice` → `intellij.protocol`) ; `KNOWN_PACKAGE_CYCLES` est vide : « A8 package cycles: 0 known cycles over 0 packages ».
- Preuve CI de la PR : `Verify` Ubuntu et Windows verts, `check-jacoco.py` (scope `project-resolution` sur le nouvel emplacement), `IntelliJ Plugin Validation` (plugin compilé et testé), goldens de caractérisation inchangés.
- Découvertes : `DuplicationGuardTest` et `JsonEscapeGuardTest` citaient le chemin de `DeterministicJson` ; le baseline SpotBugs est indexé par `instanceHash`, qui dépend du nom de classe (5 empreintes rafraîchies) ; SonarCloud compte une classe déplacée comme du code neuf et mesure la couverture module par module (69,1 % < 80 %) : tests unitaires ajoutés dans `minos-application` (14 cas) et `minos-engine` (6 cas), 80,2 % ensuite.
- Reste : les cases 4.3 et 5.2 de `openspec/changes/casser-les-cycles-de-packages/tasks.md` n'ont pas été cochées après la fusion (preuves ci-dessus) ; à faire à l'archivage.

## Archivage OpenSpec (2026-10-10)

Les quatre changements du sprint 1 sont archivés (`openspec/changes/archive/2026-10-10-*`) et leurs exigences fusionnées dans `openspec/specs/` : `controles-de-fusion` (8 exigences, nouvelle), `frontieres-de-modules` (13, nouvelle), `confinement-code-non-fiable` (+4). Les « Purpose » provisoires des deux capacités créées ont été remplacés. Restent actifs, hors sprint : `etendre-audit-outille-a-tout-le-perimetre` (12/13) et `aligner-dependances-de-test-testcontainers` (7/8).

## Sprint 2 — Failles de sécurité et observabilité (spécifié le 2026-10-10, non implémenté)

Quatre changements OpenSpec, branche locale `docs/sprint2-openspec` (HEAD de départ `465e970d`) ; aucun code, aucun test, aucun workflow modifié. Chaque changement est fusionnable seul.

| Constat | Changement OpenSpec | Statut | Écart avec l'audit constaté à l'analyse |
|---|---|---|---|
| AUD-SEC-01 | `ouvrir-le-depot-git-sans-executer-ses-filtres` | **Spécifié** | Preuve complétée sur JGit **7.8.0** (sources du dépôt Maven local, 971 fichiers) : `WorkingTreeIterator.java:1433` / `:483-496` exécute le filtre `clean` par `sh -c` ou `cmd.exe /c` ; aucune occurrence de `safe.directory`. L'action « ouvrir le dépôt exactement » **supprime la remontée** (monorepos, worktrees, sous-modules) : remplacée par propriétaire + retrait des filtres du dépôt (le propriétaire seul ne protège pas un `.git` livré dans une archive). Décisions en attente : D1 (remontée), D3 (filtres du dépôt seulement ou tous). |
| AUD-DEP-08 | `rendre-la-journalisation-observable` | **Spécifié** | **L'action de l'audit est inversée** : `slf4j-jdk-platform-logging` redirige `System.Logger` vers SLF4J ; le sens utile est `slf4j-jdk14` (SLF4J vers `java.util.logging`, stderr). Vérification par `javap` du jar prévue en tâche 1.1 avant toute modification du POM. |
| AUD-QUA-04 | idem | **Spécifié** | Quatre `catch (Throwable)` et non trois (`MinosEditorActions:69` et `:83`, `MinosM21Actions:204`, `MinosToolWindowPanel:280`). Décision en attente D2-a : message de l'exception dans `idea.log` (le critère de sortie du sprint dit « sans chemin absolu »). |
| AUD-QUA-06 | idem | **Spécifié** | Relevé du HEAD : 5 sites de récupération (dont `ProviderResidueReclamation:80-81`, non cité) + 4 diagnostics de bac à sable qui passent la cause **à dessein** + 2 `getMessage()`. Une interdiction générale de la cause contredirait cette décision : garde à deux règles (`Path` concaténé sans exception ; cause/`getMessage()` par liste nominative de 6 entrées). Décision en attente D3-a (étendre `check-private-io.py` ou script dédié). |
| AUD-SEC-02 | `epingler-et-isoler-l-installation-des-providers` | **Spécifié** | Défaut d'environnement plus large : `npm ci` (×2), `dotnet tool install`, `go install` et les sondes `dotnet`/`go` héritent aussi de l'environnement complet. `applyForTrustedLauncher` est une liste **Windows**, package-private, sans `HOME` : remplacée par une façade publique fondée sur la liste des providers. Le plan d'indexation Linux relance encore `coursier launch` (hors changement, D2). Décisions en attente : D1 (verrou par jar, recommandé, ou zip assemblé), D2, D3. Amende l'ADR 0040. |
| AUD-SEC-12 | `durcir-secret-postgres-et-lanceur-de-developpement` | **Spécifié** | Confirmé dans les deux scripts. Observation hors périmètre : l'installateur Inno écrit le mot de passe source dans `{tmp}` (privé à l'utilisateur) puis le supprime. Décision en attente D2 (corriger en place, recommandé, ou fichier partagé). |
| AUD-SEC-13 | idem | **Spécifié** | « Plausible » devient **reproduit** : `java.bat` du répertoire courant exécuté par `cmd.exe` quand `NoDefaultCurrentDirectoryInExePath` est absente. **Piège** : cette variable vaut déjà `1` dans l'environnement du poste, ce qui masque le défaut ; le test doit la retirer. Correctif mesuré : `set NoDefaultCurrentDirectoryInExePath=1` dans le lanceur. `where` n'est pas retenu (il cherche aussi dans le répertoire courant). |

Preuve de la spécification : `openspec validate --all --strict` → 18 passés, 0 échec.

Ordre d'implémentation proposé : `ouvrir-le-depot-git-sans-executer-ses-filtres` (gravité élevée) puis `epingler-et-isoler-l-installation-des-providers` (lot 1 d'abord), puis `rendre-la-journalisation-observable` (lots 1 à 3 séparables), enfin `durcir-secret-postgres-et-lanceur-de-developpement`.
