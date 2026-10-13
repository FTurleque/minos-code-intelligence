# Design

## Context

Sprint 6 de l'audit du 10 octobre 2026 : **AUD-TST-09** et **AUD-TST-10**. HEAD analysé : `949e1569` (`develop`). `PG` = `minos-storage-postgresql/src/test/java/com/minos/storage/postgresql`, `SL` = `minos-storage-local/src/test/java/com/minos/storage/local`.

Ordre dans le sprint 6 : indépendant des autres changements ; `borner-la-couverture-globale-et-du-coeur` est recommandé d'abord (ses planchers signaleraient une baisse de couverture due à une réécriture).

## État vérifié au HEAD

### AUD-TST-09 — confirmé

Trois méthodes sans assertion :

- `PG/snapshot/PostgresCodeKnowledgeSnapshotStoreTest.java:83-90` (`isIdempotentForSameContent` : deux `publish`, rien de relu) ;
- `PG/snapshot/PostgresFingerprintSnapshotStoreTest.java:35-43` (même forme) ;
- `PG/support/PostgresSchemaMigratorTest.java:47-51` (`migrate()` deux fois, `schema_version` non relue).

`minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/support/PostgresSchemaMigrator.java:10` : `CURRENT_VERSION = 4`, visible du test (même package). `:55-56` : une version supérieure est refusée.

### AUD-TST-10 — confirmé, plus large

48 `Thread.sleep` dans 33 fichiers de test. Sites « délai puis assertion négative » :

| Site (ligne du `sleep`) | Assertion | Nature du verrou |
|---|---|---|
| `SL/LocalStorageRetentionLockTest.java:77` | `isDone()` | fichier |
| `SL/store/FileHostedControlPlaneStoreLockTest.java:74` | `isDone()` | fichier |
| `minos-integration-git/src/test/java/com/minos/integration/git/JGitRemoteRepositoryMaterializerLockTest.java:72` | `isDone()` | fichier |
| `minos-engine/src/test/java/com/minos/orchestration/IndexingLifecycleServiceTest.java:118` | compteur et `isDone()` | bail de projet |
| `minos-bootstrap/src/test/java/com/minos/bootstrap/incremental/IncrementalIndexingCoordinatorTest.java:104` | `isDone()` | bail de projet |
| `PG/project/ProjectMutationIndexStateStoreTest.java:113` | `isDone()` | consultatif PostgreSQL |
| `SL/orchestration/ProjectIndexLeaseTest.java:37` | `AtomicBoolean` | fichier |
| `SL/store/ProjectMutationSemanticVectorStoreTest.java:76` | `AtomicBoolean` | bail partagé |
| `SL/store/FileSemanticSyncConsistencyTest.java:265` | `AtomicBoolean` | fichier, entre JVM |
| `PG/project/PostgresRetentionMutationLockTest.java:53` | `AtomicBoolean` | consultatif PostgreSQL |
| `PG/semantic/PostgresSemanticSyncConsistencyTest.java:96` | `AtomicBoolean` | consultatif PostgreSQL |
| `PG/support/PostgresProjectMutationLockIntegrationTest.java:58` | `AtomicBoolean` | consultatif PostgreSQL |
| `PG/project/ProjectMutationIndexStateStoreTest.java:154` | état du pool | consultatif PostgreSQL |

Quatorzième site, sans aucun délai : `PG/project/ProjectMutationIndexStateStoreTest.java:59` affirme `!isDone()` juste après le loquet de démarrage du concurrent. Non cités par l'audit : `FileSemanticSyncConsistencyTest:265`, `PostgresSemanticSyncConsistencyTest:96`, `ProjectMutationIndexStateStoreTest:59`.

