# Tasks

Ordre : mesure, déplacement, preuve. Commande de référence : `./mvnw.cmd -B -ntp -pl minos-runtime-local -am test` (outillage Windows : `docs/audit/2026-10-10/SUIVI.md`).

## 1. Déplacement

- [x] 1.1 Rejouer l'analyse des dépendances du découpage D1 (aucun cycle, liste des types à rendre publics) et la consigner si elle diffère de `design.md`.
- [x] 1.2 Déplacer les 31 classes et leurs tests par `git mv` (historique conservé), réécrire les `package` et les imports (dans `minos-bootstrap`, `minos-cli`, `minos-provider-scip`, `minos-app`) ; les huit classes de la racine, `META-INF/services` et les ressources ne bougent pas.
- [x] 1.3 Élargir la visibilité au strict nécessaire (D2), guidé par le compilateur ; consigner le nombre de types et de membres rendus publics.

## 2. Preuve

- [x] 2.1 `./mvnw.cmd -B -ntp -pl minos-runtime-local -am test` : même nombre de tests exécutés et sautés qu'avant le déplacement.
- [x] 2.2 `./mvnw.cmd -B -ntp -pl minos-app -am test-compile` : tous les modules qui importent des classes déplacées compilent, tests compris.
- [x] 2.3 Rejouer `python .claude/scripts/run_gates.py --fast` (dont `check-module-boundaries.py` A3 et A8, `check-private-io.py`, `check-jacoco.py`, les gates `remediation` et de cohérence qui citent le module), mettre à jour les chemins littéraux.
- [x] 2.4 Régénérer le baseline SpotBugs du module (`-Paudit-spotbugs`) ; une alerte nouvelle se corrige ou se consigne, avec sa raison dans `design.md`.
- [x] 2.5 Mesurer la couverture JaCoCo lignes + conditions des fichiers déplacés ; ajouter dans le module les tests nécessaires pour tenir le seuil de 80 % de SonarCloud.
- [x] 2.6 Faire relire le diff par l'agent `minos-security-reviewer` (visibilité des primitives de confinement et d'environnement) et traiter ses constats.
- [x] 2.7 Mettre à jour `docs/architecture/arc42/05-vue-blocs.md` (sous-packages du module) ; rejouer `python scripts/docs/check-current-docs.py`.

## 3. Preuve sur les deux plateformes

- [x] 3.1 **(manuelle)** Relever sur `Verify (ubuntu-24.04)` et `Verify (windows-2022)` que les tests de confinement s'exécutent (aucun saut silencieux) et que `SonarCloud Code Analysis` passe. **Preuve (2026-10-13)** : run `pr-ci` 38316438452 (`develop`, `808938e5`) : sous Ubuntu `LinuxBubblewrapWorkerSandboxBackendTest` 4/0 sauté, `LinuxCgroupJobOwnershipIsolationTest` 10/0, `WindowsAppContainerWorkerSandboxBackendTest` 15/14 (propres à Windows, comptés) ; sous Windows 15/0, les classes Linux 4/4 et 10/10 ; `SandboxTestSupportTest` 8/0 des deux côtés. `SonarCloud Code Analysis` vert sur les PR #416 (`f3a03c19`) et #426 (`677f3e89`).

## 4. Clôture

- [x] 4.1 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (rangement des modules : module, PR, types rendus publics, écarts).
- [x] 4.2 `openspec validate --all --strict` ; lister les `docs/` à mettre à jour avant archivage.

## 5. Complément du 2026-10-12 : classes propres à Windows

Rendu possible par la PR #421 (SonarCloud importe aussi la couverture du job Windows) ; demandé par le propriétaire le 2026-10-12.

- [x] 5.1 Vérifier que le déplacement de `WindowsAppContainerWorkerSandboxBackend`, `SandboxLauncherScript`, `WindowsJobObjectProcessOwnership`, `WindowsExecutionPathIdentityProvider` et `WorkerSandboxBackends` dans `sandbox` ne crée aucun cycle, puis les déplacer avec leurs tests (`git mv`), réécrire `META-INF/services` et les chemins littéraux.
- [x] 5.2 Élargir la visibilité au strict nécessaire et **refermer** ce que le rangement partiel avait ouvert pour ces classes ; ajouter `sandbox.WorkerSandboxBackends` à `RUNTIME_LOCAL_EXPORTED_TYPES` (règle A10), les autres modules le nommaient déjà.
- [x] 5.3 Preuve : `./mvnw.cmd -B -ntp -pl minos-runtime-local -am test` (mêmes nombres qu'avant), `./mvnw.cmd -B -ntp -pl minos-app -am test-compile`, `python .claude/scripts/run_gates.py`, baseline SpotBugs régénéré.
- [x] 5.4 **(manuelle)** Relever sur la PR que `SonarCloud Code Analysis` mesure la couverture de ces classes à partir des deux rapports (preuve de la fusion fichier par fichier laissée ouverte par la PR #421). **Preuve (2026-10-13)** : PR #426 (après le correctif #430) : mesure par fichier de l'API SonarCloud, `WindowsAppContainerWorkerSandboxBackend` 78,5 % (exécutée par le seul job Windows), `LinuxBubblewrapWorkerSandboxBackend` 83,9 % (seul job Linux), nouveau code 84,2 % : les deux rapports se cumulent fichier par fichier.
