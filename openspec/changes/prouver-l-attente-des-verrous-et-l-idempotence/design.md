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

## Mesuré à l'implémentation (2026-10-15)

Branche `test/prouver-l-attente-des-verrous`, partie de `develop` à `83dd1fbb`. Poste Windows 10, JDK 25 (Temurin 25.0.4.1), Docker Desktop 29.8.2 : **les tests PostgreSQL ont tourné localement** (Testcontainers, `-Dminos.postgresql.tests.required=true`). Aucun code de production modifié : chaque neutralisation de D2 a été retirée par `git checkout -- */src/main` juste après son exécution, et `git status -- '*/src/main/*'` était vide avant le commit.

### Tâche 1.1 — nature des verrous, relue dans les classes testées

| Site (méthode) | Verrou réellement attendu | Signal retenu |
|---|---|---|
| `LocalStorageRetentionLockTest.aCompactionWaitsFor…` | verrou de fichier tenu par le test dans la même JVM : `BoundedFileLease` reçoit `OverlappingFileLockException` et attend dans `LeaseDeadline.pauseBeforeRetry` | fil observé en attente dans `BoundedFileLease` |
| `FileHostedControlPlaneStoreLockTest.aWriterWaitsFor…` | idem (`FileHostedControlPlaneStore.lock` → `BoundedFileLease`) | idem |
| `JGitRemoteRepositoryMaterializerLockTest.theMaterializationLockCanBeAcquired…` | idem (`JGitRemoteRepositoryMaterializer` → `BoundedFileLease`) | idem, aide locale au test (le module ne dépend pas du test-jar du moteur) |
| `ProjectIndexLeaseTest.serializesConcurrentAcquisitionForSameProject` | **verrou de la JVM** (`ReentrantLock` de `ProjectIndexLease.LockState`, pris par `BoundedFileLease.acquireJvmLock`), pas un verrou de fichier | idem |
| `FileSemanticSyncConsistencyTest.crossJvmFileLockBlocks…` | verrou de fichier tenu par une **autre JVM** ; `tryLock` rend `null` et `BoundedFileLease` attend entre deux essais | idem |
| `ProjectMutationSemanticVectorStoreTest.structuralPromotionCannotInterleave…` | bail `SnapshotProjectLease` : bande de `ReentrantLock` de la JVM puis verrou de fichier, via `BoundedFileLease` | idem |
| `IndexingLifecycleServiceTest.lifecycleLeaseSerializes…` | `ReentrantLock` de `InMemoryIndexStateStore.acquireProjectLease` (JVM) | fil observé en attente dans `InMemoryIndexStateStore` |
| `IncrementalIndexingCoordinatorTest.projectLeaseRemainsHeld…` | idem | idem |
| `ProjectMutationIndexStateStoreTest.sameProjectMutationWaitsForSharedLock` (ancien `:59`, sans délai) | `pg_advisory_xact_lock` de `PostgresProjectMutationLock.acquire` | `pg_locks` : verrou consultatif non accordé sur la clé du projet |
| `ProjectMutationIndexStateStoreTest.lifecycleLeaseBlocks…` | idem, derrière le `pg_try_advisory_lock` de session du bail | idem |
| `ProjectMutationIndexStateStoreTest.sameProjectLifecycleWaitersDoNotConsumeQueryPool` | **verrou de la JVM** : portail local équitable (`ReentrantLock(true)`) de `acquireLocalGate` ; les six attendants n'atteignent jamais PostgreSQL | fils observés en attente dans `ProjectMutationIndexStateStore` |
| `PostgresRetentionMutationLockTest`, `PostgresSemanticSyncConsistencyTest.rawSemanticMutations…`, `PostgresProjectMutationLockIntegrationTest` | `pg_advisory_xact_lock` de `PostgresProjectMutationLock.acquire` | `pg_locks` |

**Écarts avec la table d'analyse.** (1) Le site `:154` n'attend pas un verrou consultatif mais le portail local de la JVM. (2) `ProjectIndexLeaseTest` attend un verrou de la JVM, pas un verrou de fichier. (3) Toute attente de verrou de fichier du dépôt passe par `BoundedFileLease`, qui ne bloque jamais dans un appel natif (`tryLock` puis pause bornée) : le fil est `TIMED_WAITING` avec un cadre `BoundedFileLease`, y compris face à une autre JVM. La preuve directe de D1 pour la JVM vaut donc aussi pour les six sites de fichier ; le loquet posé avant l'appel est conservé et l'assertion négative suit l'observation. (4) L'analyse disait qu'aucun `@RepeatedTest` n'existait : il y en a trois au HEAD (`SourceStateKeyMemoTest:92`, `PostgresConnectionFactoryValidationTest:602,642`) ; sans effet ici.

