# Tasks

Ordre : relevé, puis par section rouge, correction, preuve. Tout `-pl` s'accompagne de `-am`. Aucun code de production n'est modifié ; la neutralisation d'un verrou pour la preuve du rouge est locale et jamais commitée. Dire, pour chaque preuve PostgreSQL, si elle a tourné localement (Docker) ou seulement en CI.

## 1. Relevé (AUD-TST-09, AUD-TST-10)

- [ ] 1.1 [AUD-TST-10] Classer les 48 `Thread.sleep` des 33 fichiers de test : preuve d'attente (14 sites attendus, table de `design.md`), simulation de durée, attente active bornée. Pour chaque site de preuve, confirmer la nature du verrou (JVM, fichier, entre JVM, consultatif PostgreSQL) en lisant la classe testée. Consigner dans `design.md`.
- [ ] 1.2 [AUD-TST-09] Relever les tables écrites par `PostgresCodeKnowledgeSnapshotStore` et `PostgresFingerprintSnapshotStore`, et le comportement d'une seconde publication à contenu différent sous le même identifiant (non spécifié ici ; le consigner).

## 2. Idempotence relue (AUD-TST-09)

- [ ] 2.1 [AUD-TST-09] Rouge : ajouter aux trois tests la relecture attendue (D4), puis neutraliser localement le contrôle d'existence de chaque publication et la garde de version de la migration. **Preuve du rouge : les trois tests échouent sur la copie neutralisée ; au HEAD, sans relecture, ils restent verts.**
- [ ] 2.2 [AUD-TST-09] `PostgresCodeKnowledgeSnapshotStoreTest`, `PostgresFingerprintSnapshotStoreTest`, `PostgresSchemaMigratorTest` : nombre de lignes et contenu rechargé après le second appel ; `schema_version` égale à `1..CURRENT_VERSION`. Preuve : `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` ; scope JaCoCo `m30-postgresql-pgvector` non abaissé.

## 3. Verrous consultatifs PostgreSQL (AUD-TST-10)

- [ ] 3.1 [AUD-TST-10] Rouge : aide de test « attendre un verrou consultatif non accordé » (`pg_locks`, filtrée sur la clé du projet, avec échéance) et son propre test : échéance atteinte sans verrou en attente → échec. Réécrire les six sites (`ProjectMutationIndexStateStoreTest` `:59`, `:113`, `:154`, `PostgresRetentionMutationLockTest:53`, `PostgresSemanticSyncConsistencyTest:96`, `PostgresProjectMutationLockIntegrationTest:58`). **Preuve du rouge : verrou neutralisé localement, chaque test échoue (D2).**
- [ ] 3.2 [AUD-TST-10] Rejouer 50 fois chaque test réécrit (D3). Preuve : 50 sur 50 ; `./mvnw -B -ntp -pl minos-storage-postgresql -am verify` ; scope JaCoCo `m30-postgresql-pgvector`.

## 4. Verrous de fichier et entre JVM (AUD-TST-10)

- [ ] 4.1 [AUD-TST-10] Réécrire `LocalStorageRetentionLockTest:77`, `FileHostedControlPlaneStoreLockTest:74`, `ProjectIndexLeaseTest:37`, `FileSemanticSyncConsistencyTest:265` et `JGitRemoteRepositoryMaterializerLockTest:72` : loquet avant l'appel bloquant, attente bornée d'un signal positif attendu absent (D1). **Preuve du rouge : verrou neutralisé localement, chaque test échoue.**
- [ ] 4.2 [AUD-TST-10] Rejouer 50 fois chaque test, sur Windows et sur Linux (dire lequel n'a tourné qu'en CI). Preuve : `./mvnw -B -ntp -pl minos-storage-local -am verify`, `./mvnw -B -ntp -pl minos-integration-git -am verify` ; scopes JaCoCo `persistence-cache-indexes`, `semantic-vector-store`, `m27-team-hosted-control-plane`, `m25-remote-distributed-indexing`.

## 5. Baux de projet dans la JVM (AUD-TST-10)

- [ ] 5.1 [AUD-TST-10] Réécrire `ProjectMutationSemanticVectorStoreTest:76`, `IndexingLifecycleServiceTest:118` et `IncrementalIndexingCoordinatorTest:104` : concurrent observé en attente (file du verrou ou état du thread, avec échéance) avant l'assertion négative. **Preuve du rouge : bail neutralisé localement, chaque test échoue.**
- [ ] 5.2 [AUD-TST-10] Rejouer 50 fois. Preuve : `./mvnw -B -ntp -pl minos-engine -am test`, `./mvnw -B -ntp -pl minos-bootstrap -am verify`, `./mvnw -B -ntp -pl minos-storage-local -am verify` ; scopes JaCoCo `critical-orchestration`, `resume-orchestration`.

## 6. Preuve d'ensemble

- [ ] 6.1 [AUD-TST-09, AUD-TST-10] `python .claude/scripts/run_gates.py --fast` vert ; décompte final des `Thread.sleep` de test (attendu : 48 moins les sites réécrits) consigné.
- [ ] 6.2 **(manuelle)** [AUD-TST-09, AUD-TST-10] À la PR : `Verify (ubuntu-24.04)` (PostgreSQL exigé) et `Verify (windows-2022)` verts ; noter les run ids.

## 7. Clôture

- [ ] 7.1 `docs/developer/testing.md` : les trois moyens de prouver une attente (D1), la preuve de non-vacuité (D2), le rejeu de 50 exécutions. Rejouer `python scripts/docs/check-current-docs.py`.
- [ ] 7.2 Mettre à jour `docs/audit/2026-10-10/SUIVI.md` (AUD-TST-09, AUD-TST-10 : statuts, 14 sites au lieu de 11, sorties rouges de D2, décomptes de rejeu, ce qui n'a tourné qu'en CI).
- [ ] 7.3 `openspec validate --all --strict` ; docs à mettre à jour avant archivage : `docs/STATUS.md`, `docs/developer/testing.md`.
