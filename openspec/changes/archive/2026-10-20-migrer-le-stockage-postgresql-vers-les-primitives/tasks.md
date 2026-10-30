# Tasks

Ordre : rouge, correction, preuve. Exige `completer-les-primitives-d-e-s-privees`.

## 1. Fichiers de travail du magasin PostgreSQL (AUD-SEC-03)

- [x] 1.1 [AUD-SEC-03] Rouge : dans `PostgresCodeKnowledgeSnapshotStoreTest`, un test où le fichier de travail est remplacé par un lien symbolique vers un fichier sentinelle entre `createScratchFile` et l'écriture (point d'injection de package-private sur `createScratchFile`, ajouté avec le test), pour la lecture de l'actif et pour `requireSameContent` : l'opération échoue, la sentinelle est intacte, le fichier de travail est supprimé. **Preuve du rouge : l'écriture actuelle suit le lien.**
- [x] 1.2 [AUD-SEC-03] Remplacer les deux `Files.newOutputStream` par `PrivateLocalStorage.openPrivateOutputStream` ; retirer l'entrée `PostgresCodeKnowledgeSnapshotStore` / `Files.newOutputStream` de `scripts/architecture/private-io-allowlist.json`. Preuve : `./mvnw -B -ntp -pl minos-storage-postgresql -am test` (Docker requis), `python scripts/architecture/check-private-io.py` vert (`allowlisted-occurrences` baisse de 2).
- [x] 1.3 [AUD-SEC-03] **(manuelle)** Sur un poste sans Docker, lire le run `Verify (ubuntu-24.04)` de la PR : le scope `m30-postgresql-pgvector` de `python scripts/quality/check-jacoco.py` y est évalué ; aucun seuil abaissé. **À relever sur la CI de la PR.** **Preuve de CI : `check-jacoco.py` exécuté sur `Verify (ubuntu-24.04)`, tous les scopes PASS, aucun plancher abaissé (run 39165629467, PR #510 ; `Verify` vert sur chaque PR de la série).**
- [x] 1.4 [AUD-SEC-03] Rejouer `python scripts/remediation/check-mnd.py`, `python scripts/remediation/check-mne.py`, `python scripts/architecture/test_check_private_io.py -v`, `python .claude/scripts/run_gates.py --fast`.

## 2. Clôture

- [x] 2.1 Mettre à jour `docs/project/audits/2026-10-10/SUIVI.md` (lot « stockage PostgreSQL » clos). **Faite à la clôture de la série.**
- [x] 2.2 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : aucune au-delà du suivi.
- [x] 2.3 Baseline SpotBugs : relire `quality/spotbugs-baseline/minos-storage-postgresql.xml` pour les méthodes réécrites (comparaison par `instanceHash` dans `code-audit.yml`, qui n'est pas un check exigé) ; régénérer la baseline si une entrée disparaît ou change de place. **Non rejoué en local ; à relire avec le prochain `code-audit.yml`.** **Relevé (2026-10-20) : `code-audit.yml` (SpotBugs seul) sur `develop` (run 39483649606) comparé au commit d'avant la migration (run 39484725127) : la série ajoute 2 alertes `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` (`ManagedPolyglotScipRuntimeManager`, `LocalProjectRegistry`), corrigées dans la PR de clôture, en retire 1 (`REC_CATCH_EXCEPTION` de `ProcessIndexerExecutor`) et décale 1 `ENV_USE_PROPERTY_INSTEAD_OF_ENV`. La base figée n'est pas modifiée : le reste de l'écart de `develop` à la base (22 nouvelles, 3 disparues) précède la série ou vient d'autres changements (`InMemoryIndexStateStore` `UL_UNRELEASED_LOCK`, `PostgresConnectionFactory` ×3), à traiter à part.**
