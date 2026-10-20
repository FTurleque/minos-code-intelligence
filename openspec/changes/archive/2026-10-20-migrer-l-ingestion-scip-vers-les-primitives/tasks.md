# Tasks

Ordre : rouge, correction, preuve. Exige `completer-les-primitives-d-e-s-privees`. Tests dans `minos-provider-scip`, dans la même tâche que le code.

## 1. Lecture de l'artefact (AUD-SEC-03)

- [x] 1.1 [AUD-SEC-03] Rouge : dans `ScipIndexReaderTest` et `ScipSymbolSnapshotImporterIntegrityTest`, un index remplacé par un lien symbolique entre le contrôle de chemin et l'ouverture (point d'injection de package, comme le hook `beforeOpen` de `ConfinedFileOpener`) est refusé ; un fichier spécial est refusé ; la taille lue sur le descripteur ouvert fait foi (un fichier qui grossit entre le contrôle et la lecture est refusé par la borne). **Preuve du rouge : le remplacement par un lien est suivi.** **Rouge par compilation seulement (les coutures de test n'existaient pas) : le remplacement par un lien n'était déjà pas suivi au HEAD (`NOFOLLOW_LINKS` à l'ouverture), les tests sont donc des gardes.**
- [x] 1.2 [AUD-SEC-03] `ScipIndexReader` et `freeze` (source) ouvrent par `ConfinedFileOpener.openRegularFileNoFollowChannel` ; les contrôles de chemin et leurs messages restent (D2). Preuve : `./mvnw -B -ntp -pl minos-provider-scip -am test`, `ScipIndexReaderPreflightTest` et `ScipSymbolSnapshotImporterBehaviorTest` verts sans modification.

## 2. Index figé (AUD-SEC-03)

- [x] 2.1 [AUD-SEC-03] Rouge : un fichier figé remplacé par un lien symbolique avant l'écriture est refusé, la sentinelle est intacte, le fichier figé est supprimé (`finally`, `:162`). **Preuve du rouge : l'écriture actuelle suit le lien si l'ouverture n'est pas `NOFOLLOW` ; sinon le test est conservé comme garde et le rouge est la compilation du point d'injection.**
- [x] 2.2 [AUD-SEC-03] Remplacer le canal d'écriture par `PrivateLocalStorage.openPrivateOutputStream` (D3) ; retirer les entrées `ScipIndexReader` / `Files.newByteChannel` et `ScipSymbolSnapshotImporter` / `Files.newByteChannel` de `scripts/architecture/private-io-allowlist.json`. Preuve : tests du module verts, `python scripts/architecture/check-private-io.py` vert (`allowlisted-occurrences` baisse de 3).

## 3. Gates et couverture (AUD-SEC-03)

- [x] 3.1 [AUD-SEC-03] Rejouer `python scripts/architecture/test_check_private_io.py -v`, `python scripts/quality/check-remote-distributed-consistency.py`, `python scripts/quality/check-polyglot-provider-consistency.py`, `python .claude/scripts/run_gates.py --fast`.
- [x] 3.2 [AUD-SEC-03] `./mvnw -B -ntp -pl minos-provider-scip -am verify` puis `python scripts/quality/check-jacoco.py` (scope `global` ; le scope `m24-polyglot-provider-platform` est rouge sous Windows hors régression : comparer à `develop`). **Module et amonts : 369 tests, 0 échec ; JaCoCo à relever sur la CI de la PR.** **Preuve de CI : `check-jacoco.py` exécuté sur `Verify (ubuntu-24.04)`, tous les scopes PASS, aucun plancher abaissé (run 39165629467, PR #510 ; `Verify` vert sur chaque PR de la série).**

## 4. Clôture

- [x] 4.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (lot « providers SCIP », partie ingestion close). **Faite à la clôture de la série.**
- [x] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : aucune au-delà du suivi.
- [x] 4.3 Baseline SpotBugs : relire `quality/spotbugs-baseline/minos-provider-scip.xml` pour les méthodes réécrites (comparaison par `instanceHash` dans `code-audit.yml`, qui n'est pas un check exigé) ; régénérer la baseline si une entrée disparaît ou change de place. **Non rejoué en local ; à relire avec le prochain `code-audit.yml`.** **Relevé (2026-10-20) : `code-audit.yml` (SpotBugs seul) sur `develop` (run 39483649606) comparé au commit d'avant la migration (run 39484725127) : la série ajoute 2 alertes `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (`ManagedPolyglotScipRuntimeManager`, `LocalProjectRegistry`), corrigées dans la PR de clôture, en retire 1 (`REC_CATCH_EXCEPTION` de `ProcessIndexerExecutor`) et décale 1 `ENV_USE_PROPERTY_INSTEAD_OF_ENV`. La base figée n'est pas modifiée : le reste de l'écart de `develop` à la base (22 nouvelles, 3 disparues) précède la série ou vient d'autres changements (`InMemoryIndexStateStore` `UL_UNRELEASED_LOCK`, `PostgresConnectionFactory` ×3), à traiter à part.**