**Écart à D5.** Observer un fil en attente n'est pas « trois lignes de loquet » : l'aide vit une fois dans `minos-engine/src/test/java/com/minos/testsupport/ParkedThreads.java` (test-jar déjà partagé, comme `LogCapture`), avec son propre test `ParkedThreadsTest` (attente observée ; échec si l'appel se termine sans attendre ; échec à l'échéance si le fil attend ailleurs). `minos-integration-git` et `minos-storage-postgresql` ne dépendent pas de ce test-jar : chacun porte une copie locale d'une dizaine de lignes plutôt qu'une nouvelle dépendance de build. L'aide `pg_locks` est `PostgresTestSupport.awaitAdvisoryLockWaiter` (D5), avec son test `PostgresAdvisoryLockWaiterTest` (attendant observé ; échec à l'échéance quand le verrou est tenu sans attendant ; attendant d'un autre projet ignoré). Aucune aide n'ajoute de `Thread.sleep` : la boucle est cadencée par l'aller-retour SQL ou par `Thread.onSpinWait()`, et bornée par une échéance qui fait échouer le test.

**Inventaire des `Thread.sleep` de test.** Au HEAD `83dd1fbb`, la chaîne apparaît 49 fois dans 34 fichiers, dont 3 dans une Javadoc (`SemanticSyncConsistencyTest:45`, `ConfinedFileOpenerTest:30`, `PostgresSchemaMigratorTest:98`) : **46 appels dans 31 fichiers** (l'analyse en comptait 48 dans 33 au HEAD `949e1569`). Classement :

- 13 preuves d'attente (table ci-dessus ; le quatorzième site n'avait pas de délai) ;
- 17 attentes actives bornées (boucle à échéance sur un fichier, un processus ou un compteur) : `ResumeAfterHardKillIntegrationTest:151`, `ProcessIndexerExecutorTest:245`, `LinuxCgroupJobOwnershipIsolationTest:333,344`, `LinuxStrongProcessOwnershipContainmentTest:112`, `WindowsJobObjectContainmentTest:207`, `ProviderWriteQuotaSupervisorTest:117`, `SnapshotCompactionConcurrencyTest:73`, et neuf dans `minos-intellij` (`MinosProcessSupervisorOrphanTest:103,114,135,141`, `MinosProcessSupervisorTest:349,370`, `MinosStrongProcessLauncherTest:49,322,330`) ;
- 10 simulations de durée (processus fils qui « pend », lecture lente, rythme d'écriture) : `ResumeCrashFixtureMain:126`, `DirectoryReplacingProviderMain:30`, `ProcessIndexerExecutorTest:190,199`, `ProcessOwnershipTrackerHostProtectionTest:21`, `LinuxStrongProcessOwnershipContainmentTest:43`, `StrongProcessOwnershipIndexerExecutorTest:150`, `JGitCloneDeadlineTest:56`, `ProviderWriteQuotaSupervisorTest:33,49` ;
- 4 stabilisations du tas après `System.gc()` : `SnapshotScalabilityBenchmark:425`, `HybridCorpusWeightTest:124`, `InMemoryBackendBenchmark:342`, `QueryViewWeightTest:93` ;
- 2 délais d'échantillonnage suivis d'une assertion, sans verrou : `ProviderWriteQuotaSupervisorTest:64,79` (hors périmètre, à reprendre à part).

**Après le changement : 33 appels dans 19 fichiers** (46 − 13).

### Tâche 1.2 — tables écrites et seconde publication

- `PostgresCodeKnowledgeSnapshotStore.publishSnapshot` écrit `knowledge_snapshots` (clé `(project_id, snapshot_id)`) et `knowledge_active` (clé `project_id`) dans une transaction, sous le verrou de mutation du projet ; l'insertion n'a lieu que si l'identité est absente (`existingSha == null`).
- `PostgresFingerprintSnapshotStore.publish` écrit `fingerprint_snapshots` (clé `(project_id, snapshot_id)`) ; `promote` écrit `fingerprint_active`. Une identité existante et égale est rendue telle quelle.
- **Contenu différent sous le même identifiant** : les deux stores le refusent par `IOException` (« … already exists with different content »), transaction annulée ; pour la connaissance, une différence de format seule (V2 réimporté après V3, ADR 0046) est acceptée après décodage des deux charges. Déjà couvert par `rejectsIdentityMutationWithDifferentContent` dans les deux classes ; non spécifié par ce changement.
- Les clés primaires rendent une ligne dupliquée impossible au HEAD : sans contrôle d'existence, la seconde insertion échoue sur la contrainte. D'où une preuve du rouge en deux temps (A et B ci-dessous).

### Preuves du rouge (D2)

| Neutralisation locale (jamais commitée) | Test | Sortie rouge |
|---|---|---|
| `BoundedFileLease.acquire` rend un bail libre (fichier créé, aucun verrou) | `LocalStorageRetentionLockTest.aCompactionWaitsFor…` | `:88 pool-2-thread-1 completed before it was observed waiting in com.minos.io.BoundedFileLease` |
| idem | `ProjectIndexLeaseTest.serializesConcurrentAcquisition…` | `:45 pool-3-thread-1 completed before it was observed waiting in com.minos.io.BoundedFileLease` |
| idem | `FileHostedControlPlaneStoreLockTest.aWriterWaitsFor…` | `:84 pool-4-thread-1 completed before it was observed waiting in com.minos.io.BoundedFileLease` |
| idem | `FileSemanticSyncConsistencyTest.crossJvmFileLockBlocks…` | `:267 Thread-1 completed before it was observed waiting in com.minos.io.BoundedFileLease` |
| idem | `ProjectMutationSemanticVectorStoreTest.structuralPromotion…` | `:84 pool-5-thread-2 completed before it was observed waiting in com.minos.io.BoundedFileLease` |
| idem | `JGitRemoteRepositoryMaterializerLockTest.theMaterializationLock…` | `:84 the materialization completed before it was observed waiting for the lock` |
| `InMemoryIndexStateStore` sans `lock()` ni `unlock()` | `IndexingLifecycleServiceTest.lifecycleLeaseSerializes…` | `:123 pool-2-thread-2 completed before it was observed waiting in com.minos.orchestration.InMemoryIndexStateStore` |
| idem | `IncrementalIndexingCoordinatorTest.projectLeaseRemainsHeld…` | `:109 pool-2-thread-2 was not observed waiting in com.minos.orchestration.InMemoryIndexStateStore within PT10S (state TIMED_WAITING)` |
| `PostgresProjectMutationLock.acquire` rend la main sans verrou | les cinq sites `pg_locks` | `PostgresTestSupport.awaitAdvisoryLockWaiter:187 no session was observed waiting for the advisory lock of project <uuid> within PT10S` (5 sur 5) |
| portail local de `ProjectMutationIndexStateStore` ouvert | `sameProjectLifecycleWaitersDoNotConsumeQueryPool` | `:174 the local project gate must allow only the current lifecycle owner to reserve a dedicated session ==> expected: <1> but was: <7>` |
| A : contrôles d'existence et garde `version < 4` retirés | les trois tests d'idempotence | `IOException` (« unable to publish … », « unable to initialize … ») : rouge, **mais l'ancien test sans assertion l'aurait été aussi** (clé primaire) |
| B : A, plus clés primaires et `ON CONFLICT` retirés (duplication silencieuse) | `PostgresCodeKnowledgeSnapshotStoreTest.isIdempotentForSameContent` | `:93 one stored snapshot row ==> expected: <1> but was: <2>` |
| B | `PostgresFingerprintSnapshotStoreTest.isIdempotentForSameContent` | `:48 one stored fingerprint row ==> expected: <1> but was: <2>` |
| B | `PostgresSchemaMigratorTest.isIdempotentWhenCalledRepeatedly` | `:54 … ==> expected: <[1, 2, 3, 4]> but was: <[1, 2, 3, 4, 4, 4]>` |
| `ParkedThreads.isParkedIn` rendant toujours `true` | `ParkedThreadsTest` | 4 échecs sur 5 : `failsWhenTheCallerCompletedWithoutWaiting:46`, `failsAsSoonAsTheCallerReportsItsCompletion:55`, `failsAtTheDeadlineWhenTheCallerWaitsElsewhere:73` « Expected org.opentest4j.AssertionFailedError to be thrown, but nothing was thrown », `aRunningThreadIsNotParked:86 expected: <false> but was: <true>` |

Sous la neutralisation B, **les trois tests du HEAD, sans relecture, restent verts** (3 exécutés, 0 échec) : c'est la vacuité relevée par AUD-TST-09. Sous la neutralisation du bail en mémoire, l'ancien `IncrementalIndexingCoordinatorTest` échouait plus loin (`expected: <NONE> but was: <FULL>`) : le second appel attendait ailleurs (`TIMED_WAITING` hors du bail), ce que le délai fixe ne distinguait pas. `PostgresAdvisoryLockWaiterTest.observesASessionWaitingForTheProjectLock` est rouge sous la neutralisation consultative, ses deux cas de refus restent verts (ils ne dépendent pas du verrou).

### Rejeu (D3)

`@RepeatedTest(50)` temporaire (script hors dépôt, retiré avant commit), une exécution Maven ciblée : **50 sur 50** pour chacun des 14 sites, des 3 tests d'idempotence, des 3 tests de `PostgresAdvisoryLockWaiterTest` et des 3 tests concurrents de `ParkedThreadsTest`, soit 1 150 exécutions répétées, 0 échec, 0 saut. Windows seulement : **Linux n'a pas été rejoué localement** ; la CI Ubuntu exécute chaque test une fois par run (tâche 6.2).

### Ce qui n'a pas tourné

- Linux (Ubuntu 24.04) : ni les tests réécrits ni leur rejeu ; à constater à la PR.
- `check-jacoco.py` sur un rapport agrégé, qui demande un `verify` de tout le reactor : non lancé. Le changement n'ajoute que des assertions et retire des délais ; aucun chemin de production n'est moins exercé.
