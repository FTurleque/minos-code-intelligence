## 1. Contrat commun de l'état d'indexation

- [ ] 1.1 (E01, D3) Écrire la classe abstraite `com.minos.orchestration.IndexStateStoreContract` dans `minos-engine/src/test` (4 cas du design). L'étendre avec `FileIndexStateStoreContractTest` dans `minos-storage-local`. **Preuve** : `./mvnw -B -ntp -pl minos-storage-local -am test "-Dtest=FileIndexStateStoreContractTest" "-Dsurefire.failIfNoSpecifiedTests=false"` est vert. Le backend local respecte déjà le contrat.
- [ ] 1.2 (E01, D3) **Rouge.** `PostgresIndexStateStoreContractTest` (`minos-storage-postgresql`, sous-classe de `PostgresTestSupport`) échoue sur les cas 1 et 4 : l'offre relue est vide. Prouver le rouge sous Docker, avec `"-Dminos.postgresql.tests.required=true"`.

## 2. Migration v5 et magasin PostgreSQL

- [ ] 2.1 (E01, D1, D2) **Correction.** Dans `PostgresSchemaMigrator`, passer `CURRENT_VERSION` à 5 et ajouter `applyV5`. Dans `PostgresIndexStateStore`, lire et écrire `resumable_run_id`, puis construire l'état avec le constructeur à 7 arguments. Mettre à jour `PostgresSchemaUpgradeFromV1Test`, qui attend maintenant les versions 1 à 5. Ajouter un cas de montée depuis une base v4 peuplée. **Preuve** : `./mvnw -B -ntp -pl minos-storage-postgresql -am test "-Dminos.postgresql.tests.required=true"` est vert, contrat compris. Le refus d'un schéma plus récent reste vert.

## 3. Rétention

- [ ] 3.1 (E01, D4) **Rouge.** Écrire un test PostgreSQL : avec `maxNonSucceededRuns = 0`, le run `INTERRUPTED` offert est supprimé par `compact`. Il doit échouer avant la correction. Écrire le cas symétrique côté local, qui est vert.
- [ ] 3.2 (E01, D4) **Correction.** Dans `deleteHistoricalRuns`, la sous-requête `NOT EXISTS` devient `p.latest_run_id=target.id OR p.resumable_run_id=target.id`. **Preuve** : les deux tests de rétention sont verts, ainsi que le cas « run non offert supprimé ».

## 4. Documentation

- [ ] 4.1 (E01) Ajouter à l'ADR 0039 une note de mise en œuvre : l'offre de reprise est persistée par les deux backends et protégée par leur rétention. Dans `docs/developer/quality/code-audit-couverture.md:64`, passer `CURRENT_VERSION` de 4 à 5. Ajouter la ligne de note de version : une base PostgreSQL migrée en v5 est refusée par une version antérieure de MINOS.

## 5. Clôture

- [ ] 5.1 Lancer `python .claude/scripts/run_gates.py`, en particulier `check-p0-p2.py`, puis `check-jacoco.py` (portée `m30-postgresql-pgvector`) sur le rapport Linux, `openspec validate --all --strict` et `archgate check`. Lancer `./mvnw -B -ntp verify` sous Windows et laisser la CI Linux exiger PostgreSQL.
- [ ] 5.2 Dans `docs/project/audits/2026-10-10/SUIVI.md`, passer E01 à « corrigé » dans la section de la phase 4. `ETAT-2026-10-13.md` et `findings.json` ne sont pas modifiés. Revue de sécurité du stockage PostgreSQL : aucune donnée sensible dans la colonne, et une requête paramétrée.
