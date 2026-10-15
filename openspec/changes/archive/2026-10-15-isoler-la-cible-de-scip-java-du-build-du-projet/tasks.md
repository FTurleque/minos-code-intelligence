# Tasks

Ordre dans chaque section : rouge, correction, preuve. Tout `-pl` s'accompagne de `-am` ; Maven avec `JAVA_HOME` sur un JDK 25.

## 1. Cible des fichiers intermédiaires

- [x] 1.1 Rouge : dans `minos-provider-scip`, les tests des deux commandes (`ScipJavaProcessPlanFactoryCommandTest`, `ScipJavaClasspathTest`) exigent `--targetroot <run>/scip-java-targetroot` (D1, D2) ; un test du runner Windows exige `--targetroot` dans la branche Java (D3). **Preuve du rouge (2026-10-15, HEAD `74d85e4f`)** : `-pl minos-provider-scip -am test -Dtest=ScipJavaProcessPlanFactoryCommandTest,ScipJavaClasspathTest` échoue à la compilation (5 erreurs : `targetRoot` absent, `classpathCommand` et `standaloneCommand` sans paramètre de cible). Pas de rouge d'exécution : les commandes n'avaient pas de cible à vérifier.
- [x] 1.2 Correction : `ScipJavaProcessPlanFactory` (D1, D2) et `scip-java-windows-runner.ps1` (D3). Preuve : tests verts ; `./mvnw -B -ntp -pl minos-provider-scip -am test` ; `python scripts/remediation/check-p0-p2.py` et `check-post-mne.py` ; `python .claude/scripts/run_gates.py --fast`. **Preuve (2026-10-15)** : `ScipJavaProcessPlanFactory.targetRoot` (`<run>/scip-java-targetroot`), les deux commandes terminées par `--targetroot <cible>`, branche Java du runner Windows alignée sur la branche Kotlin ; `./mvnw -B -ntp -pl minos-provider-scip -am test -Dtest=com.minos.adapter.scip.**` : 357 tests, 0 échec, 11 sautés ; `check-p0-p2.py`, `check-post-mne.py` verts ; `run_gates.py --fast` 20/20. Le runner Windows n'a pas été exécuté sur une indexation réelle Windows (la CI n'exécute aucun provider).

## 2. Preuve sur un index réel

- [x] 2.1 Indexation réelle de ce dépôt sous Linux (bubblewrap et cgroup v2, JDK 25), **sans retoucher `minos-app/pom.xml`**, sur le code de production du reactor : l'index couvre les 14 modules. À comparer avec le HEAD de départ, où il ne couvre que `minos-app`. **Preuve (2026-10-15)** : conteneur Ubuntu 24.04 jetable, JDK 25.0.4.1, scip-java de l'image 1.2.0 sous bubblewrap et cgroup v2 délégué, Maven hors ligne ; corpus : le reactor au HEAD `74d85e4f` sans `src/test` (532 fichiers), `minos-app/pom.xml` **intact** (surcharge `<directory>` présente). CLI de `develop` (`74d85e4f`) : symboles du projet par module `{minos-app=173}`, seul module indexé. CLI de cette branche : 14 modules, 28 149 symboles (`minos-api` 1 929, `minos-app` 173, `minos-application` 5 972, `minos-bootstrap` 105, `minos-cli` 2 277, `minos-domain` 627, `minos-engine` 6 403, `minos-integration-git` 677, `minos-mcp` 741, `minos-nexus` 334, `minos-provider-scip` 2 145, `minos-runtime-local` 2 764, `minos-storage-local` 2 673, `minos-storage-postgresql` 1 329).

## 3. Clôture

- [x] 3.1 `docs/audit/2026-10-10/SUIVI.md` (constat 2 de la clôture du sprint 4 : corrigé) ; `docs/STATUS.md` si la limite y est citée. **Fait (2026-10-15)** : constat 2 marqué corrigé dans SUIVI ; phrase de limites de la section sprint 4 de STATUS mise à jour.
- [x] 3.2 `openspec validate --all --strict` ; archivage après fusion. **Fait (2026-10-15)** : PR #462 fusionnée (`7cda51d4`), CI verte (dont `Verify (ubuntu-24.04)`, `Verify (windows-2022)`, SonarCloud : 100 % de couverture du nouveau code, 0 problème) ; `openspec validate --all --strict` vert avant archivage.
