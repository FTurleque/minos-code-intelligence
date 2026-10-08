# Tasks

## 1. Reprise après arrêt brutal (tests d'abord)

- [ ] 1.1 Test multi-processus `minos-storage-local` : publication interrompue après l'écriture temporaire, processus tué, réouverture → snapshot actif précédent intact. Exécuter sous Windows et Linux. Rejouer `check-jacoco.py` (scopes du stockage local) et `check-private-io.py`. — Fait sous Windows le 2026-10-08 (`SnapshotPublicationCrashTest`, vert) ; reste : exécution Linux en CI et rejeu des deux scripts.
- [x] 1.2 Même test pour `FileProjectFingerprintSnapshotStore`.
- [x] 1.3 Si un test est rouge : correctif dans le même lot, avec la fiche qui le décrit (défaut confirmé) ; sinon consigner que la garantie est démontrée. — Constat 2026-10-08 : aucun test rouge, garantie démontrée sous Windows (`SnapshotPublicationCrashTest`, `FingerprintPublicationCrashTest`, `ActivePointerCorruptionTest`) ; aucun correctif.

## 2. Pointeur actif illisible

- [x] 2.1 Tests pointeur vide, tronqué, version inconnue (`ActiveSnapshotRepository`) avec vérification du message.

## 3. Migration PostgreSQL peuplée

- [x] 3.1 Figer le schéma v1 depuis l'historique de `PostgresSchemaMigrator` dans les ressources de test.
- [ ] 3.2 Test Testcontainers : base v1 peuplée, ouverture, `schema_version` courant, données relues ; test d'échec d'étape avec annulation. Exécuter avec `-Dminos.postgresql.tests.required=true` ; rejouer `check-jacoco.py` sans `--skip-scope m30-postgresql-pgvector`. — Tests écrits et verts le 2026-10-08 (`PostgresSchemaUpgradeFromV1Test`, 2 tests) ; reste : rejeu de `check-jacoco.py`.

## 4. Pointeurs orphelins (après décision)

- [ ] 4.1 Décision de l'utilisateur sur la récupération des `.active-*.tmp` (H13).
- [ ] 4.2 Si oui : test d'abord (inverser `SnapshotRetentionOrphanTest:38`), puis balayage sous verrou de mutation ; amender l'ADR 0023 si la politique de rétention change.

## 5. Validation

- [ ] 5.1 `./mvnw -B -ntp clean verify` sous Windows et CI Ubuntu ; PIT ciblé sur `com.minos.storage.local.*` pour constater que les nouveaux tests tuent des mutants auparavant survivants.