Modèle déjà présent dans le dépôt : `PG/project/ProjectMutationIndexStateStoreTest.java:196-204` (loquet de démarrage, puis attente bornée d'un loquet **positif** « acquis », attendu absent). Aucun test du dépôt n'interroge `pg_locks` ; aucun `@RepeatedTest` n'existe.

**Non vérifié** : aucun de ces tests n'a été exécuté pendant l'analyse ; la nature du verrou de chaque site est déduite du nom et du message du test et se confirme en tâche 1.1. Les numéros de ligne de l'audit ont glissé de deux à trois lignes sur quatre sites.

### Écarts avec l'action proposée par l'audit

1. **Observer l'état `WAITING` du thread ne vaut pas pour tous les verrous.** Un thread bloqué sur un verrou consultatif PostgreSQL attend une réponse du serveur, et un thread bloqué sur un verrou de fichier est dans un appel d'E/S natif : tous deux sont `RUNNABLE`. L'observation d'état ne vaut que pour un verrou de la JVM (D1).
2. **Un loquet posé « juste avant l'appel bloquant » ne prouve pas l'entrée dans l'attente**, seulement que le concurrent y arrive. Sans autre signal, il reste une fenêtre ; d'où la preuve de non-vacuité (D2) et, pour PostgreSQL, `pg_locks` qui est une preuve directe.
3. **Onze sites selon l'audit, quatorze au HEAD** ; les 35 autres `Thread.sleep` ne sont pas des preuves d'attente et sortent du périmètre.
4. **La règle du dépôt exige 50 rejeux** d'un test de concurrence nouveau ou réécrit ; l'audit n'en parle pas (D3).
5. **AUD-TST-09 : « relire le nombre de lignes »** suppose de connaître les tables de chaque store ; à relever en tâche 1.1 plutôt qu'à supposer. Le comportement d'une seconde publication à **contenu différent** sous le même identifiant n'a pas été relu : il n'est pas spécifié ici.

## Goals / Non-Goals

**Goals** : un test d'idempotence échoue si l'état persisté change au second appel ; un test de verrou échoue si le verrou est retiré, quelle que soit la charge du runner ; aucune instabilité introduite.

**Non-Goals** : modifier le code de production ; supprimer tous les `Thread.sleep` ; ajouter un gate.

## Decisions

### D1. Un signal d'attente par nature de verrou

- **Verrou de la JVM** (bail de projet en mémoire, bail partagé) : le test attend, avec une échéance, que le concurrent soit observé en attente (file d'attente du verrou si elle est exposée, sinon état `WAITING` / `TIMED_WAITING` du thread), puis affirme l'absence d'effet.
- **Verrou consultatif PostgreSQL** : le test interroge `pg_locks` (verrou consultatif non accordé) jusqu'à une échéance, puis affirme l'absence d'effet. C'est la seule preuve directe.
- **Verrou de fichier**, dans la JVM ou entre deux JVM : loquet posé juste avant l'appel bloquant, puis attente **bornée d'un signal positif** « acquis », attendu absent (modèle de `ProjectMutationIndexStateStoreTest.java:196-204`). La preuve reste indirecte ; elle est complétée par D2.

Dans tous les cas : aucune attente sans échéance ; un dépassement d'échéance **échoue** avec un message, il ne passe pas.

### D2. Chaque test réécrit est vu rouge une fois

Avant de déclarer un test corrigé, le verrou qu'il garde est neutralisé **localement, sans commit** (acquisition remplacée par un passage direct), et le test doit échouer. La sortie rouge est consignée dans `SUIVI.md`. Un test qui reste vert sans verrou n'est pas terminé.

### D3. Rejeu de 50 exécutions

Chaque test de concurrence réécrit est rejoué 50 fois sur le poste (boucle de la commande Maven ciblée par `-Dtest=…`, ou `@RepeatedTest` temporaire non commité). Un seul échec renvoie à la réécriture. Le décompte est consigné ; ce qui n'a tourné qu'en CI (PostgreSQL sans Docker local) est dit.

### D4. Idempotence : relire après le second appel

- Stores de snapshot et d'empreinte : nombre de lignes du projet égal après la première et la seconde publication, et contenu rechargé égal au contenu publié.
- Migrateur : après deux `migrate()`, `schema_version` contient exactement les versions `1..CURRENT_VERSION`, une fois chacune.

### D5. Aides de test au plus près

Une aide « attendre un verrou consultatif en attente » vit dans le support de test de `minos-storage-postgresql` (`PG/PostgresTestSupport.java` ou une classe voisine). Aucune aide commune à plusieurs modules n'est créée pour trois lignes de loquet.

## Direction des dépendances

Tests seulement, dans le module de la classe testée. Aucune arête nouvelle ; aucun support de test n'est partagé entre modules.

## Qualification des capacités

Aucune capacité nouvelle. Les garanties existantes (publication idempotente, migration rejouable, exclusion des mutations concurrentes d'un projet) restent **qualifiées** ; leur preuve passe de « non vérifiée par ces tests » à vérifiée. Preuve de l'attente sur verrou de fichier : **partielle** (indirecte, D1), déclarée comme telle.

## Windows et Linux

Les verrous de fichier diffèrent entre les deux systèmes (verrouillage obligatoire sous Windows, consultatif sous Linux) : les tests de fichier sont rejoués sur les deux. Les tests PostgreSQL exigent Docker : exécutés et exigés sur Linux en CI, sautés sur un poste sans Docker.

## Risks / Trade-offs

- Une observation d'état mal choisie rend un test instable : d'où l'échéance explicite et les 50 rejeux.
- `pg_locks` montre tous les verrous de la base de test : la requête filtre sur la clé du projet ou sur la session du concurrent, sinon un test parallèle la fausse.
- La preuve D2 n'est pas automatisée (pas de mutation en CI) : elle vaut au moment de la réécriture. Les tests de mutation (AUD-TST-11) la rejouent à la demande.
- La couverture ne doit pas bouger ; SonarCloud ne voit que du code de test.
