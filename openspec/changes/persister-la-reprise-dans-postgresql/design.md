# Design

## Context

L'ADR 0039 § 2 dit que `ProjectIndexState.resumableRunId` désigne l'unique run `INTERRUPTED` qu'un projet propose à la reprise, et que ce run est protégé par la rétention exactement comme le dernier run. Le port `IndexStateStore` ne dépend d'aucun backend. Le backend local respecte ces deux règles, mais le backend PostgreSQL non.

## État vérifié au HEAD `560a2fa9`

**Modèle (`minos-engine`)**
- `ProjectIndexState` est un record à 7 composantes. Son invariant (`ProjectIndexState.java:46-49`) refuse une offre de reprise hors des états `STALE` et `FAILED`.
- Un constructeur de compatibilité à 6 arguments fixe `resumableRunId` à `Optional.empty()` (`:52` et suivantes).

**Backend PostgreSQL (`minos-storage-postgresql`)**
- `PostgresSchemaMigrator.java:10` : `CURRENT_VERSION = 4`. Les étapes `applyV1` à `applyV4` sont aux lignes `:58-61`.
- `project_index_state` est créée en `:85` avec `project_id, availability, active_snapshot_id, latest_run_id, updated_at, detail`. Aucune migration n'y ajoute de colonne de reprise.
- `PostgresIndexStateStore.java:34` lit 5 colonnes, et `:38-40` construit l'état par le constructeur à 6 arguments. `:84-88` n'écrit que ces colonnes.
- `PostgresStorageRetentionService.deleteHistoricalRuns` (`:103-130`) ne protège que `p.latest_run_id=target.id`.

**Backend local (`minos-storage-local`)**
- `FileIndexStateStore.java:51` (`RESUMABLE_RUN_ID_PROPERTY`), `:194` en lecture et `:268` en écriture.
- `IndexRunRetentionService.java:34` et `:49` protègent `resumableRunId`.

**Consommateurs (`minos-engine`)**
- `IndexingResumePlanner.java:140` ne propose aucune reprise si la référence est vide.
- `AuthoritativeProjectStateReconciler.java:394-435` calcule et enregistre l'offre, et `:183` et `:426` y mettent fin par `endResumeOffer`.
- `ResumableRunSummary.java:43` lit la référence pour `project status`.

**Tests et gates**
- `git grep -i resum minos-storage-postgresql` ne renvoie rien. Aucun test de contrat n'est commun aux deux backends.
- `PostgresSchemaMigratorTest.java:54` vérifie que `schema_version` contient chaque version de 1 à `CURRENT_VERSION`.
- `PostgresSchemaUpgradeFromV1Test.java:34` affirme `List.of(1, 2, 3, 4)` : ce littéral passera à `1..5`.
- Aucun gate Python n'affirme le numéro de version.

## Goals / Non-Goals

**Objectifs**
- PostgreSQL conserve, relit et retire l'offre de reprise comme le backend local.
- La rétention PostgreSQL protège le run offert.
- Un seul test de contrat fixe ces garanties pour les deux backends.

**Hors objectifs**
- Changer la logique de reprise.
- Reprendre les runs interrompus avant la migration.
- Changer le schéma au-delà d'une colonne.

## Decisions

### D1 — Une colonne nullable, sans contrainte de clé étrangère

`resumable_run_id uuid NULL`. Cette colonne suit `latest_run_id`, qui n'a pas non plus de clé étrangère vers `indexing_runs`. Une référence vers un run supprimé ou incohérent est déjà traitée par `ResumableRunSummary` comme une absence d'offre. C'est la règle de l'ADR 0039, et le backend local l'applique.

- **Option retenue** : une colonne NULL, sans clé étrangère.
- **Option écartée** : une clé étrangère. Elle forcerait un ordre d'écriture entre le run et l'état, que le backend local n'impose pas, et elle romprait la symétrie du contrat.

### D2 — Une migration v5 idempotente

`applyV5` exécute `ALTER TABLE project_index_state ADD COLUMN IF NOT EXISTS resumable_run_id uuid NULL`, puis `INSERT INTO schema_version(version) VALUES (5) ON CONFLICT(version) DO NOTHING`. Elle suit le modèle d'`applyV3`. Elle s'exécute sous le verrou consultatif et dans la transaction de `migrate()` : un échec annule tout, comme l'exigence existante le prévoit déjà.

### D3 — Un test de contrat abstrait, dans le test-jar du moteur

- `com.minos.orchestration.IndexStateStoreContract` est une classe abstraite placée dans `minos-engine/src/test`. Le package appartient au moteur (règle A3). Elle déclare `newStore()` et `reopen()` comme méthodes abstraites.
- Elle est étendue par `FileIndexStateStoreContractTest` (`minos-storage-local`, package `com.minos.storage.local.orchestration`) et par `PostgresIndexStateStoreContractTest` (`minos-storage-postgresql`, sous-classe de `PostgresTestSupport`).
- Les deux modules dépendent déjà du test-jar du moteur. Aucune arête n'est ajoutée.

Cas du contrat :
1. Un état `FAILED` avec une offre se relit à l'identique, par la même instance et par une instance neuve.
2. Un état sans offre (constructeur à 6 arguments) se relit avec `resumableRunId` vide.
3. Remplacer un état avec offre par un état sans offre retire l'offre à la relecture.
4. Un état `STALE` avec une offre se relit à l'identique.

Ce que le contrat ne couvre pas, et qui reste dans les tests propres à chaque backend : le format persisté (fichier de propriétés ou colonnes SQL) et la migration.

- **Option retenue** : une classe abstraite dans le test-jar du moteur. C'est ce que recommandait l'audit.
- **Option écartée** : un test dupliqué dans chaque module, qui ne garantit pas que les deux backends vérifient les mêmes cas.

### D4 — La rétention protège le run offert

Dans `deleteHistoricalRuns`, la sous-requête `NOT EXISTS` porte sur `p.latest_run_id=target.id OR p.resumable_run_id=target.id`. Un test PostgreSQL fixe `maxNonSucceededRuns = 0`, enregistre un run `INTERRUPTED` et un état `FAILED` qui l'offre, appelle `compact`, puis vérifie que le run existe encore. Le même cas est ajouté côté local pour vérifier la symétrie.

## Risques et coût

| Coût | Ampleur | Traitement |
|---|---|---|
| Couverture SonarCloud du nouveau code, 80 % par module | Lignes modifiées de 3 classes de `minos-storage-postgresql`, couvertes par les tests PostgreSQL sous Linux | Les tests du contrat et de la rétention couvrent chaque ligne modifiée ; couverture mesurée sur le rapport Linux de la CI |
| Retour arrière | Une base en v5 est refusée par un MINOS v4 | Comportement existant et voulu de `migrate()`, signalé dans les notes de version |
| Base partagée par plusieurs instances | La migration est sérialisée par le verrou consultatif global | Déjà couvert par `PostgresSchemaMigratorTest` (concurrence) |
| Portée JaCoCo `m30-postgresql-pgvector` | Préfixe `storage/postgresql/` inchangé | Rejouer `check-jacoco.py` |
| Gates littéraux | `check-p0-p2.py` lit `PostgresSchemaMigrator.java` et `PostgresProjectRegistry.java` | Rejouer ; aucune chaîne affirmée ne change |

## Plateformes

- Aucun comportement n'est propre à un système.
- Le contrat local s'exécute sous Windows et sous Linux.
- Le contrat PostgreSQL est exigé sous Linux en CI. Sous Windows, il est sauté sans Docker et exécuté avec Docker Desktop.
