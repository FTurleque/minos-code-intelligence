# Tasks

Ordre dans chaque section : rouge, correction, preuve. Chaque tâche retire ses entrées de `scripts/architecture/private-io-allowlist.json` (cliquet) et rejoue `python scripts/architecture/check-private-io.py`. Tests dans `minos-application`, dans la même tâche que le code. Aucun prérequis de série.

## 1. Historique d'indexation (AUD-SEC-03)

- [x] 1.1 [AUD-SEC-03] Rouge : un test de `LocalProjectOperations.writeHistory` (point d'accès package-private ajouté avec le test, ou test par la CLI du module si la visibilité l'interdit) — (a) octets identiques à ceux d'aujourd'hui pour un `providerVersion` non ASCII et un `providerVersion` nul, la ligne de commentaire datée de `Properties.store` étant fixée (`java.properties.date`) ou écartée ; (b) un lien symbolique substitué au temporaire est refusé, la cible du lien est intacte ; (c) le fichier final est privé. **Preuve du rouge : (b) l'écriture actuelle suit le lien.**
- [x] 1.2 [AUD-SEC-03] `Properties.store` vers un `ByteArrayOutputStream` puis `PrivateLocalStorage.writePrivateFile` (D2) ; retirer l'entrée `LocalProjectOperations` / `Files.newOutputStream`. Preuve : `./mvnw -B -ntp -pl minos-application -am test`, `check-private-io.py` vert (baisse de 1).

## 2. Lecture des sources du projet (AUD-SEC-03)

- [x] 2.1 [AUD-SEC-03] Rouge : dans `JavaSecurityConfigConfinementTest` et `ProgramGraphSourceStateReuseTest`, (a) une source dont le leaf devient un lien symbolique entre la résolution (`toRealPath`) et la lecture est refusée par l'empreinte (`FingerprintConstrainedJavaProgramGraphProvider`) et par `JavaSourceWorkspace` (point d'injection package-private) ; (b) une source ordinaire donne la même empreinte qu'avant (fixture capturée) ; (c) un fichier qui grossit pendant le hachage garde le message « grew while being fingerprinted ». **Preuve du rouge : (a) la lecture actuelle suit le lien.**
- [x] 2.2 [AUD-SEC-03] `sha256Exact` et `updateBounded` ouvrent par `ConfinedFileOpener.openRegularFileNoFollow` enveloppé dans `BoundedInputStream` (D3), sans paramètre `OpenOption` ; `sha256Exact`, ses messages et `MAX_SECURITY_CONFIG_BYTES` restent. Retirer les entrées `FingerprintConstrainedJavaProgramGraphProvider` et `JavaSourceWorkspace` / `Files.newInputStream`. Preuve : tests du module et de `minos-bootstrap` (`MinosApplicationTest`) verts, `python scripts/remediation/check-mne.py`, `check-mnd.py`, `check-p0-p2.py` verts.
- [x] 2.3 [AUD-SEC-03] **(manuelle)** Mesurer l'empreinte d'un projet de plusieurs milliers de fichiers Java avant et après (`ProgramGraphPerformanceQualificationTest` ou la CLI) ; seuil d'abandon : plus de 5 % de plus. **Mesuré (voir `design.md`, « Constat de mise en œuvre ») : +85 % sous Windows, +31 % sous Linux sur le hachage seul de 5 000 fichiers, au-delà du seuil d'abandon de 5 % ; surcoût accepté par le propriétaire le 2026-10-20 (fusion de la PR #509).**

## 3. Gates et couverture (AUD-SEC-03)

- [x] 3.1 [AUD-SEC-03] Rejouer `python scripts/quality/check-java-ast-provider-consistency.py`, `python scripts/quality/check-vertical-decomposition-consistency.py`, `python scripts/architecture/test_check_private_io.py -v`, `python .claude/scripts/run_gates.py --fast`.
- [x] 3.2 [AUD-SEC-03] `./mvnw -B -ntp -pl minos-application -am verify` puis `python scripts/quality/check-jacoco.py` (scopes `program-graph-analysis`, `java-advanced-provider`, `global`) ; aucun plancher abaissé. **Preuve de CI : `check-jacoco.py` exécuté sur `Verify (ubuntu-24.04)`, tous les scopes PASS, aucun plancher abaissé (run 39165629467, PR #510 ; `Verify` vert sur chaque PR de la série).**

## 4. Clôture

- [x] 4.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (lot « application et registre », partie `minos-application` close ; deux entrées anciennes retirées). **Faite dans la PR de clôture de la migration.**
- [x] 4.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : aucune au-delà du suivi. **Vérifié : `openspec validate --all --strict` vert.**
- [x] 4.3 Baseline SpotBugs : relire `quality/spotbugs-baseline/minos-application.xml` pour les méthodes réécrites (comparaison par `instanceHash` dans `code-audit.yml`, qui n'est pas un check exigé) ; régénérer la baseline si une entrée disparaît ou change de place. **Relevé (2026-10-20) : `code-audit.yml` (SpotBugs seul) sur `develop` (run 39483649606) comparé au commit d'avant la migration (run 39484725127) : la série ajoute 2 alertes `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (`ManagedPolyglotScipRuntimeManager`, `LocalProjectRegistry`), corrigées dans la PR de clôture, en retire 1 (`REC_CATCH_EXCEPTION` de `ProcessIndexerExecutor`) et décale 1 `ENV_USE_PROPERTY_INSTEAD_OF_ENV`. La base figée n'est pas modifiée : le reste de l'écart de `develop` à la base (22 nouvelles, 3 disparues) précède la série ou vient d'autres changements (`InMemoryIndexStateStore` `UL_UNRELEASED_LOCK`, `PostgresConnectionFactory` ×3), à traiter à part.**
