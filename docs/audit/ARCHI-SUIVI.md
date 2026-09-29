# Suivi — chantier Architecture, sévérité moyenne (lot 1 : A3, un package, un module)

> Branche : `archi/a3-packages` (depuis `develop`, base `10486cb7`), worktree `minos-wt/a3-packages`.
> Constats : **A3** (packages éclatés entre modules et alias CLI dépréciés, `AUDIT-2026-09.md` § 3) et **A7** (le script de frontières ne se confronte jamais au reactor), fermé au passage.
> Décision d'architecture : [ADR 0044](../adr/0044-un-package-un-module.md).
> Agents : `impl-archi` (implémentation), `verif-archi` (inspection de chaque commit). Aucun push, aucune PR ouverte par les agents.
> Règles du lot : aucun changement de comportement (les deux tests de caractérisation A2 et les 12 golden de `minos-app/src/test/resources/characterization/` restent verts et identiques octet pour octet, jamais régénérés) ; déplacer plutôt que renommer ; aucune règle de `check-module-boundaries.py` assouplie ; aucune dépendance Maven ajoutée hors de `ALLOWED_DEPENDENCIES`.

## 1. Tableau de bord

| Jalon | Contenu | Statut | Commits |
|---|---|---|---|
| 1 | Inventaire, table de correspondance prévue, ADR 0044, contrôle « aucun package éclaté » en cliquet, A7, auto-test, branchement CI | livré, accepté par verif-archi | `073f4a43`, `26e63775` |
| 2 | Renommages des adaptateurs : `adapter.scip` (a), `git` (b), runtime-local et `MinosVersion` (c1, c2), storage-local `store`/`registry`/`storage`/`orchestration` (d1–d4) ; `incremental` de storage-local reporté (voir journal) | livré, accepté par verif-archi | `883fb35d`, `d3fb624c`, `8c0eb0ef`, `92a27a36`, `3bdb7eea`, `a2f8a69b`, `57817bdd`, `814130ba` (+ docs `4c61e30f`, `5eefb2ec`, test `7c97bff9`) |
| 3 | Replis de la couche application : `discovery`/`discovery.spi` (e1), `incremental`/`orchestration` (e2), tests relogés (e3), groupe d5 reporté, `hosted` (f), `dynamic` (g1, g2 avec scission du test), `semantic` (h), `StorageBackends` (i) | livré, accepté par verif-archi | `adeaee42`, `4b8f08a4`, `86cb9711`, `05d94082`, `a23179b9`, `ee446f23`, `fe2e988e`, `4009da25`, `a545a5a1` |
| 4 | `cli` / `app` et SPI `mcp` (j), `nexus` (k), alias CLI (l), 41 tests étrangers (m), filtres m19/m20 (n), clôture : cliquet supprimé, règle stricte, ADR et docs finalisés (o) | livré, accepté par verif-archi | `b56413bb`, `56682e0e`, `59345b6f`, `cb4f0b58`, `751ba98d`, `b0586a93`, `0ef46b12`, `34642f4d` |
| **Bilan** | 14 packages éclatés → 0 ; 45 tests en package étranger → 0 ; accès package-private entre jars → 0 ; 4 alias CLI supprimés ; A7 fermé | lot terminé | — |

## 2. Inventaire daté (base `10486cb7`, 29 septembre 2026)

L'inventaire de l'audit (9 packages éclatés) est antérieur à A2 : en descendant des ports dans `minos-engine` sans changer leur package, A2 en a créé de nouveaux. Recalcul sur les sources (`package` déclaré par chaque fichier de `src/main/java` des 14 modules du reactor) :

### 2.1 Packages de production déclarés par plusieurs modules (14)

| Package | Modules (nombre de classes de premier niveau) |
|---|---|
| `com.minos.adapter.scip` | engine 2, provider-scip 17 |
| `com.minos.cli` | app 7, cli 37 |
| `com.minos.discovery` | application 3, engine 1 |
| `com.minos.dynamic` | application 2, domain 9, engine 1 |
| `com.minos.git` | engine 1, integration-git 6 |
| `com.minos.hosted` | application 18, domain 10, engine 4 |
| `com.minos.incremental` | application 12, engine 5, storage-local 1 |
| `com.minos.integration.nexus` | app 1, nexus 4 |
| `com.minos.orchestration` | application 12, engine 25, storage-local 4 |
| `com.minos.registry` | engine 6, storage-local 3 |
| `com.minos.runtime` | application 1, engine 4, runtime-local 34 |
| `com.minos.semantic` | application 10, domain 5 |
| `com.minos.storage` | application 1, engine 6, storage-local 3 |
| `com.minos.store` | engine 7, storage-local 18 |

`com.minos.discovery.spi` (application 4) n'est pas éclaté, mais fait partie de la fermeture de `com.minos.discovery` et le suit.

### 2.2 Tests logés dans un package dont les sources de production appartiennent à un autre module (45)

| Module du test | Package | Production dans | Fichiers |
|---|---|---|---|
| app | `com.minos.adapter.scip` | engine, provider-scip | `M17ProviderPlatformTest`, `M24PolyglotProviderTest`, `ScipIndexerCatalogTest`, `ScipPersistentSnapshotExperiment`, `ScipRelatedTestSnapshotIntegrationTest`, `ScipSymbolSnapshotImporterTest` |
| app | `com.minos.application` | application | `M17ProviderSurfaceIntegrationTest`, `ProviderCatalogPortTest`, `SharedMinosApplicationIntegrationTest` |
| app | `com.minos.architecture` | application | `ArchitectureRealFixtureMeasurementTest` |
| app | `com.minos.context` | application | `CodeSearchBenchmark` |
| app | `com.minos.impact` | application | `ImpactAnalysisRealFixtureTest` |
| app | `com.minos.incremental` | application, engine, storage-local | `IncrementalIndexingRealFixtureTest` |
| app | `com.minos.mcp` | mcp | `MinosMcpServerIntegrationTest` |
| app | `com.minos.query` | engine | `DependencyDerivationServiceTest`, `RelatedTestDerivationServiceTest`, `RelationshipQueryServiceTest`, `SymbolQueryServiceTest` |
| bootstrap | `com.minos.application` | application | `MinosApplicationTest`, `ProgramGraphPerformanceQualificationTest`, `ProjectIndexStateReconcilerTest`, `ProjectInspectionSnapshotConsistencyTest`, `ProjectResolverTest`, `ProviderPlatformDiagnosticRedactionTest` |
| bootstrap | `com.minos.architecture` | application | `ArchitectureJavaFixtureMeasurementTest`, `LocalProjectArchitectureQueryTest` |
| bootstrap | `com.minos.dynamic` | application, domain, engine | `RuntimeIntelligenceServiceTest` |
| bootstrap | `com.minos.impact` | application | `LocalProjectImpactQueryTest` |
| bootstrap | `com.minos.incremental` | application, engine, storage-local | `FileProjectFingerprintSnapshotStoreSymlinkTest`, `FileProjectFingerprintSnapshotStoreTest`, `IncrementalIndexingCoordinatorTest`, `IncrementalIndexingDiagnosticRedactionTest`, `ProjectFingerprintSnapshotAlignmentServiceTest`, `ProjectFingerprintSnapshotRealFixtureTest` |
| bootstrap | `com.minos.orchestration` | application, engine, storage-local | `FileAuthoritativeSnapshotRecoveryTest`, `ResumeAfterHardKillIntegrationTest`, `ResumeCrashFixtureMain` |
| bootstrap | `com.minos.program.analysis` | application | `FileProgramGraphProviderTest`, `ProgramGraphAnalysisTest`, `ProgramGraphServiceConcurrencyTest` |
| bootstrap | `com.minos.semantic` | application, domain | `M23SemanticProviderConfigurationTest`, `SemanticHybridIntelligenceTest`, `SemanticSyncConsistencyTest` |
| bootstrap | `com.minos.storage.postgresql` | storage-postgresql | `PostgresAuthoritativeSnapshotConsistencyTest` |
| bootstrap | `com.minos.workspace` | application | `WorkspaceIntelligenceServiceTest` |

**Tests latents.** Les tests d'un module logés dans un package éclaté dont ce module est l'un des propriétaires ne sont pas encore en faute, mais le deviennent dès que la production du package quitte leur module. Relevé : application `discovery` 4, `incremental` 6, `orchestration` 17, `hosted` 11, `semantic` 4, `storage` 1 ; domain `hosted` 1, `dynamic` 1 ; app `com.minos.cli` 8 ; puis, par renommage, les tests des adaptateurs (runtime-local `runtime` 48, storage-local `store` 25 / `orchestration` 10 / `registry` 7 / `storage` 2, integration-git `git` 7, nexus 3). Les 40 tests d'application et de domain de `discovery`, `incremental`, `orchestration`, `hosted` et `dynamic` ne référencent que des classes d'engine, de domain ou de la fermeture déplacée : ils peuvent suivre leur production dans `minos-engine` (aucune bibliothèque de test hors JUnit).

### 2.3 Accès de niveau package entre jars réellement utilisés

Relevé exact dans le bytecode compilé (`./mvnw -q -DskipTests test-compile` sur `10486cb7`) : pour chaque classe, les références du pool de constantes vers une classe du même package et d'un autre module, dont la classe cible n'est pas publique ou dont le membre n'est ni public ni privé. Les constantes de compilation (inlinées par `javac`) sont relevées à part dans les sources.

| Accès | Côté | Résolution prévue |
|---|---|---|
| app `MinosLauncher` → cli `MinosCliRunner.isStatelessHelpRequest`, `runStatelessHelp`, `isIdeHandshake`, `runIdeHandshake` | production | disparaît : `MinosLauncher` est déplacé dans `minos-cli` |
| application `incremental.ProjectChangeSet` → engine `FileFingerprint.requireSha256` | production | disparaît : `incremental` d'application est déplacé dans engine |
| storage-local `incremental.FileProjectFingerprintSnapshotStore$ActivePointer` → engine `FileFingerprint.requireSha256` | production | **résolu** (arbitrage 2, groupe d5 `05d94082`) : `requireSha256` rendu public, Javadoc de règle de format partagée |
| storage-local `orchestration.FileIndexStateStore` → engine `IndexingRun.portable(Path)` | production | **résolu** (arbitrage 2, groupe d4 `814130ba`) : `portable` rendu public, Javadoc de règle de format partagée |
| application (test) `storage.StorageBackendConfigurationTest` → engine `MinosRuntimeSettings.testing`, `StorageBackendConfiguration.resolve` | test | le test ne référence que des classes d'engine : il rejoint les tests d'engine |
| bootstrap (test) `dynamic.RuntimeIntelligenceServiceTest` → application `RuntimeIntelligenceService(…, Clock)` | test | **résolu** (arbitrage 3, groupe g2 `fe2e988e`) : test scindé, constructeur à `Clock` resté package-private |
| bootstrap (test) `incremental.FileProjectFingerprintSnapshotStoreTest` → storage-local `FileProjectFingerprintSnapshotStore.MAX_FILES`, `MAX_STRING_BYTES`, `MAX_SNAPSHOT_BYTES` | test | le test rejoint les tests de storage-local une fois `incremental` d'application dans engine |
| bootstrap (test) `storage.postgresql.PostgresAuthoritativeSnapshotConsistencyTest` → storage-postgresql `PostgresIndexStateStore`, `PostgresJsonCodec`, `PostgresProjectRegistry` (classes non publiques), `PostgresTestSupport` | test | le test ne dépend d'application que par `IndexingLifecycleService`, qui descend dans engine : il rejoint alors les tests de storage-postgresql (même module que les classes qu'il touche) |

Scripts de relevé : `inventory.py`, `pkgprivate.py`, `mapping.py` (scratch de l'agent, non versionnés) ; sorties datées conservées avec la preuve rouge du contrôle.

## 3. Décisions

### 3.1 Table des décisions (fixée par l'orchestrateur, vérifiée ici)

| Package | Décision | Vérification sur `10486cb7` |
|---|---|---|
| `discovery`, `discovery.spi`, `incremental`, `orchestration` (côté application) | déplacement des 31 classes d'application dans `minos-engine` | fermeture close : ces 31 classes ne référencent que engine, domain et le JDK (`com.minos.io`, `source`, `diagnostics` sont dans engine) ; aucune dépendance externe (Jackson n'est utilisé que par `semantic.OllamaEmbeddingProvider`) |
| `hosted` | déplacement application 18 + domain 10 → `minos-engine` | fermeture d'application vide ; aucune classe de domain hors `hosted` ne nomme ces records ; aucune signature publique de `minos-api` ne les expose |
| `runtime` | `MinosVersion` déplacé application → engine ; runtime-local 34 renommé `com.minos.runtime.local` | `Implementation-Version` n'est posé que dans le manifeste du jar de `minos-app` et du jar ombré (`minos-app/pom.xml`) : le jar d'origine de la classe ne porte pas la version, le repli de développement reste inchangé — test à l'appui au lot concerné. Les ressources `com/minos/runtime/**` (gabarits PowerShell, fragments, golden de test) et `META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` suivent le renommage |
| `store`, `registry`, `storage`, `orchestration`, `incremental` (côté storage-local) | renommés `com.minos.storage.local.store`, `.registry`, `com.minos.storage.local`, `.orchestration`, `.incremental` | voir § 3.2 pour les deux accès de production coupés |
| `git` | integration-git 6 renommé `com.minos.integration.git` ; `GitIntelligence` reste dans engine | sans objection |
| `adapter.scip` | les 2 DTO d'engine renommés dans `com.minos.orchestration` | pas de collision de nom dans `com.minos.orchestration` |
| `dynamic` | application 2 renommés `com.minos.application.dynamic` ; domain 9 + engine 1 se replient par déplacement | le port `RuntimeObservationStore` **n'est pas exempt d'I/O** : ses quatre méthodes déclarent `IOException`. Il ne descend donc pas dans domain ; ce sont les 9 records de domain qui montent dans engine (aucune classe de domain hors `dynamic` ne les nomme) |
| `semantic` | application 10 renommés `com.minos.application.semantic` ; domain garde ses 5 types | `minos-api` n'utilise ces services qu'en interne (`LocalSemanticCodeIntelligenceApi`), aucune signature publique ne les expose |
| `storage` (côté application) | `StorageBackends` renommé dans `com.minos.application` | l'import change dans `A2CompositionCharacterizationTest` ; voir § 3.2 |
| `cli` | `MinosLauncher` déplacé dans `minos-cli` (FQN conservé), route `mcp` par un SPI déclaré dans `minos-cli` et fourni par `minos-app` ; les autres classes de `minos-app` renommées `com.minos.app` | voir § 3.2 pour `DockerRuntimeBootstrap` |
| `integration.nexus` | `NexusExportBridgeMain` reste dans `minos-app` (FQN conservé) ; les 4 classes de `minos-nexus` renommées `com.minos.nexus` | sans objection |
| tests en package étranger | relogés dans un package de leur module, ou dans le module testé quand A2 le permet | voir § 2.2 et § 2.3 |
| alias CLI dépréciés | `cli.ProjectOperations`, `ProjectSymbolQuery`, `LocalProjectOperations`, `LocalProjectSymbolQuery` supprimés ; appelants sur `com.minos.application.*` | ce sont les quatre seuls `@Deprecated` de `minos-cli` |

### 3.2 Écarts et points à arbitrer relevés par `impl-archi`

1. **`DockerRuntimeBootstrap` est un point d'entrée de processus.** `docker/compose.mcp.prod.yaml:91` et `docker/compose.mcp.connected.yaml:116` le lancent par son nom (`entrypoint: [java, -cp, /opt/minos/minos.jar, com.minos.cli.DockerRuntimeBootstrap]`). Le renommer en `com.minos.app` casserait les fichiers compose déjà copiés par les utilisateurs. Il ne dépend que d'application, d'engine et de `FindSymbolCommand` (cli) : il peut être **déplacé** dans `minos-cli` avec son FQN, comme `MinosLauncher`. Proposé en application du principe « jamais un point d'entrée documenté ». **Arbitrage (2026-09-29) : retenu**, `DockerRuntimeBootstrap` est déplacé dans `minos-cli` et garde son FQN.
2. **Deux accès de production de storage-local vers des méthodes package-private d'engine** (`FileFingerprint.requireSha256`, `IndexingRun.portable`) sont coupés par le renommage de storage-local. Ce sont des règles de format partagées entre le port et son adaptateur (empreinte SHA-256 validée, chemin portable des points de contrôle persistés) : les copier dans storage-local violerait la règle « pas de copie d'un helper existant » et créerait deux sources de vérité pour un format sur disque. Proposition : les rendre `public`, avec justification écrite dans le commit et ici. **Arbitrage (2026-09-29) : retenu.** Chaque méthode reçoit une Javadoc qui l'énonce comme règle de format sur disque partagée entre le port et ses adaptateurs ; aucune copie.
3. **`RuntimeIntelligenceServiceTest`** (bootstrap) construit `RuntimeIntelligenceService` par son constructeur package-private à `Clock`, avec des adaptateurs fichiers réels : il ne peut ni rejoindre application (A2 interdit application → adaptateurs, portée test comprise) ni garder son accès une fois le service renommé `com.minos.application.dynamic`. Options : (a) constructeur à `Clock` rendu public (point d'injection de l'horloge, justifié) ; (b) test scindé, la partie qui a besoin de l'horloge réécrite dans application avec des doublures des ports d'engine. **Arbitrage (2026-09-29) : option (b).** Les cas qui dépendent de l'horloge passent dans `minos-application` avec des doublures en mémoire des ports d'engine ; les cas qui ont besoin des adaptateurs fichiers restent dans bootstrap, dans un package de bootstrap ; le constructeur à `Clock` reste package-private. Chaque assertion d'origine doit exister après la scission (décompte et correspondance au journal). Si la scission se révèle démesurée, arrêt et signalement plutôt qu'élargissement.
4. **Les tests de caractérisation A2 importent deux classes renommées** : `A2CompositionCharacterizationTest` importe `com.minos.storage.LocalStorageBackend` (→ `com.minos.storage.local.LocalStorageBackend`) et `com.minos.storage.StorageBackends` (→ `com.minos.application.StorageBackends`). Les 12 golden ne contiennent aucun nom pleinement qualifié MINOS (vérifié : 0 occurrence de `com.minos.`), ils restent donc identiques ; mais le **source** de ce test changera de deux lignes d'import. Si la règle « identiques octet pour octet » vise aussi les sources des deux tests, ces deux renommages sont incompatibles avec elle. **Arbitrage (2026-09-29) :** le source des tests de caractérisation peut changer d'imports ; restent identiques octet pour octet les 12 golden et le comportement testé ; aucune assertion n'est modifiée.
5. **Noms de journaux.** Les classes renommées qui journalisent par `System.getLogger(X.class.getName())` (huit classes de runtime-local) changent de nom de journal. Aucun fichier de configuration de journalisation n'existe dans le dépôt et aucun golden ne contient de nom MINOS pleinement qualifié ; c'est une conséquence observable seulement dans les journaux, consignée dans l'ADR 0044. **Arbitrage (2026-09-29) : accepté.**

## 4. Table de correspondance (ancien module/FQN → nouveau)

Générée depuis les sources de `10486cb7`, tenue à jour à chaque commit (colonne Statut : groupe qui l'a réalisée, sha au journal § 6). « inchangé » = déplacement pur, seul le jar change. La ligne 1 (`DockerRuntimeBootstrap`) suit l'arbitrage § 3.2 (1). Toutes les lignes sont faites.

| # | Nature | Ancien module | Ancien FQN | Nouveau module | Nouveau FQN | Statut |
|---|---|---|---|---|---|---|
| 1 | déplacement | `app` | `com.minos.cli.DockerRuntimeBootstrap` | `cli` | inchangé | fait (groupe j) |
| 2 | déplacement | `app` | `com.minos.cli.MinosLauncher` | `cli` | inchangé | fait (groupe j) |
| 3 | déplacement | `application` | `com.minos.discovery.DefaultDiscoveryPlugins` | `engine` | inchangé | fait (groupe e) |
| 4 | déplacement | `application` | `com.minos.discovery.ProjectDiscoveryService` | `engine` | inchangé | fait (groupe e) |
| 5 | déplacement | `application` | `com.minos.discovery.ProjectIgnorePolicy` | `engine` | inchangé | fait (groupe e) |
| 6 | déplacement | `application` | `com.minos.discovery.spi.BuildSystemDetector` | `engine` | inchangé | fait (groupe e) |
| 7 | déplacement | `application` | `com.minos.discovery.spi.LanguageDetector` | `engine` | inchangé | fait (groupe e) |
| 8 | déplacement | `application` | `com.minos.discovery.spi.ProjectDetector` | `engine` | inchangé | fait (groupe e) |
| 9 | déplacement | `application` | `com.minos.discovery.spi.SourceRootDetector` | `engine` | inchangé | fait (groupe e) |
| 10 | déplacement | `application` | `com.minos.hosted.HmacHostedIdentityProvider` | `engine` | inchangé | fait (groupe f) |
| 11 | déplacement | `application` | `com.minos.hosted.HostedAuditChain` | `engine` | inchangé | fait (groupe f) |
| 12 | déplacement | `application` | `com.minos.hosted.HostedAuditDelivery` | `engine` | inchangé | fait (groupe f) |
| 13 | déplacement | `application` | `com.minos.hosted.HostedAuditSink` | `engine` | inchangé | fait (groupe f) |
| 14 | déplacement | `application` | `com.minos.hosted.HostedAuthorizationService` | `engine` | inchangé | fait (groupe f) |
| 15 | déplacement | `application` | `com.minos.hosted.HostedAvailabilityPort` | `engine` | inchangé | fait (groupe f) |
| 16 | déplacement | `application` | `com.minos.hosted.HostedCommitRecovery` | `engine` | inchangé | fait (groupe f) |
| 17 | déplacement | `application` | `com.minos.hosted.HostedControlPlaneService` | `engine` | inchangé | fait (groupe f) |
| 18 | déplacement | `application` | `com.minos.hosted.HostedDenialThrottle` | `engine` | inchangé | fait (groupe f) |
| 19 | déplacement | `application` | `com.minos.hosted.HostedIdentityProvider` | `engine` | inchangé | fait (groupe f) |
| 20 | déplacement | `application` | `com.minos.hosted.HostedMembershipService` | `engine` | inchangé | fait (groupe f) |
| 21 | déplacement | `application` | `com.minos.hosted.HostedProductionBoundary` | `engine` | inchangé | fait (groupe f) |
| 22 | déplacement | `application` | `com.minos.hosted.HostedRetentionService` | `engine` | inchangé | fait (groupe f) |
| 23 | déplacement | `application` | `com.minos.hosted.HostedTenantMutationWriter` | `engine` | inchangé | fait (groupe f) |
| 24 | déplacement | `application` | `com.minos.hosted.HostedTenantService` | `engine` | inchangé | fait (groupe f) |
| 25 | déplacement | `application` | `com.minos.hosted.HostedTokenService` | `engine` | inchangé | fait (groupe f) |
| 26 | déplacement | `application` | `com.minos.hosted.HostedTransportSecurityPort` | `engine` | inchangé | fait (groupe f) |
| 27 | déplacement | `application` | `com.minos.hosted.HostedWorkspaceService` | `engine` | inchangé | fait (groupe f) |
| 28 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingCoordinator` | `engine` | inchangé | fait (groupe e) |
| 29 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingPlan` | `engine` | inchangé | fait (groupe e) |
| 30 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingPlanReason` | `engine` | inchangé | fait (groupe e) |
| 31 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingPlanner` | `engine` | inchangé | fait (groupe e) |
| 32 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingResult` | `engine` | inchangé | fait (groupe e) |
| 33 | déplacement | `application` | `com.minos.incremental.ProjectChangeSet` | `engine` | inchangé | fait (groupe e) |
| 34 | déplacement | `application` | `com.minos.incremental.ProjectFingerprintService` | `engine` | inchangé | fait (groupe e) |
| 35 | déplacement | `application` | `com.minos.incremental.ProjectFingerprintSnapshotAlignmentService` | `engine` | inchangé | fait (groupe e) |
| 36 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationAssessment` | `engine` | inchangé | fait (groupe e) |
| 37 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationReason` | `engine` | inchangé | fait (groupe e) |
| 38 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationScope` | `engine` | inchangé | fait (groupe e) |
| 39 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationService` | `engine` | inchangé | fait (groupe e) |
| 40 | déplacement | `application` | `com.minos.orchestration.AuthoritativeProjectStateReconciler` | `engine` | inchangé | fait (groupe e) |
| 41 | déplacement | `application` | `com.minos.orchestration.ExecutionCheckpoints` | `engine` | inchangé | fait (groupe e) |
| 42 | déplacement | `application` | `com.minos.orchestration.IndexerExecutionScopeResolver` | `engine` | inchangé | fait (groupe e) |
| 43 | déplacement | `application` | `com.minos.orchestration.IndexingExecutionTarget` | `engine` | inchangé | fait (groupe e) |
| 44 | déplacement | `application` | `com.minos.orchestration.IndexingLifecyclePlanSupport` | `engine` | inchangé | fait (groupe e) |
| 45 | déplacement | `application` | `com.minos.orchestration.IndexingLifecycleService` | `engine` | inchangé | fait (groupe e) |
| 46 | déplacement | `application` | `com.minos.orchestration.IndexingResumePlanner` | `engine` | inchangé | fait (groupe e) |
| 47 | déplacement | `application` | `com.minos.orchestration.IndexingResumePolicy` | `engine` | inchangé | fait (groupe e) |
| 48 | déplacement | `application` | `com.minos.orchestration.IndexingRunExecutor` | `engine` | inchangé | fait (groupe e) |
| 49 | déplacement | `application` | `com.minos.orchestration.ResumableArtifactPolicy` | `engine` | inchangé | fait (groupe e) |
| 50 | déplacement | `application` | `com.minos.orchestration.ResumableRunMarkers` | `engine` | inchangé | fait (groupe e) |
| 51 | déplacement | `application` | `com.minos.orchestration.ResumableRunSummary` | `engine` | inchangé | fait (groupe e) |
| 52 | déplacement | `application` | `com.minos.runtime.MinosVersion` | `engine` | inchangé | fait (groupe c) |
| 53 | déplacement | `domain` | `com.minos.dynamic.CorrelatedRuntimeObservation` | `engine` | inchangé | fait (groupe g) |
| 54 | déplacement | `domain` | `com.minos.dynamic.CorrelatedRuntimeSession` | `engine` | inchangé | fait (groupe g) |
| 55 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservation` | `engine` | inchangé | fait (groupe g) |
| 56 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservationCompleteness` | `engine` | inchangé | fait (groupe g) |
| 57 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservationSession` | `engine` | inchangé | fait (groupe g) |
| 58 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservationType` | `engine` | inchangé | fait (groupe g) |
| 59 | déplacement | `domain` | `com.minos.dynamic.RuntimeResolutionStatus` | `engine` | inchangé | fait (groupe g) |
| 60 | déplacement | `domain` | `com.minos.dynamic.RuntimeSymbolReference` | `engine` | inchangé | fait (groupe g) |
| 61 | déplacement | `domain` | `com.minos.dynamic.RuntimeSymbolResolution` | `engine` | inchangé | fait (groupe g) |
| 62 | déplacement | `domain` | `com.minos.hosted.HostedAccessClaims` | `engine` | inchangé | fait (groupe f) |
| 63 | déplacement | `domain` | `com.minos.hosted.HostedAuditEvent` | `engine` | inchangé | fait (groupe f) |
| 64 | déplacement | `domain` | `com.minos.hosted.HostedPermission` | `engine` | inchangé | fait (groupe f) |
| 65 | déplacement | `domain` | `com.minos.hosted.HostedPrincipal` | `engine` | inchangé | fait (groupe f) |
| 66 | déplacement | `domain` | `com.minos.hosted.HostedProjectBinding` | `engine` | inchangé | fait (groupe f) |
| 67 | déplacement | `domain` | `com.minos.hosted.HostedRetentionPlan` | `engine` | inchangé | fait (groupe f) |
| 68 | déplacement | `domain` | `com.minos.hosted.HostedRetentionPolicy` | `engine` | inchangé | fait (groupe f) |
| 69 | déplacement | `domain` | `com.minos.hosted.HostedRole` | `engine` | inchangé | fait (groupe f) |
| 70 | déplacement | `domain` | `com.minos.hosted.HostedTenantState` | `engine` | inchangé | fait (groupe f) |
| 71 | déplacement | `domain` | `com.minos.hosted.SharedWorkspace` | `engine` | inchangé | fait (groupe f) |
| 72 | renommage | `app` | `com.minos.cli.DockerMcpTransport` | `app` | `com.minos.app.DockerMcpTransport` | fait (groupe j) |
| 73 | renommage | `app` | `com.minos.cli.McpBackend` | `app` | `com.minos.app.McpBackend` | fait (groupe j) |
| 74 | renommage | `app` | `com.minos.cli.McpBackendConfiguration` | `app` | `com.minos.app.McpBackendConfiguration` | fait (groupe j) |
| 75 | renommage | `app` | `com.minos.cli.McpBackendConfigurationStore` | `app` | `com.minos.app.McpBackendConfigurationStore` | fait (groupe j) |
| 76 | renommage | `app` | `com.minos.cli.McpBackendRouter` | `app` | `com.minos.app.McpBackendRouter` | fait (groupe j) |
| 77 | renommage | `application` | `com.minos.dynamic.RuntimeIntelligenceService` | `application` | `com.minos.application.dynamic.RuntimeIntelligenceService` | fait (groupe g) |
| 78 | renommage | `application` | `com.minos.dynamic.RuntimeObservationEnvelopeCodec` | `application` | `com.minos.application.dynamic.RuntimeObservationEnvelopeCodec` | fait (groupe g) |
| 79 | renommage | `application` | `com.minos.semantic.EmbeddingProvider` | `application` | `com.minos.application.semantic.EmbeddingProvider` | fait (groupe h) |
| 80 | renommage | `application` | `com.minos.semantic.HybridContextBuilder` | `application` | `com.minos.application.semantic.HybridContextBuilder` | fait (groupe h) |
| 81 | renommage | `application` | `com.minos.semantic.HybridSearchService` | `application` | `com.minos.application.semantic.HybridSearchService` | fait (groupe h) |
| 82 | renommage | `application` | `com.minos.semantic.LocalHashEmbeddingProvider` | `application` | `com.minos.application.semantic.LocalHashEmbeddingProvider` | fait (groupe h) |
| 83 | renommage | `application` | `com.minos.semantic.OllamaEmbeddingProvider` | `application` | `com.minos.application.semantic.OllamaEmbeddingProvider` | fait (groupe h) |
| 84 | renommage | `application` | `com.minos.semantic.SemanticDocumentFactory` | `application` | `com.minos.application.semantic.SemanticDocumentFactory` | fait (groupe h) |
| 85 | renommage | `application` | `com.minos.semantic.SemanticIndexBudget` | `application` | `com.minos.application.semantic.SemanticIndexBudget` | fait (groupe h) |
| 86 | renommage | `application` | `com.minos.semantic.SemanticIndexService` | `application` | `com.minos.application.semantic.SemanticIndexService` | fait (groupe h) |
| 87 | renommage | `application` | `com.minos.semantic.SemanticSearchEvaluator` | `application` | `com.minos.application.semantic.SemanticSearchEvaluator` | fait (groupe h) |
| 88 | renommage | `application` | `com.minos.semantic.SemanticSearchService` | `application` | `com.minos.application.semantic.SemanticSearchService` | fait (groupe h) |
| 89 | renommage | `application` | `com.minos.storage.StorageBackends` | `application` | `com.minos.application.StorageBackends` | fait (groupe i) |
| 90 | renommage | `engine` | `com.minos.adapter.scip.ScipSymbolSnapshotReport` | `engine` | `com.minos.orchestration.ScipSymbolSnapshotReport` | fait (groupe a) |
| 91 | renommage | `engine` | `com.minos.adapter.scip.ScipSymbolSnapshotRequest` | `engine` | `com.minos.orchestration.ScipSymbolSnapshotRequest` | fait (groupe a) |
| 92 | renommage | `integration-git` | `com.minos.git.GitIntelligenceService` | `integration-git` | `com.minos.integration.git.GitIntelligenceService` | fait (groupe b) |
| 93 | renommage | `integration-git` | `com.minos.git.JGitCloneDeadline` | `integration-git` | `com.minos.integration.git.JGitCloneDeadline` | fait (groupe b) |
| 94 | renommage | `integration-git` | `com.minos.git.JGitRemoteGitClient` | `integration-git` | `com.minos.integration.git.JGitRemoteGitClient` | fait (groupe b) |
| 95 | renommage | `integration-git` | `com.minos.git.JGitRemoteRepositoryMaterializer` | `integration-git` | `com.minos.integration.git.JGitRemoteRepositoryMaterializer` | fait (groupe b) |
| 96 | renommage | `integration-git` | `com.minos.git.RemoteCloneBudget` | `integration-git` | `com.minos.integration.git.RemoteCloneBudget` | fait (groupe b) |
| 97 | renommage | `integration-git` | `com.minos.git.RemoteRepositoryCachePolicy` | `integration-git` | `com.minos.integration.git.RemoteRepositoryCachePolicy` | fait (groupe b) |
| 98 | renommage | `nexus` | `com.minos.integration.nexus.NexusExportContract` | `nexus` | `com.minos.nexus.NexusExportContract` | fait (groupe k) |
| 99 | renommage | `nexus` | `com.minos.integration.nexus.NexusExportService` | `nexus` | `com.minos.nexus.NexusExportService` | fait (groupe k) |
| 100 | renommage | `nexus` | `com.minos.integration.nexus.NexusSemanticSignalContract` | `nexus` | `com.minos.nexus.NexusSemanticSignalContract` | fait (groupe k) |
| 101 | renommage | `nexus` | `com.minos.integration.nexus.NexusSemanticSignalService` | `nexus` | `com.minos.nexus.NexusSemanticSignalService` | fait (groupe k) |
| 102 | renommage | `runtime-local` | `com.minos.runtime.BoundedProcessOutput` | `runtime-local` | `com.minos.runtime.local.BoundedProcessOutput` | fait (groupe c) |
| 103 | renommage | `runtime-local` | `com.minos.runtime.CgroupJobOwnership` | `runtime-local` | `com.minos.runtime.local.CgroupJobOwnership` | fait (groupe c) |
| 104 | renommage | `runtime-local` | `com.minos.runtime.CommandLocator` | `runtime-local` | `com.minos.runtime.local.CommandLocator` | fait (groupe c) |
| 105 | renommage | `runtime-local` | `com.minos.runtime.CompositeProviderRuntimeManager` | `runtime-local` | `com.minos.runtime.local.CompositeProviderRuntimeManager` | fait (groupe c) |
| 106 | renommage | `runtime-local` | `com.minos.runtime.DistributedArtifactBundleStore` | `runtime-local` | `com.minos.runtime.local.DistributedArtifactBundleStore` | fait (groupe c) |
| 107 | renommage | `runtime-local` | `com.minos.runtime.DistributedArtifactCachePolicy` | `runtime-local` | `com.minos.runtime.local.DistributedArtifactCachePolicy` | fait (groupe c) |
| 108 | renommage | `runtime-local` | `com.minos.runtime.DistributedIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.DistributedIndexerExecutor` | fait (groupe c) |
| 109 | renommage | `runtime-local` | `com.minos.runtime.FileResumableRunMarkers` | `runtime-local` | `com.minos.runtime.local.FileResumableRunMarkers` | fait (groupe c) |
| 110 | renommage | `runtime-local` | `com.minos.runtime.IndexerProcessPlan` | `runtime-local` | `com.minos.runtime.local.IndexerProcessPlan` | fait (groupe c) |
| 111 | renommage | `runtime-local` | `com.minos.runtime.IndexerProcessPlanFactory` | `runtime-local` | `com.minos.runtime.local.IndexerProcessPlanFactory` | fait (groupe c) |
| 112 | renommage | `runtime-local` | `com.minos.runtime.LinuxBubblewrapWorkerSandboxBackend` | `runtime-local` | `com.minos.runtime.local.LinuxBubblewrapWorkerSandboxBackend` | fait (groupe c) |
| 113 | renommage | `runtime-local` | `com.minos.runtime.LinuxCgroupJob` | `runtime-local` | `com.minos.runtime.local.LinuxCgroupJob` | fait (groupe c) |
| 114 | renommage | `runtime-local` | `com.minos.runtime.LocalIsolatedIndexWorker` | `runtime-local` | `com.minos.runtime.local.LocalIsolatedIndexWorker` | fait (groupe c) |
| 115 | renommage | `runtime-local` | `com.minos.runtime.LocalProviderWorkspace` | `runtime-local` | `com.minos.runtime.local.LocalProviderWorkspace` | fait (groupe c) |
| 116 | renommage | `runtime-local` | `com.minos.runtime.ProcessIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.ProcessIndexerExecutor` | fait (groupe c) |
| 117 | renommage | `runtime-local` | `com.minos.runtime.ProcessOwnershipTracker` | `runtime-local` | `com.minos.runtime.local.ProcessOwnershipTracker` | fait (groupe c) |
| 118 | renommage | `runtime-local` | `com.minos.runtime.ProcessSandboxCapableIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.ProcessSandboxCapableIndexerExecutor` | fait (groupe c) |
| 119 | renommage | `runtime-local` | `com.minos.runtime.ProcessTreeTermination` | `runtime-local` | `com.minos.runtime.local.ProcessTreeTermination` | fait (groupe c) |
| 120 | renommage | `runtime-local` | `com.minos.runtime.ProviderProcessEnvironment` | `runtime-local` | `com.minos.runtime.local.ProviderProcessEnvironment` | fait (groupe c) |
| 121 | renommage | `runtime-local` | `com.minos.runtime.ProviderResidueReclamation` | `runtime-local` | `com.minos.runtime.local.ProviderResidueReclamation` | fait (groupe c) |
| 122 | renommage | `runtime-local` | `com.minos.runtime.ProviderWorkspaceFiles` | `runtime-local` | `com.minos.runtime.local.ProviderWorkspaceFiles` | fait (groupe c) |
| 123 | renommage | `runtime-local` | `com.minos.runtime.ProviderWriteQuota` | `runtime-local` | `com.minos.runtime.local.ProviderWriteQuota` | fait (groupe c) |
| 124 | renommage | `runtime-local` | `com.minos.runtime.ProviderWriteQuotaSupervisor` | `runtime-local` | `com.minos.runtime.local.ProviderWriteQuotaSupervisor` | fait (groupe c) |
| 125 | renommage | `runtime-local` | `com.minos.runtime.RunDirectoryRetention` | `runtime-local` | `com.minos.runtime.local.RunDirectoryRetention` | fait (groupe c) |
| 126 | renommage | `runtime-local` | `com.minos.runtime.StrongProcessOwnershipIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor` | fait (groupe c) |
| 127 | renommage | `runtime-local` | `com.minos.runtime.WindowsAppContainerWorkerSandboxBackend` | `runtime-local` | `com.minos.runtime.local.WindowsAppContainerWorkerSandboxBackend` | fait (groupe c) |
| 128 | renommage | `runtime-local` | `com.minos.runtime.WindowsContainmentScript` | `runtime-local` | `com.minos.runtime.local.WindowsContainmentScript` | fait (groupe c) |
| 129 | renommage | `runtime-local` | `com.minos.runtime.WindowsExecutionPathIdentityProvider` | `runtime-local` | `com.minos.runtime.local.WindowsExecutionPathIdentityProvider` | fait (groupe c) |
| 130 | renommage | `runtime-local` | `com.minos.runtime.WindowsJobObjectProcessOwnership` | `runtime-local` | `com.minos.runtime.local.WindowsJobObjectProcessOwnership` | fait (groupe c) |
| 131 | renommage | `runtime-local` | `com.minos.runtime.WorkerResourceContainment` | `runtime-local` | `com.minos.runtime.local.WorkerResourceContainment` | fait (groupe c) |
| 132 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxBackend` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxBackend` | fait (groupe c) |
| 133 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxBackends` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxBackends` | fait (groupe c) |
| 134 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxQualification` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxQualification` | fait (groupe c) |
| 135 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxSelection` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxSelection` | fait (groupe c) |
| 136 | renommage | `storage-local` | `com.minos.incremental.FileProjectFingerprintSnapshotStore` | `storage-local` | `com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore` | fait (groupe d5) |
| 137 | renommage | `storage-local` | `com.minos.orchestration.FileIndexStateStore` | `storage-local` | `com.minos.storage.local.orchestration.FileIndexStateStore` | fait (groupe d) |
| 138 | renommage | `storage-local` | `com.minos.orchestration.IndexRunRetentionPolicy` | `storage-local` | `com.minos.storage.local.orchestration.IndexRunRetentionPolicy` | fait (groupe d) |
| 139 | renommage | `storage-local` | `com.minos.orchestration.IndexRunRetentionService` | `storage-local` | `com.minos.storage.local.orchestration.IndexRunRetentionService` | fait (groupe d) |
| 140 | renommage | `storage-local` | `com.minos.orchestration.ProjectIndexLease` | `storage-local` | `com.minos.storage.local.orchestration.ProjectIndexLease` | fait (groupe d) |
| 141 | renommage | `storage-local` | `com.minos.registry.InterProcessLocalProjectRegistry` | `storage-local` | `com.minos.storage.local.registry.InterProcessLocalProjectRegistry` | fait (groupe d) |
| 142 | renommage | `storage-local` | `com.minos.registry.LocalProjectRegistry` | `storage-local` | `com.minos.storage.local.registry.LocalProjectRegistry` | fait (groupe d) |
| 143 | renommage | `storage-local` | `com.minos.registry.ProjectPathMappingStore` | `storage-local` | `com.minos.storage.local.registry.ProjectPathMappingStore` | fait (groupe d) |
| 144 | renommage | `storage-local` | `com.minos.storage.LocalStorageBackend` | `storage-local` | `com.minos.storage.local.LocalStorageBackend` | fait (groupe d) |
| 145 | renommage | `storage-local` | `com.minos.storage.LocalStorageRetentionService` | `storage-local` | `com.minos.storage.local.LocalStorageRetentionService` | fait (groupe d) |
| 146 | renommage | `storage-local` | `com.minos.storage.SerializedRuntimeObservationStore` | `storage-local` | `com.minos.storage.local.SerializedRuntimeObservationStore` | fait (groupe d) |
| 147 | renommage | `storage-local` | `com.minos.store.ActiveSnapshotRepository` | `storage-local` | `com.minos.storage.local.store.ActiveSnapshotRepository` | fait (groupe d) |
| 148 | renommage | `storage-local` | `com.minos.store.CodeKnowledgeSnapshotBinaryCodec` | `storage-local` | `com.minos.storage.local.store.CodeKnowledgeSnapshotBinaryCodec` | fait (groupe d) |
| 149 | renommage | `storage-local` | `com.minos.store.EnvironmentHostedTenantKeyProvider` | `storage-local` | `com.minos.storage.local.store.EnvironmentHostedTenantKeyProvider` | fait (groupe d) |
| 150 | renommage | `storage-local` | `com.minos.store.FileHostedControlPlaneStore` | `storage-local` | `com.minos.storage.local.store.FileHostedControlPlaneStore` | fait (groupe d) |
| 151 | renommage | `storage-local` | `com.minos.store.FileRuntimeObservationStore` | `storage-local` | `com.minos.storage.local.store.FileRuntimeObservationStore` | fait (groupe d) |
| 152 | renommage | `storage-local` | `com.minos.store.FileSemanticVectorStore` | `storage-local` | `com.minos.storage.local.store.FileSemanticVectorStore` | fait (groupe d) |
| 153 | renommage | `storage-local` | `com.minos.store.FileSymbolSnapshotStore` | `storage-local` | `com.minos.storage.local.store.FileSymbolSnapshotStore` | fait (groupe d) |
| 154 | renommage | `storage-local` | `com.minos.store.ProjectMutationSemanticVectorStore` | `storage-local` | `com.minos.storage.local.store.ProjectMutationSemanticVectorStore` | fait (groupe d) |
| 155 | renommage | `storage-local` | `com.minos.store.SnapshotBinaryCodecSupport` | `storage-local` | `com.minos.storage.local.store.SnapshotBinaryCodecSupport` | fait (groupe d) |
| 156 | renommage | `storage-local` | `com.minos.store.SnapshotCodec` | `storage-local` | `com.minos.storage.local.store.SnapshotCodec` | fait (groupe d) |
| 157 | renommage | `storage-local` | `com.minos.store.SnapshotCodecV1` | `storage-local` | `com.minos.storage.local.store.SnapshotCodecV1` | fait (groupe d) |
| 158 | renommage | `storage-local` | `com.minos.store.SnapshotCodecV2` | `storage-local` | `com.minos.storage.local.store.SnapshotCodecV2` | fait (groupe d) |
| 159 | renommage | `storage-local` | `com.minos.store.SnapshotCompactionService` | `storage-local` | `com.minos.storage.local.store.SnapshotCompactionService` | fait (groupe d) |
| 160 | renommage | `storage-local` | `com.minos.store.SnapshotIntegrityService` | `storage-local` | `com.minos.storage.local.store.SnapshotIntegrityService` | fait (groupe d) |
| 161 | renommage | `storage-local` | `com.minos.store.SnapshotProjectLease` | `storage-local` | `com.minos.storage.local.store.SnapshotProjectLease` | fait (groupe d) |
| 162 | renommage | `storage-local` | `com.minos.store.SnapshotRepository` | `storage-local` | `com.minos.storage.local.store.SnapshotRepository` | fait (groupe d) |
| 163 | renommage | `storage-local` | `com.minos.store.SnapshotRetentionPolicy` | `storage-local` | `com.minos.storage.local.store.SnapshotRetentionPolicy` | fait (groupe d) |
| 164 | renommage | `storage-local` | `com.minos.store.SnapshotRetentionService` | `storage-local` | `com.minos.storage.local.store.SnapshotRetentionService` | fait (groupe d) |
| 165 | suppression | `cli` | `com.minos.cli.LocalProjectOperations` | `—` | supprimé → appelants sur `com.minos.application.LocalProjectOperations` | fait (groupe l) |
| 166 | suppression | `cli` | `com.minos.cli.LocalProjectSymbolQuery` | `—` | supprimé → appelants sur `com.minos.application.LocalProjectSymbolQuery` | fait (groupe l) |
| 167 | suppression | `cli` | `com.minos.cli.ProjectOperations` | `—` | supprimé → appelants sur `com.minos.application.ProjectOperations` | fait (groupe l) |
| 168 | suppression | `cli` | `com.minos.cli.ProjectSymbolQuery` | `—` | supprimé → appelants sur `com.minos.application.ProjectSymbolQuery` | fait (groupe l) |

Total : 71 déplacements, 93 renommages, 4 suppressions.

**Classes créées par le lot** (hors table, aucune ancienne) : `com.minos.cli.McpLaunchRoute` (SPI public) et `com.minos.cli.McpLaunchRoutes` (résolveur package-private) dans minos-cli, `com.minos.app.McpLaunchRouteProvider` (fournisseur) dans minos-app, avec `minos-app/src/main/resources/META-INF/services/com.minos.cli.McpLaunchRoute`.

**Tests** : la table ne liste que la production. Les tests ont suivi leur package au même commit ; les 45 tests en package étranger de l'inventaire sont relogés comme indiqué au journal (e3, d5, g2 et groupe m) ; tests ajoutés : `MinosVersionContractTest`, `ShadedJarVersionIT`, `ShadedJarMcpEntryPointIT`, `McpLaunchRoutesTest`, `McpLaunchRoutingTest`, et la moitié bootstrap de la scission du test runtime (`RuntimeIntelligenceFileAdaptersTest`).

### 4.1 Décomptes par module (fichiers `*.java`, base `10486cb7` → tête du lot)

| Module | Production avant | Production après | Écart | Tests avant | Tests après | Écart |
|---|---|---|---|---|---|---|
| `minos-api` | 13 | 13 | +0 | 13 | 13 | +0 |
| `minos-app` | 8 | 7 | −1 | 40 | 32 | −8 |
| `minos-application` | 155 | 105 | −50 | 68 | 30 | −38 |
| `minos-bootstrap` | 6 | 6 | +0 | 30 | 27 | −3 |
| `minos-cli` | 37 | 37 | +0 | 32 | 40 | +8 |
| `minos-domain` | 55 | 36 | −19 | 7 | 5 | −2 |
| `minos-engine` | 92 | 161 | +69 | 26 | 70 | +44 |
| `minos-integration-git` | 6 | 6 | +0 | 7 | 7 | +0 |
| `minos-mcp` | 8 | 8 | +0 | 9 | 9 | +0 |
| `minos-nexus` | 4 | 4 | +0 | 3 | 3 | +0 |
| `minos-provider-scip` | 33 | 33 | +0 | 32 | 34 | +2 |
| `minos-runtime-local` | 34 | 34 | +0 | 48 | 48 | +0 |
| `minos-storage-local` | 29 | 29 | +0 | 44 | 46 | +2 |
| `minos-storage-postgresql` | 18 | 18 | +0 | 15 | 16 | +1 |
| **Total** | 498 | 497 | −1 | 374 | 380 | +6 |

Production : −4 alias supprimés, +3 classes du SPI `mcp`. Tests : +6 fichiers (les cinq tests ajoutés listés ci-dessus et le test d'application issu de la scission). Packages de production : 34 → 45, chacun détenu par un seul module.

## 5. Références littérales à mettre à jour, à assertion identique

> Toutes mises à jour, commit par commit, à assertion identique : détail au journal (§ 6) de chaque groupe.

Relevé (hors `scripts/history/`) des fichiers qui citent un chemin ou un FQN appelé à changer. Chaque lot met à jour les siens et le consigne au journal.

| Fichier | Références |
|---|---|
| `scripts/quality/check-jacoco.py` | préfixes `com/minos/store/…`, `com/minos/semantic/…`, `com/minos/dynamic/`, `com/minos/hosted/`, `com/minos/runtime/…`, `com/minos/git/…`, `com/minos/cli/McpBackend*`, `com/minos/cli/DockerRuntimeBootstrap`, `com/minos/integration/nexus/` (l'ensemble des classes couvertes par chaque portée doit rester identique) |
| `scripts/quality/check-hosted-control-plane-consistency.py` | chemins `minos-application/…/hosted/*`, `minos-domain/…/hosted/*`, `minos-storage-local/…/store/*` |
| `scripts/quality/check-remote-distributed-consistency.py` | chemins et FQN de runtime-local et integration-git |
| `scripts/quality/check-runtime-dynamic-consistency.py`, `check-semantic-retrieval-consistency.py`, `check-polyglot-provider-consistency.py`, `check-vertical-decomposition-consistency.py` | chemins de `dynamic`, `semantic`, `discovery`, `orchestration`, `store`, `runtime` |
| `scripts/remediation/check-mnd.py`, `check-mne.py`, `check-post-mne.py`, `check-p0-p2.py`, `check-audit-remediation-v2.py`, `check-minos-01.py`, `check-post228-hardening.py` | chemins de sources et de ressources (`com/minos/runtime/windows…`) |
| `scripts/docs/product-facts.py` | `minos-runtime-local/…/runtime/WorkerResourceContainment.java`, `WorkerSandboxQualification.java` |
| `scripts/m15/M15FinalQueryProbe.java`, `scripts/m26/M26RuntimeFixture.java`, `scripts/m27/M27HostedFixture.java` (gelés par assertion, ADR 0043) | imports `com.minos.store.*`, `com.minos.registry.*`, `com.minos.cli.LocalProjectSymbolQuery` |
| `minos-runtime-local/src/main/resources/META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` | `com.minos.runtime.WindowsExecutionPathIdentityProvider` |
| `docker/compose.mcp.prod.yaml`, `docker/compose.mcp.connected.yaml` | `com.minos.cli.MinosLauncher`, `com.minos.cli.DockerRuntimeBootstrap` — inchangés si § 3.2 (1) est retenu |
| `docs/developer/README.md`, `docs/architecture/arc42/05-vue-blocs.md`, `08-concepts-transverses.md`, `docs/developer/*.md` | description des packages par module |

Points d'entrée dont le FQN ne change pas : `com.minos.cli.MinosLauncher` (`docs/user/cli.md`, `mainClass` du jar, compose, `build-windows-distribution.ps1`, `qualify-docker-release.sh`), `com.minos.integration.nexus.NexusExportBridgeMain`, `com.minos.mcp.MinosMcpServer`, `com.minos.adapter.scip.runtime.StampManagedProviderMarkers` (`docker/Dockerfile.mcp.release`).

## 6. Journal

- 2026-09-29 — impl-archi, jalon 1 : worktree `a3-packages` sur `10486cb7`, gates de base verts (`check-module-boundaries.py` SUCCESS 14 modules / 498 sources, `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py`). Inventaire recalculé (§ 2), identique à celui de l'orchestrateur (14 packages, 45 tests). Accès package-private relevés dans le bytecode (§ 2.3). Décisions vérifiées (§ 3.1), cinq points soumis (§ 3.2).
- 2026-09-29 — impl-archi : `073f4a43` (docs) — ce suivi et l'ADR 0044, ajouté à l'index des ADR. Gates docs verts.
- 2026-09-29 — impl-archi : `26e63775` (build) — `check-module-boundaries.py` : règle A3 « un package, un module » (production éclatée et test en package étranger) en cliquet (`TOLERATED_SPLIT_PACKAGES` 14, `TOLERATED_FOREIGN_TESTS` 45, entrée périmée ou élargie = échec) et règle A7 (`MODULES` confronté aux `<modules>` du POM racine, profils compris). Aucune règle existante assouplie ; ligne de succès : `packagePolicy=A3-ADR-0044, reactor=root-pom-modules` ajoutés. Auto-test `scripts/architecture/test_check_module_boundaries.py` (13 cas, vert) branché dans le job `invariants` de `pr-ci.yml` après le script ; quatre mutations du script (règle des tests neutralisée, règle de production neutralisée, A7 neutralisé, détection des entrées périmées neutralisée) toutes rouges. **Preuve rouge** sur l'arbre de `10486cb7` avec cliquet vide : `M21 MODULE BOUNDARY CONSISTENCY FAILED`, 59 violations (14 packages éclatés + 45 tests en package étranger), sortie conservée ; A7 rejoué sur l'arbre réel : `MODULES` privé de `minos-bootstrap` → rouge, `MODULES` augmenté de `minos-intellij` (Gradle, hors reactor) → rouge. Reproduction : charger le script par `importlib`, remplacer les deux listes de tolérance par des listes vides dans les valeurs par défaut de `check_package_ownership`, puis appeler `main()`. Verts sur la tête : `check-module-boundaries.py` (14 modules, 498 sources, 34 packages), `check-current-docs.py`, `product-facts.py --check`, `check-milestone-artifact-references.py` (95 scripts), `check-workflow-pins.py`, les sept `scripts/remediation/check-*.py` du job `invariants`.
- 2026-09-29 — impl-archi, jalon 2 groupe a (`adapter.scip`, `883fb35d`) : `ScipSymbolSnapshotRequest` et `ScipSymbolSnapshotReport` (engine) passent de `com.minos.adapter.scip` à `com.minos.orchestration`, à côté du port `ScipArtifactImporter`. 13 fichiers Java touchés (imports seulement, plus les deux déclarations de package) ; aucune référence littérale hors Java à mettre à jour (seuls des suivis historiques les citent). Entrée `com.minos.adapter.scip` retirée du cliquet ; les 6 tests de minos-app dans `com.minos.adapter.scip` restent tolérés (production désormais dans provider-scip seul). Aucune portée JaCoCo ne cite ces classes. Ruptures ajoutées à `docs/user/java-api.md`. Vérifié : `-pl minos-engine,minos-provider-scip,minos-application -am test` vert, `test-compile` de tout le reactor vert, gates verts.
- 2026-09-29 — impl-archi : `7c97bff9` (test) — constats verif-archi du jalon 1 traités (§ 7 : V-A3-01, 02, 03, 05 résolus).
- 2026-09-29 — impl-archi, jalon 2 groupe b (`git`, `d3fb624c`) : les 6 classes d'integration-git et leurs 7 tests passent de `com.minos.git` à `com.minos.integration.git` ; le port `GitIntelligence` reste dans engine (`com.minos.git`), désormais seul propriétaire du package. Imports : `DefaultMinosApplicationComposer` (bootstrap), `GitActivityCommandTest` (cli), `GitIntelligenceService` importe le port. Références littérales mises à jour à assertion identique (seul le chemin change) : `check-jacoco.py` (portée `remote-distributed` : 3 préfixes, et le seuil par classe de `JGitCloneDeadline`), `check-remote-distributed-consistency.py` (5 lectures de fichiers, et l'interdiction d'import `import com.minos.integration.git.JGitRemoteRepositoryMaterializer` dans `LocalRemoteIndexOperations`, gardée sur le nouveau nom pour ne pas devenir vide), `check-post-mne.py` (3 lectures). Docs : `docs/developer/README.md` (table des packages), `arc42/05-vue-blocs.md` (sources), ruptures de `java-api.md`. Entrée `com.minos.git` retirée du cliquet. Vérifié : `-pl minos-integration-git,minos-bootstrap -am test`, `test-compile` du reactor, `GitActivityCommandTest` verts ; les 23 gates statiques verts.
- 2026-09-29 — impl-archi, jalon 2 groupe c1 (`runtime`, runtime-local, `8c0eb0ef`) : les 34 classes de runtime-local et leurs 48 tests passent de `com.minos.runtime` à `com.minos.runtime.local` ; les 4 ports d'engine restent dans `com.minos.runtime`. **Ressources** : `windows-appcontainer-sandbox-v4.ps1.template`, `windows-job-object-owner-v1.ps1.template`, `windows-path-identity-v1.ps1` et les 13 `windows-fragments/*.ps1frag` déplacés (`git mv`) sous `com/minos/runtime/local/`, les 2 golden de test sous `com/minos/runtime/local/golden/` ; les 6 chemins de ressource absolus des sources (`WindowsContainmentScript`, `WindowsAppContainerWorkerSandboxBackend`, `WindowsJobObjectProcessOwnership`, `WindowsExecutionPathIdentityProvider`, `WindowsContainmentScriptTest` ×2) suivent. `.gitattributes` épingle ces fichiers par extension, pas par chemin : aucun changement. **Service** : `META-INF/services/com.minos.orchestration.ExecutionPathIdentityProvider` nomme désormais `com.minos.runtime.local.WindowsExecutionPathIdentityProvider` (nom du fichier inchangé : c'est le port d'engine). Imports réécrits dans bootstrap (5 sources, 3 tests), provider-scip (12 sources, 3 tests), cli (4 tests). Références littérales mises à jour à assertion identique : `product-facts.py` (2 lectures), `check-jacoco.py` (portées `m25-remote-distributed-indexing`, `provider-execution-trust-boundary`, `provider-write-quota-supervisor`, `provider-sandbox-linux`, `provider-sandbox-windows` : 23 préfixes et 2 seuils par classe — chiffres corrigés au commit suivant), `check-remote-distributed-consistency.py` (lectures, 2 interdictions d'import), `check-vertical-decomposition-consistency.py`, `check-minos-01.py` (répertoires source/test et lanceur), `check-mnd.py`, `check-mne.py`, `check-p0-p2.py` (lectures et interdiction `import com.minos.runtime.local.CommandLocator;` dans `DockerMcpTransport`), `check-post-mne.py`, `check-post228-hardening.py` (sources, gabarit et fragment). Docs : `developer/README`, `arc42/05`, ruptures de `java-api.md`. Noms de journaux des 8 classes journalisantes : `com.minos.runtime.local.*` (consigné dans l'ADR). Cliquet : `com.minos.runtime` réduit à `{minos-application, minos-engine}` (reste `MinosVersion`). Vérifié : `-pl minos-runtime-local,minos-provider-scip,minos-bootstrap -am test` vert (runtime-local 428 tests / 62 ignorés, provider-scip 86, bootstrap 85 / 1 ignoré, dont `WindowsContainmentScriptTest` : lanceurs assemblés identiques aux golden qualifiés), `test-compile` du reactor vert, tests cli concernés (21) verts ; les 23 gates statiques verts.
- 2026-09-29 — impl-archi, jalon 2 groupe c2 (`MinosVersion`, `92a27a36`) : `com.minos.runtime.MinosVersion` **déplacé** de minos-application dans minos-engine (FQN inchangé, `git mv` à 100 %, aucune autre ligne touchée). `com.minos.runtime` n'appartient plus qu'à engine : entrée retirée du cliquet. **Preuve de comportement inchangé**, tests ajoutés avec le déplacement, contrat prouvé par mutation (verif-archi) ; exécutés aussi, non commités, sur l'état précédant le déplacement : `MinosVersionContractTest` (hors JAR versionné → repli `1.0.1-SNAPSHOT` ; classe copiée dans un JAR isolé dont le manifeste porte `Implementation-Version: 9.8.7-contract` et chargée par un chargeur dédié → `9.8.7-contract` ; version vide → repli) et `ShadedJarVersionIT` (le JAR ombré répond à `--version` exactement `MINOS <Implementation-Version de son manifeste>`). Verts sur `8c0eb0ef` + tests et après le déplacement, avec `MinosLauncherTest`, `StableCliHelpTest`, `ShadedJarSmokeIT`, `ShadedJarCompositionRootIT`. Le JAR ombré contient une seule `com/minos/runtime/MinosVersion.class`, son fichier de service `ExecutionPathIdentityProvider` nomme `com.minos.runtime.local.WindowsExecutionPathIdentityProvider`, et aucune ressource ne reste sous `com/minos/runtime/` hors `local/`. `minos-mcp` (`MinosMcpServer`) voit la classe par la dépendance transitive d'application vers engine, comme il voit déjà `com.minos.diagnostics` et `com.minos.orchestration` : aucune dépendance Maven ajoutée.
- 2026-09-29 — impl-archi, jalon 2 groupe d1 (`store`, storage-local, `3bdb7eea`) : les 18 classes de storage-local et leurs 25 tests passent de `com.minos.store` à `com.minos.storage.local.store` ; les 7 ports et modèles d'engine gardent `com.minos.store`. Imports réécrits dans 36 fichiers hors du package (bootstrap, provider-scip, storage-postgresql, cli, app, et les trois fixtures gelées `scripts/m15/M15FinalQueryProbe.java`, `scripts/m26/M26RuntimeFixture.java`, `scripts/m27/M27HostedFixture.java`). Références littérales mises à jour à assertion identique : `check-jacoco.py` (portées `persistence-cache-indexes` 4 préfixes — `InMemoryCodeKnowledgeStore` et `SnapshotQueryView`, d'engine, gardent `com/minos/store/` —, `semantic-vector-store` 1, `m26-runtime-dynamic-intelligence` 1, `m27-team-hosted-control-plane` 2), `check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py`, `check-semantic-retrieval-consistency.py`, `check-audit-remediation-v2.py`, `check-mnd.py`, `check-mne.py`, `check-p0-p2.py`. Docs : `arc42/05`, `arc42/08`, `developer/README`, ruptures de `java-api.md`. Entrée `com.minos.store` retirée du cliquet. Vérifié : `-pl minos-storage-local,minos-storage-postgresql,minos-provider-scip,minos-bootstrap -am test` vert (storage-local 210 / 8 ignorés, storage-postgresql 59, provider-scip 86, bootstrap 85 / 1), `test-compile` du reactor, tests cli concernés verts ; gates statiques verts.
- 2026-09-29 — impl-archi : `5eefb2ec` (docs) — V-A3-06 résolu (amendement de l'ADR 0022 étendu aux adaptateurs), V-A3-07 consigné.
- 2026-09-29 — impl-archi, jalon 2 groupe d2 (`registry`, storage-local, `a2f8a69b`) : `LocalProjectRegistry`, `InterProcessLocalProjectRegistry`, `ProjectPathMappingStore` et leurs 7 tests passent de `com.minos.registry` à `com.minos.storage.local.registry` ; les 6 ports et modèles d'engine gardent `com.minos.registry`. Imports réécrits dans 30 fichiers hors du package (bootstrap, storage-postgresql, cli, app, fixtures gelées m15/m26/m27, `LocalStorageBackend`). Références littérales : `check-mnd.py` (1 lecture), `check-post-mne.py` (2 lectures), à assertion identique ; aucune portée JaCoCo concernée. Docs : `developer/README`, ruptures de `java-api.md`. Entrée `com.minos.registry` retirée du cliquet. Vérifié : `-pl minos-storage-local,minos-storage-postgresql,minos-bootstrap -am test`, `test-compile` du reactor, tests cli concernés verts ; gates statiques verts.
- 2026-09-29 — impl-archi, jalon 2 groupe d3 (`storage`, storage-local, `57817bdd`) : `LocalStorageBackend`, `LocalStorageRetentionService`, `SerializedRuntimeObservationStore` et leurs 2 tests passent de `com.minos.storage` à `com.minos.storage.local` ; les ports d'engine gardent `com.minos.storage`, et `StorageBackends` (application) y reste jusqu'à son propre renommage. Imports : bootstrap (`DefaultMinosApplicationComposer`, `StorageBackendSelection`) et **`A2CompositionCharacterizationTest`** (une ligne d'import, conformément à l'arbitrage 4 ; aucune assertion touchée, golden inchangés). Références littérales à assertion identique : `check-jacoco.py` (portée `m30-storage-backend-selection`, préfixe `LocalStorageBackend` seul — `StorageBackend*` d'engine et `StorageBackends` d'application gardent `com/minos/storage/`), `check-runtime-dynamic-consistency.py`, `check-mnd.py`, `check-post-mne.py` (2 lectures). Outil : le réécriveur de chemins ne vise plus que les fichiers des classes déplacées (une première passe réécrivait aussi les sous-packages `store`/`registry` déjà renommés ; annulée avant tout commit). Docs : `developer/README`, ruptures de `java-api.md`. Cliquet : `com.minos.storage` réduit à `{minos-application, minos-engine}`. Vérifié : `-pl minos-storage-local,minos-storage-postgresql,minos-bootstrap -am test`, `test-compile` du reactor, `A2CompositionCharacterizationTest` 5/5 ; gates statiques verts.
- 2026-09-29 — impl-archi, jalon 2 groupe d4 (`orchestration`, storage-local, `814130ba`) : `FileIndexStateStore`, `IndexRunRetentionPolicy`, `IndexRunRetentionService`, `ProjectIndexLease` et leurs 10 tests passent de `com.minos.orchestration` à `com.minos.storage.local.orchestration` ; engine garde ses ports et modèles, application ses 12 services jusqu'au lot de déplacement. **Visibilité élargie (arbitrage 2)** : `IndexingRun.portable(Path)` (engine) passe de package-private à `public`. Justification : c'est une règle de format sur disque partagée entre le port et ses adaptateurs — la forme portable d'un chemin qui entre dans `IndexingRun.targetKey` et que `FileIndexStateStore` écrit dans les points de contrôle persistés (`<prefix>scope`) ; storage-local l'atteignait par la seule égalité des noms de package. La copier créerait deux définitions d'un même format persistant ; sa Javadoc l'énonce désormais comme telle. Corps inchangé. Imports : bootstrap (5 tests), cli (1 test), `LocalStorageBackend`, `LocalStorageRetentionService` et son test. Aucune référence littérale hors Java (aucun gate, aucune portée JaCoCo ne nomme ces classes ; l'ADR 0039 cite un ancien chemin, historique, laissé tel quel). Docs : `developer/README`, ruptures de `java-api.md`. Cliquet : `com.minos.orchestration` réduit à `{minos-application, minos-engine}`. Vérifié : `-pl minos-storage-local,minos-storage-postgresql,minos-bootstrap -am test`, `test-compile` du reactor, `LocalProjectOperationsM14StateTest` verts ; gates statiques verts.
- 2026-09-29 — impl-archi, jalon 2 groupe d5 (`incremental`, storage-local) **reporté**, sans commit. `FileProjectFingerprintSnapshotStore` renommé seul couperait l'accès de `FileProjectFingerprintSnapshotStoreTest` (bootstrap, `com.minos.incremental`) à ses constantes package-private `MAX_FILES`, `MAX_STRING_BYTES`, `MAX_SNAPSHOT_BYTES`. Ce test (et `FileProjectFingerprintSnapshotStoreSymlinkTest`) utilise aussi `ProjectFingerprintService`, service d'application tant que `incremental` d'application n'est pas descendu dans engine : il ne peut donc pas encore rejoindre storage-local. Plutôt que d'élargir ces constantes, le groupe d5 est fait **juste après** le déplacement de `incremental` d'application vers engine (jalon suivant) : les deux tests rejoindront alors les tests de storage-local dans `com.minos.storage.local.incremental`, et `FileFingerprint.requireSha256` passera en `public` (arbitrage 2) dans ce même commit. Le cliquet garde `com.minos.incremental` à `{minos-application, minos-engine, minos-storage-local}` d'ici là.
- 2026-09-29 — impl-archi, **fin du jalon 2** (tête `814130ba` + ce commit de journal). `./mvnw -pl minos-app -am verify` (sans `clean`) : BUILD SUCCESS, **1 359 tests, 0 échec, 0 erreur, 46 ignorés** = base 1 355/0/46 + les 4 tests ajoutés au groupe c2 (`MinosVersionContractTest` 3, `ShadedJarVersionIT` 1) ; `A2CompositionCharacterizationTest` 5/5, `A2SurfaceCharacterizationTest` 12/12. `git diff --stat 10486cb7 -- minos-app/src/test/resources/characterization` : vide. `check-jacoco.py` sur le rapport agrégé : toutes les portées PASS. **Appartenance aux portées JaCoCo** : comparée classe par classe (bytecode de la tête ramené aux noms de la base par la table de renommage, préfixes de la base contre préfixes de la tête, `prefixMinimums` compris) — les 27 portées désignent exactement les mêmes classes (m25 55, m27 57, m26 32, provider-execution-trust-boundary 31, persistence-cache-indexes 19, resume-orchestration 19, critical-orchestration 8, m30-storage-backend-selection 7…). **Artefacts périmés** : un build sans `clean` laisse sous `minos-runtime-local/target/classes/com/minos/runtime/` les anciennes ressources (le plugin de ressources ne supprime rien) ; `./mvnw clean verify -pl minos-app -am` (ITs seuls) les élimine : le JAR ombré ne contient plus que `com/minos/runtime/local/windows-*` (17 entrées), aucune classe d'adaptateur sous un ancien package, et `ShadedJarCompositionRootIT`, `ShadedJarSmokeIT`, `ShadedJarVersionIT` sont verts. Cliquet : 9 packages éclatés encore tolérés sur 14 (`cli`, `discovery`, `dynamic`, `hosted`, `incremental` — 3 modules —, `integration.nexus`, `orchestration`, `semantic`, `storage` — ces deux derniers réduits à application + engine), 45 tests en package étranger inchangés ; 5 entrées retirées (`adapter.scip`, `git`, `runtime`, `store`, `registry`).
- 2026-09-29 — impl-archi, jalon 3 groupe e1 (`discovery`, `discovery.spi`, `adeaee42`) : `DefaultDiscoveryPlugins`, `ProjectDiscoveryService`, `ProjectIgnorePolicy` et les 4 SPI (`BuildSystemDetector`, `LanguageDetector`, `ProjectDetector`, `SourceRootDetector`) **déplacés** d'application vers engine, FQN inchangés (`git mv` à 100 %, aucune ligne modifiée) ; leurs 4 tests d'application (engine, domain et JUnit seulement) suivent dans les tests d'engine. `com.minos.discovery` et `com.minos.discovery.spi` n'appartiennent plus qu'à engine : entrée retirée du cliquet. Pas de rupture Java (noms inchangés ; application dépend d'engine). Références littérales (chemins `minos-application/…` → `minos-engine/…`, assertions identiques) : `check-polyglot-provider-consistency.py` (3), `check-mnd.py` (2), `check-mne.py` (1). Portées JaCoCo : préfixes inchangés (FQN identiques). Vérifié : `-pl minos-engine,minos-application -am test` (les 4 tests tournent désormais dans engine), `test-compile` du reactor, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe e2 (`incremental`, `orchestration`, `4b8f08a4`) : les 12 classes d'`incremental` et les 12 d'`orchestration` d'application **déplacées** ensemble vers engine (fermeture cyclique : `incremental` utilise `orchestration` et inversement), FQN inchangés, `git mv` à 100 % ; leurs 6 + 17 tests d'application suivent dans les tests d'engine. L'accès package-private `ProjectChangeSet` → `FileFingerprint.requireSha256` devient interne à engine. Cliquet : `com.minos.orchestration` retiré (engine seul), `com.minos.incremental` réduit à `{minos-engine, minos-storage-local}`. Aucune référence littérale de chemin à ces sources ; portées JaCoCo inchangées (FQN identiques). Docs : `arc42/05` (engine : découverte, planification incrémentale, orchestration ; application : dépendances corrigées en `minos-domain`, `minos-engine`). Vérifié : `-pl minos-engine,minos-application,minos-storage-local,minos-bootstrap -am test`, `test-compile` du reactor, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe e3 (tests relogés, `86cb9711`) : `StorageBackendConfigurationTest` (application, `com.minos.storage`, 13 cas) rejoint les tests d'engine — il ne référence que des classes d'engine, dont `MinosRuntimeSettings.testing` et `StorageBackendConfiguration.resolve` package-private, désormais atteints depuis leur propre module. `PostgresAuthoritativeSnapshotConsistencyTest` (bootstrap, `com.minos.storage.postgresql`, 1 cas) rejoint les tests de storage-postgresql : sa seule dépendance à application, `IndexingLifecycleService`, est dans engine depuis e2 ; ses accès aux classes non publiques `PostgresIndexStateStore`, `PostgresJsonCodec`, `PostgresProjectRegistry` et à `PostgresTestSupport` redeviennent internes au module. `git mv` à 100 %, aucune ligne modifiée ; aucune visibilité élargie. Cliquet : l'entrée du test PostgreSQL est retirée (44 tests étrangers tolérés). bootstrap garde sa dépendance de test vers storage-postgresql (`MinosApplicationTest` l'utilise). Vérifié : `-pl minos-engine,minos-application,minos-storage-postgresql,minos-bootstrap -am test` (13/13 et 1/1 dans leurs nouveaux modules, PostgreSQL via Docker), gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe d5 (`incremental`, storage-local ; reporté du jalon 2, arbitrage confirmé ; `05d94082`) : `FileProjectFingerprintSnapshotStore` passe de `com.minos.incremental` à `com.minos.storage.local.incremental` ; `FileProjectFingerprintSnapshotStoreTest` (10 cas) et `FileProjectFingerprintSnapshotStoreSymlinkTest` (4 cas) quittent bootstrap pour les tests de storage-local, dans ce même package : leurs accès aux constantes package-private `MAX_FILES`, `MAX_STRING_BYTES`, `MAX_SNAPSHOT_BYTES` deviennent internes au module, sans élargissement (leur autre dépendance, `ProjectFingerprintService`, est dans engine depuis e2). **Visibilité élargie (arbitrage 2)** : `FileFingerprint.requireSha256(String)` (engine) passe de package-private à `public`, Javadoc de règle de format partagée ajoutée, corps inchangé. Justification : `FileProjectFingerprintSnapshotStore` relit les empreintes persistées de son pointeur actif et doit les valider exactement comme le port (64 caractères hexadécimaux, minuscules `Locale.ROOT`) ; il l'atteignait par la seule égalité des noms de package, et une copie créerait deux définitions d'un même format persistant. Imports : 5 tests bootstrap et 1 test app restés dans `com.minos.incremental`, `LocalStorageBackend`, `LocalStorageRetentionService` et son test. Aucune référence littérale hors Java (l'ADR 0042 cite cet accès comme limite connue : résolu ici). Docs : `developer/README`, ruptures de `java-api.md`. Cliquet : `com.minos.incremental` retiré (engine seul), 2 tests étrangers retirés (42 tolérés). Vérifié : `-pl minos-storage-local,minos-bootstrap -am test` (14/14 dans storage-local), `test-compile` du reactor, `IncrementalIndexingRealFixtureTest` (app) vert, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe f (`hosted`, `a23179b9`) : les 18 classes d'application et les 10 records de domain de `com.minos.hosted` **déplacés** dans engine, FQN inchangés, `git mv` à 100 % ; leurs 11 + 1 fichiers de test suivent (engine, domain et JUnit seulement). Aucune classe de domain hors `hosted` ne nommait ces records ; `minos-api` ne les expose dans aucune signature publique. Seule modification de contenu : `HostedProductionBoundaryTest` lit ses sources depuis la racine du dépôt, son chemin passe de `minos-application/src/main/java/com/minos/hosted` à `minos-engine/…` (assertions inchangées). Références littérales (chemins seuls) : `check-hosted-control-plane-consistency.py` (9 lectures, 2 tests), `check-vertical-decomposition-consistency.py` (constante `HOSTED` et 3 lectures), `check-p0-p2.py` (4). Portées JaCoCo `m27-team-hosted-control-plane` et consorts : préfixe `com/minos/hosted/` inchangé, mêmes classes. Docs : `arc42/05` (domain et engine). Cliquet : `com.minos.hosted` retiré. Vérifié : `-pl minos-engine,minos-application,minos-storage-local,minos-bootstrap,minos-cli,minos-api -am test` (11 classes de test `hosted` dans engine), `test-compile` du reactor, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe g1 (`dynamic`, côté domain, `ee446f23`) : les 9 records de domain (`RuntimeObservation*`, `RuntimeSymbol*`, `CorrelatedRuntime*`, `RuntimeResolutionStatus`) **déplacés** dans engine, à côté du port `RuntimeObservationStore` qui déclare `IOException` et ne pouvait donc pas descendre dans domain ; FQN inchangés, `git mv` à 100 % ; `RuntimeObservationModelTest` suit. Aucune classe de domain hors `dynamic` ne les nommait. Références littérales : `check-runtime-dynamic-consistency.py` (4 lectures, 1 test). Docs : `arc42/05`. Cliquet : `com.minos.dynamic` réduit à `{minos-application, minos-engine}`. Vérifié : `-pl minos-engine,minos-application,minos-storage-local,minos-storage-postgresql,minos-bootstrap,minos-cli -am test`, `test-compile` du reactor, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe g2 (`dynamic`, côté application, `fe2e988e`) : `RuntimeIntelligenceService` et `RuntimeObservationEnvelopeCodec` **renommés** de `com.minos.dynamic` en `com.minos.application.dynamic` (épinglés par `ProjectResolver`, ils ne pouvaient pas descendre). Imports : `MinosApplication`, `RuntimeIntelligenceRenderer`, cli (`MinosCli`, `RuntimeCommand`, `RuntimeCommandTest`). **Scission de `RuntimeIntelligenceServiceTest` (arbitrage 3, option b)**, constructeur à `Clock` resté package-private :

  | Cas d'origine (bootstrap, `com.minos.dynamic`, 30 assertions) | Après la scission |
  |---|---|
  | `importsStrictPartialEvidenceAndReportsResolutionHotPathsAndSymbolFacts` (22 assertions, dont `importedAt == IMPORTED_AT`) | **application** `com.minos.application.dynamic.RuntimeIntelligenceServiceTest`, identique (22, horloge figée, doublures en mémoire) ; **bootstrap** `com.minos.bootstrap.RuntimeIntelligenceFileAdaptersTest`, même cas sur les adaptateurs fichiers réels (22, dont `importedAt` de l'import idempotent égal à celui du premier import, horloge système) |
  | `rejectsProjectAndSnapshotMisalignmentAndStaleSessionQueries` (4) | application, identique (4) ; bootstrap, identique sur les adaptateurs fichiers (4) |
  | `codecFailsClosedOnBomTraversalUnknownKindsAndNonPartialCompleteness` (4) | application, identique (4) — le codec n'utilise aucun adaptateur |
  | **Total** 30 | application 30 (toutes les assertions d'origine, texte inchangé) ; bootstrap 26 (intégration fichiers) |

  Doublures en mémoire (privées au test d'application) : registre (résolution par nom), snapshots (dernier publié actif, symboles triés par identifiant, comme `FileSymbolSnapshotStore`), observations (session immuable par identifiant avec contrôle d'empreinte, liste par import décroissant puis identifiant, comme `FileRuntimeObservationStore`). Non vacuité prouvée par mutation : idempotence de la doublure d'observations neutralisée → rouge ; activation du dernier snapshot publié neutralisée → rouge. Références littérales : `check-runtime-dynamic-consistency.py` (2 lectures de sources ; la lecture du test, qui exige les trois noms de cas, pointe sur le test d'application, qui les porte tous), `check-mnd.py` (1), `check-mne.py` (1) ; `check-jacoco.py` : la portée `m26-runtime-dynamic-intelligence` gagne le préfixe `com/minos/application/dynamic/` à côté de `com/minos/dynamic/`, pour désigner exactement les mêmes classes. Docs : `runtime-dynamic-intelligence.md`, ruptures de `java-api.md`. Cliquet : `com.minos.dynamic` retiré (engine seul), test étranger retiré (41 tolérés). Vérifié : `-pl minos-application,minos-bootstrap -am test` (3/3 et 2/2), `test-compile` du reactor, `RuntimeCommandTest` et `MinosMcpToolsTest` verts, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe h (`semantic`, `4009da25`) : les 10 classes d'application et leurs 4 tests passent de `com.minos.semantic` à `com.minos.application.semantic` ; domain garde ses 5 types. Imports réécrits dans application (`MinosApplication`, `MinosApplicationRuntimeConfiguration`, `SemanticAnalysisResultRenderer`), api (`LocalSemanticCodeIntelligenceApi` et son test — usage interne, aucune signature publique touchée), cli (3 sources, 1 test), mcp (`MinosApplicationMcpBackend`), nexus (`NexusSemanticSignalService` et son test) et 3 tests bootstrap restés dans `com.minos.semantic`. Références littérales (assertions identiques) : `check-jacoco.py` (portées `semantic-learned-provider` 1 préfixe, `semantic-hybrid-retrieval` 6), `check-semantic-retrieval-consistency.py` (5 chemins et le préfixe JaCoCo qu'il exige dans `check-jacoco.py`, gardés cohérents), `check-mnd.py` (2), `check-mne.py` (3). Docs : `arc42/08`, `developer/README`, ruptures de `java-api.md`. Cliquet : `com.minos.semantic` retiré (domain seul) ; les 3 tests bootstrap du package restent tolérés comme tests étrangers. Vérifié : `-pl minos-application,minos-bootstrap,minos-api,minos-nexus,minos-mcp,minos-cli -am test`, `test-compile` du reactor, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 3 groupe i (`storage`, côté application, `a545a5a1`) : `StorageBackends` **renommé** de `com.minos.storage` en `com.minos.application` (il dépend de `MinosApplicationComposers`) ; signature de `open(StorageBackendConfiguration)` inchangée ; l'import devenu redondant de `MinosApplicationComposers` est retiré. Imports : `MinosApplicationComposersTest` et `A2CompositionCharacterizationTest` (une ligne d'import, arbitrage 4, aucune assertion touchée). Références littérales : `check-jacoco.py` (portée `m30-storage-backend-selection`, préfixe `StorageBackends` ; le préfixe `com/minos/storage/StorageBackend` d'engine ne désigne plus que les ports d'engine, comme à la base hors `StorageBackends` désormais nommé explicitement). Ruptures de `java-api.md`. Cliquet : `com.minos.storage` retiré (engine seul). Vérifié : `-pl minos-application,minos-bootstrap -am test`, `test-compile` du reactor, `A2CompositionCharacterizationTest` 5/5, gates statiques verts.
- 2026-09-29 — impl-archi, **fin du jalon 3** (tête `a545a5a1` + ce commit de journal). `./mvnw clean verify -pl minos-app -am` : BUILD SUCCESS, **1 361 tests, 0 échec, 0 erreur, 46 ignorés** = 1 359 du jalon 2 + 2 (les cas du test runtime scindé : 3 en application, qui remplacent les 3 d'origine, et 2 variantes sur adaptateurs fichiers en bootstrap) ; `A2CompositionCharacterizationTest` 5/5, `A2SurfaceCharacterizationTest` 12/12. `git diff --stat 10486cb7 -- minos-app/src/test/resources/characterization` : vide. Portées JaCoCo : les 27 désignent exactement les mêmes classes que la base (comparaison classe par classe, renommages `application.semantic`, `application.dynamic`, `application.StorageBackends` compris). `check-jacoco.py` : toutes PASS sauf `m24-polyglot-provider-platform` (`ManagedPolyglotScipRuntimeManager` 0,232 < 0,28), écart préexistant propre à Windows, chiffres identiques à la base. Visibilités élargies dans le jalon : `FileFingerprint.requireSha256` seulement (arbitrage 2). **Cliquet** : 2 packages éclatés encore tolérés (`com.minos.cli` : app + cli ; `com.minos.integration.nexus` : app + nexus), 41 tests en package étranger ; les 12 autres packages de l'inventaire sont repliés.
- 2026-09-29 — impl-archi, jalon 4 groupe j (`cli` / `app`, `b56413bb`) : `MinosLauncher` et `DockerRuntimeBootstrap` **déplacés** de minos-app dans minos-cli, FQN inchangés (`git mv` à 100 % ; seule modification de `MinosLauncher` : la route `mcp`). Ses accès package-private à `MinosCliRunner` deviennent internes au module. **SPI de la route `mcp`** : interface publique `com.minos.cli.McpLaunchRoute` (`int run(Path home) throws Exception`) et résolveur package-private `McpLaunchRoutes`, qui cherche par `ServiceLoader.load(McpLaunchRoute.class, McpLaunchRoute.class.getClassLoader())`, jamais par le chargeur de contexte. Zéro fournisseur → `IllegalStateException` « MINOS MCP entry point is missing … » ; plusieurs → refus qui nomme les types triés ; aucun chemin dans ces messages, rapportés par le lanceur via `PublicErrorMessages` (`error: MINOS bootstrap failed: …`, code 1). L'ordre de routage est inchangé (`--version`, aide, aide sans état, poignée de main IDE, puis `mcp` avant `MinosApplication.open`). Fournisseur : `com.minos.app.McpLaunchRouteProvider` (délègue à `new McpBackendRouter().run(home)`, comme l'appel direct d'avant) déclaré dans `minos-app/src/main/resources/META-INF/services/com.minos.cli.McpLaunchRoute`. `McpBackend`, `McpBackendConfiguration`, `McpBackendConfigurationStore`, `McpBackendRouter`, `DockerMcpTransport` **renommés** dans `com.minos.app` avec leurs 2 tests. Les 6 autres tests `com.minos.cli` de minos-app (`DockerRuntimeBootstrapTest`, `MinosLauncherTest`, `MinosIdeHandshakeLauncherTest`, `PublicCliDiagnosticTest`, `StableCliHelpTest`, `StableCliIntegrationTest`) rejoignent minos-cli, même package, sans modification. **Tests (rouge puis vert)** : `McpLaunchRoutesTest` (4 cas : zéro fournisseur par la découverte réelle, refus à deux fournisseurs, fournisseur unique, chargeur de contexte ignoré) et `McpLaunchRoutingTest` (2 cas, processus fils : `mcp` routé vers le fournisseur enregistré avant toute création de `MINOS_HOME` ; sans fournisseur, `error: MINOS bootstrap failed: MINOS MCP entry point is missing …`, code 1, aucun chemin, home non créé) — rouges avant l'implémentation (compilation : `McpLaunchRoute`/`McpLaunchRoutes` absents, log `j-red.log`), verts après ; `ShadedJarMcpEntryPointIT` (2 cas) : le JAR ombré déclare exactement `com.minos.app.McpLaunchRouteProvider` dans `META-INF/services/com.minos.cli.McpLaunchRoute` (fusion par `ServicesResourceTransformer`), contient les classes, et la découverte réelle du lanceur, dans un chargeur isolé sur le JAR, trouve ce seul fournisseur. Chaînes de classes vérifiées : `docker/compose.mcp.*.yaml`, `scripts/ci/qualify-docker-release.sh`, `build-windows-distribution.ps1`, `docs/user/cli.md`, `mainClass` du jar : `com.minos.cli.MinosLauncher` et `com.minos.cli.DockerRuntimeBootstrap` inchangés. Références littérales : `check-p0-p2.py` (lecture de `DockerMcpTransport`), `check-post-mne.py` (lecture de `McpBackendConfigurationStore`), `check-jacoco.py` : portée `m29-backend-routing`, préfixes `McpBackend*` renommés ; `DockerRuntimeBootstrap` vivant désormais dans minos-cli avec ses tests, la portée lit le rapport agrégé au lieu du seul rapport de minos-app (mêmes classes, mêmes seuils ; seule source de mesure possible pour une classe de minos-cli). Docs : `arc42/05`, `developer/README`, ruptures de `java-api.md`. Cliquet : `com.minos.cli` retiré. Vérifié : `-pl minos-cli -am test` (dont 4 + 2 nouveaux cas et les 6 tests déplacés), `-pl minos-app -am verify` ciblé (tests `McpBackend*`, caractérisation A2 5/5 et 12/12, `ShadedJar*IT` 4 classes), gates statiques verts.
- 2026-09-29 — impl-archi : constats verif-archi du jalon 3. **V-A3-09** : le littéral du cas BOM de `RuntimeIntelligenceServiceTest` (application) contenait un U+FEFF brut, introduit par l'outil d'écriture lors de la scission ; l'échappement `"\ufeff"` d'origine est rétabli (ligne identique à celle de la base), et un balayage de tous les `*.java` du dépôt ne trouve plus aucun U+FEFF brut. **V-A3-10** : `check-runtime-dynamic-consistency.py` gèle aussi `RuntimeIntelligenceFileAdaptersTest` (bootstrap) et ses deux cas (`importsStrictPartialEvidenceAndReportsResolutionHotPathsAndSymbolFacts`, `rejectsProjectAndSnapshotMisalignmentAndStaleSessionQueries`) — assertion ajoutée, rien d'affaibli ; mutation qui renomme un cas → `M26 RUNTIME DYNAMIC CONSISTENCY FAILED: RuntimeIntelligenceFileAdaptersTest.java: missing required fact: …`, puis vert après restauration.
- 2026-09-29 — impl-archi : `56682e0e` (constats verif-archi du jalon 3 : V-A3-10 et V-A3-11 traités ; V-A3-09 annoncé mais non contenu) et `59345b6f` (V-A3-09 corrigé octet par octet).
- 2026-09-29 — impl-archi, jalon 4 groupe k (`nexus`, `cb4f0b58`) : les 4 classes de minos-nexus (`NexusExportContract`, `NexusExportService`, `NexusSemanticSignalContract`, `NexusSemanticSignalService`) et leurs 3 tests passent de `com.minos.integration.nexus` à `com.minos.nexus` ; `NexusExportBridgeMain`, point d'entrée de processus de NEXUS, garde son FQN dans minos-app, qui devient le seul propriétaire de `com.minos.integration.nexus`. Le contrat JSON versionné (ADR 0020) ne nomme aucune classe : inchangé. Imports : cli (`MinosCliRunner`, `NexusExportCommand`), app (`NexusExportIntegrationTest`). Références littérales : `check-mne.py` (1 lecture) ; `check-jacoco.py` portée `nexus-export` : préfixes `com/minos/nexus/` et `com/minos/integration/nexus/` (ce dernier désigne toujours `NexusExportBridgeMain`), mêmes classes. Docs : `developer/README`, ruptures de `java-api.md`. Cliquet : entrée retirée — **aucun package de production éclaté ne reste** (0/14). Vérifié : `-pl minos-nexus,minos-cli -am test`, `NexusExportIntegrationTest` et caractérisation A2 (app) verts, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 4 groupe l (alias CLI, `751ba98d`) : `com.minos.cli.ProjectOperations`, `ProjectSymbolQuery` (interfaces vides qui étendaient celles de `com.minos.application`), `LocalProjectOperations` et `LocalProjectSymbolQuery` (façades dont chaque méthode délègue à l'implémentation de `com.minos.application` du même nom, sans autre logique — vérifié avant suppression) **supprimés** ; ce sont les quatre seuls `@Deprecated` de minos-cli (le texte « Deprecated compatibility import » d'`IndexCommand` est une aide d'usage, pas un alias). Leurs appelants pointent sur le type d'origine : 11 sources de minos-cli (`MinosCliRunner`, `MinosCli`, commandes), 14 tests de minos-cli, 3 tests de minos-app, la fixture gelée `scripts/m15/M15FinalQueryProbe.java`. Les constructeurs publics de minos-cli qui prenaient un alias prennent désormais le type parent : compatible à la source pour tout appelant. Ruptures de `java-api.md` (paragraphe « Alias supprimés »). Vérifié : `-pl minos-cli,minos-api -am test`, `test-compile` du reactor, tests app concernés et caractérisation A2 verts, gates statiques verts.
- 2026-09-29 — impl-archi, jalon 4 groupe m (41 tests en package étranger, `b0586a93`) : chacun est relogé selon la décision du tableau — dans le module testé quand ses dépendances y sont visibles sans violer A2, sinon dans un package de son propre module (`<espace-du-module>.<ancien-suffixe>`). Dépendances relevées fichier par fichier (imports et noms simples de même package ; `MinosApplication.open`, qui exige la racine de composition, compte comme dépendance à bootstrap).

  | Tests | Avant | Après | Raison |
  |---|---|---|---|
  | `DependencyDerivationServiceTest`, `RelatedTestDerivationServiceTest`, `RelationshipQueryServiceTest` | app, `com.minos.query` | **engine**, `com.minos.query` (module testé) | domain et engine seulement |
  | `ScipIndexerCatalogTest`, `ScipPersistentSnapshotExperiment` | app, `com.minos.adapter.scip` | **provider-scip**, `com.minos.adapter.scip` (module testé) | engine, provider-scip, storage-local : dépendances de provider-scip |
  | `M17ProviderPlatformTest`, `M24PolyglotProviderTest`, `ScipRelatedTestSnapshotIntegrationTest`, `ScipSymbolSnapshotImporterTest` | app, `com.minos.adapter.scip` | app, `com.minos.app.adapter.scip` | utilisent application (et cli) |
  | `M17ProviderSurfaceIntegrationTest`, `ProviderCatalogPortTest`, `SharedMinosApplicationIntegrationTest` | app, `com.minos.application` | app, `com.minos.app.application` | api, cli, mcp ou provider-scip |
  | `ArchitectureRealFixtureMeasurementTest`, `CodeSearchBenchmark`, `ImpactAnalysisRealFixtureTest`, `IncrementalIndexingRealFixtureTest`, `MinosMcpServerIntegrationTest`, `SymbolQueryServiceTest` | app, `com.minos.architecture` / `context` / `impact` / `incremental` / `mcp` / `query` | app, `com.minos.app.architecture` / `.context` / `.impact` / `.incremental` / `.mcp` / `.query` | adaptateurs (storage-local, provider-scip), cli, ou `com.minos.output` (application) pour `SymbolQueryServiceTest` |
  | 6 tests `com.minos.application`, 2 `architecture`, 1 `impact`, 4 `incremental`, 3 `orchestration` (dont `ResumeCrashFixtureMain`), 3 `program.analysis`, 3 `semantic`, 1 `workspace` | bootstrap | bootstrap, `com.minos.bootstrap.<même suffixe>` | adaptateurs fichiers réels, runtime-local, ou `MinosApplication.open` (racine de composition) |

  **Aucun accès package-private révélé** : tout compile après relogement (le relevé du bytecode du jalon 1 n'en montrait pas d'autre que ceux déjà résolus). Contenu des fichiers : déclaration de package et imports seulement ; les 5 tests passés dans le module testé sont des `git mv` à 100 %. Références littérales (chemins, assertions identiques) : `check-polyglot-provider-consistency.py` (`M24PolyglotProviderTest`), `check-hosted-control-plane-consistency.py` (`SharedMinosApplicationIntegrationTest`), `check-vertical-decomposition-consistency.py` (`MinosApplicationTest`, `ProgramGraphPerformanceQualificationTest`), `check-p0-p2.py` (`MinosApplicationTest`). Les scripts qui sélectionnent ces tests par `-Dtest=<nom simple>` (`scripts/m24/run-final.*`, `scripts/m28/run-program-graph-performance.*`) ne dépendent pas du package : inchangés. Cliquet : `TOLERATED_FOREIGN_TESTS` vidé — **plus aucun test en package étranger** (0/45). Vérifié : `test-compile` du reactor, `./mvnw -pl minos-app -am test` : 1 362 tests Surefire, 0 échec, 46 ignorés (1 356 + les 6 nouveaux cas du groupe j) ; gates statiques verts.
- 2026-09-29 — impl-archi, jalon 4 groupe n (filtres m19/m20, `0ef46b12`) : `'minos-engine/**'` ajouté aux filtres `paths` de `m19-advanced-code-intelligence.yml` et de `m20-semantic-hybrid-intelligence.yml`, qui couvraient `minos-application/**` et `minos-domain/**` d'où ce lot a descendu `discovery`, `incremental`, `orchestration`, `hosted` et le modèle `dynamic` dans engine. Aucun autre module n'a reçu de code qu'ils couvraient (les renommages de storage-local, déjà dans le filtre de m20, restent dans leur module ; m19 ne couvrait pas storage-local et aucune de ses classes n'y est allée). Rien d'autre n'est modifié dans ces workflows (ni épinglage, ni job). Entrée correspondante retirée du § 8 ; le retrait éventuel des deux workflows, redondants avec pr-ci, y reste (V-A3-11). Vérifié : `check-workflow-pins.py` et les autres gates statiques verts.
- 2026-09-29 — impl-archi, jalon 4 groupe o (**clôture**) :
  - **Cliquet supprimé** : `TOLERATED_SPLIT_PACKAGES`, `TOLERATED_FOREIGN_TESTS` et leur code retirés de `check-module-boundaries.py` ; la règle est stricte (aucun éclatement toléré), vérifie aussi que le répertoire d'un test déclare son package, et affiche `A3 package ownership: packages=45, each owned by exactly one module`. Auto-test adapté (13 cas) : package éclaté entre deux puis trois modules, test en package étranger, test dont le chemin ment sur son package, package dans un commentaire, absence de toute tolérance, A7 (4 cas), câblage de `main()` par espions ; mutations du script (contrôle de production, des tests, du chemin, appel dans `main()`) toutes rouges. Appliquée à l'arbre de `10486cb7`, la règle finale échoue sur 59 violations (14 packages, 45 tests) — sortie `red-final-rule-on-10486cb7.err` conservée pour la PR.
  - **JaCoCo** : le fournisseur de la route `mcp` est renommé `com.minos.app.McpLaunchRouteProvider` (au lieu de `McpBackendLaunchRoute`, introduit au groupe j) pour qu'aucun préfixe `com/minos/app/McpBackend*` ne l'englobe. La portée `m29-backend-routing` revient au rapport propre de minos-app (le rapport agrégé ne porte pas l'exécution des tests de minos-app : le passage à l'agrégé du groupe j y faisait tomber `McpBackend*` à 0 ligne couverte, ligne 0,189) ; `DockerRuntimeBootstrap`, désormais dans minos-cli, qui n'apparaît plus dans ce rapport, reçoit sa propre portée `m29-docker-runtime-bootstrap`, mêmes seuils (0,55 / 0,30), sur le rapport agrégé. Appartenance comparée classe par classe à la base (les deux portées m29 réunies = l'ancienne) : **identique pour toutes les portées**.
  - **ADR 0044** : ligne Status nettoyée (V-A3-04), contrôle décrit comme strict, ruptures (alias, SPI), conséquences chiffrées, portées m29. **Suivi** : tableau de bord, table de correspondance complète (toutes les lignes « fait »), classes créées, décomptes par module (§ 4.1), résolutions du § 2.3, § 8 final.
  - **`./mvnw -B clean verify`** (log `final-verify2.log`) : BUILD SUCCESS, **1 369 tests, 0 échec, 0 erreur, 46 ignorés** = base 1 355/0/46 + 14 cas ajoutés (`MinosVersionContractTest` 3, `ShadedJarVersionIT` 1, `ShadedJarMcpEntryPointIT` 2, `McpLaunchRoutesTest` 4, `McpLaunchRoutingTest` 2, `RuntimeIntelligenceFileAdaptersTest` 2). Par module, base → fin : domain 28 → 18, engine 132 → 353, runtime-local 214 → 214, storage-local 133 → 147, provider-scip 86 → 91, integration-git 27 → 27, application 282 → 87, storage-postgresql 59 → 60, bootstrap 85 → 69, nexus 10 → 10, cli 100 → 121, api 56 → 56, mcp 33 → 33, app 106 → 76 (Surefire) et 4 → 7 (Failsafe) ; ignorés 46 → 46. Caractérisation A2 5/5 et 12/12 ; `ShadedJarCompositionRootIT`, `ShadedJarMcpEntryPointIT`, `ShadedJarSmokeIT`, `ShadedJarVersionIT` verts ; `git diff --stat 10486cb7 -- minos-app/src/test/resources/characterization` vide.
  - **`check-jacoco.py`** : toutes les portées PASS sauf `m24-polyglot-provider-platform`, dont le seul plancher manqué est le préfixe `ManagedPolyglotScipRuntimeManager` (ligne 0,232, branche 0,161 : chiffres de la base, écart Windows connu) ; la ligne de la portée entière passe de 0,614 à 0,617 parce que `ScipIndexerCatalogTest`, relogé dans provider-scip, compte désormais dans le rapport agrégé. m25 branche 0,624257 (oscillation V-A3-07). m29-backend-routing ligne 0,910 / branche 0,676 ; m29-docker-runtime-bootstrap 0,667 / 0,900. Tous les gates statiques verts (23).

## 7. Constats verif-archi

### Jalon 1

« Jalon 1 (073f4a43, 26e63775, 38d65cf9) — inspecté par verif-archi. Gates identiques à la base, golden identiques (sha256), comptes et helpers inchangés. Cliquet prouvé : les témoins nouveau package éclaté, éclatement élargi à un 3e/4e module, test en package étranger et module de reactor non gouverné sont rouges ; les tolérances périmées, plus étroites ou plus larges sont rouges ; tolérance vide = 59 violations (14 + 45). Étape CI sans action ajoutée. ADR conforme à l'index. »

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| V-A3-01 | `26e63775` | L'auto-test n'appelle jamais `main()` : retirer de `main()` les appels à `check_reactor_modules()` et `check_package_ownership()` laisse tout vert. | à corriger | **résolu** (commit des constats du jalon 1) : classe `MainWiringTest`, deux cas qui remplacent les règles par des espions levant `RuntimeError` et vérifient que `main()` renvoie 1 et que chaque espion est appelé (dans l'ordre). Mutations rejouées : appel `check_reactor_modules()` retiré → 2 échecs ; appel `check_package_ownership()` neutralisé → 1 échec. |
| V-A3-02 | `26e63775` | `TOLERATED_FOREIGN_TESTS` est indexé par le chemin seul : un test toléré pourrait changer de package sans changer de chemin. | remarque | **résolu** : le contrôle chemin/package est étendu aux sources de test (un test dont le répertoire ne dit pas le package est une erreur), le chemin suffit donc à fixer le package. Cas d'auto-test ajouté (test toléré dont le chemin ment → rouge). Aucun test du dépôt n'était en défaut. |
| V-A3-03 | `26e63775` | `check-module-boundaries.py` : espace perdue dans `PACKAGE = re.compile(`. | remarque | **résolu** : espace rétablie. |
| V-A3-04 | `073f4a43` | La ligne Status de l'ADR 0044 cite la branche et « en cours ». | remarque (fin de lot) | **résolu** au commit de clôture : « Accepted (2026-09-29) — mis en œuvre ». |
| V-A3-05 | `26e63775` | Pas de cas d'auto-test « tolérance plus large que la réalité ». | remarque | **résolu** : cas ajouté (3 modules tolérés, 2 réels → rouge). L'auto-test compte 17 cas. |

### Jalon 2 (premiers commits)

« Commits 4c61e30f, 883fb35d, 7c97bff9, d3fb624c — inspectés par verif-archi. Gates verts ; golden identiques (sha256) ; clean verify vert, tests identiques à la base (1355/0/46), caractérisation A2 5/5 et 12/12 ; témoins toujours rouges ; cliquet réduit des seules entrées adapter.scip et git (12 packages éclatés) ; aucune duplication (renommages git purs, comptes inchangés) ; références littérales mises à jour à assertion identique ; appartenance aux scopes JaCoCo inchangée (m25 55, critical-orchestration 8, resume-orchestration 19, m24 = base) ; ruptures java-api.md exactes (javap -public sur minos-api). V-A3-01/02/03/05 résolus (vérifiés par mutation). V-A3-06 (à corriger) : amendement 0022 incomplet. V-A3-07 (remarque) : m25 branches 0.624257 vs 0.625446, non attribuable au renommage, à surveiller. »

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| V-A3-06 | `073f4a43` | ADR 0044 : « le reste de l'ADR 0022 est inchangé » est faux, 0022 place `com.minos.store.*` et `com.minos.git.*` (et `com.minos.runtime.*`) dans les adaptateurs. | à corriger | **résolu** (commit docs qui suit `3bdb7eea`) : l'amendement couvre aussi le côté adaptateurs (storage-local, integration-git, runtime-local) et le déplacement de `MinosLauncher`, et renvoie à la table des décisions. |
| V-A3-07 | `d3fb624c` | Couverture de branches de la portée m25 : 0,624257 contre 0,625446 (une branche sur ≈ 841), non attribuable au renommage. | remarque | **clos** : m25 revenu exactement à la base au jalon 2 (aléa). |

### Jalon 2

« Jalon 2 (8c0eb0ef → a771ce36) — inspecté par verif-archi sur un clean verify complet. BUILD SUCCESS ; 1359/0/0/46 (base + 4 tests MinosVersion, seuls écarts) ; caractérisation A2 5/5 et 12/12 ; golden identiques (sha256). Jar ombré issu du clean build : aucune classe d'adaptateur ni ressource sous un ancien package ; ressources .ps1 chargées sous /com/minos/runtime/local/ ; ServiceLoader résout com.minos.runtime.local.WindowsExecutionPathIdentityProvider. MinosVersion : contrat prouvé par mutation (repli forcé, suppression d'isBlank, mauvais attribut du manifeste → rouge, ShadedJarVersionIT compris). Seule visibilité élargie : IndexingRun.portable, justifiée. Cliquet : 9 packages tolérés, 45 tests ; témoins rouges ; auto-test 17/17. Scripts : 18 fichiers, pure substitution de chemin. Scopes JaCoCo : 27 scopes à appartenance identique classe par classe, chiffres identiques à la base. Aucune duplication. V-A3-06 résolu (5eefb2ec). V-A3-07 clos (m25 revenu exactement à la base : aléa). V-A3-08 (remarque, hors lot) : constantes RESOURCE mortes pointant vers un .ps1 inexistant dans WindowsJobObjectProcessOwnership:25 et WindowsAppContainerWorkerSandboxBackend:77. Statut : accepté. »

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| V-A3-08 | `8c0eb0ef` | Constantes `RESOURCE` mortes pointant vers un `.ps1` inexistant (seuls les `.ps1.template` existent) : `WindowsJobObjectProcessOwnership:25`, `WindowsAppContainerWorkerSandboxBackend:77`. Préexistant (le renommage n'a changé que le préfixe). | remarque, hors lot | renvoyé au § 8 « À traiter plus tard ». |

### Jalon 3

« Jalon 3 (adeaee42 → 30fdaa35) — inspecté par verif-archi sur un clean verify complet. BUILD SUCCESS ; 1361/0/0/46 (+2 venus de la scission) ; caractérisation A2 5/5 et 12/12 ; golden identiques. Scission de RuntimeIntelligenceServiceTest : 30 et 26 assertions d'origine conservées ; l'assertion adaptée sur importedAt est plus forte (elle attrape la mutation de FileRuntimeObservationStore qui renvoie la nouvelle session) ; mutations des doublures rouges ; aucune doublure recopiée ; constructeur à Clock toujours package-private. Visibilité : seul requireSha256 élargi. Comptes conformes (application −49, domain −19, engine +68). Cliquet : cli et integration.nexus, 41 tests étrangers ; témoins rouges ; auto-test 17/17. Scripts : pure substitution de chemin. Scopes JaCoCo : appartenance identique, chiffres de la base sauf l'oscillation connue de m25 (V-A3-07). V-A3-09 (à corriger) : U+FEFF brut. V-A3-10 (remarque) : test adaptateurs non gelé. V-A3-11 (remarque) : filtres m19/m20. »

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| V-A3-09 | `fe2e988e` | `RuntimeIntelligenceServiceTest` (application) : U+FEFF brut dans le littéral du cas BOM, à la place de l'échappement d'origine ; seul BOM brut du code Java du dépôt. | à corriger | **résolu** à `59345b6f` : `56682e0e` annonçait la correction mais ne la contenait pas (l'échappement, passé par la ligne de commande, y avait été réinterprété en caractère brut) ; la correction est faite octet par octet, et un balayage des octets `EF BB BF` dans tous les `*.java` du dépôt ne trouve plus rien. |
| V-A3-10 | `fe2e988e` | `check-runtime-dynamic-consistency.py` ne gèle plus que le test d'application. | remarque | **résolu** : `require_facts` ajouté sur `RuntimeIntelligenceFileAdaptersTest` et ses deux cas ; prouvé rouge par mutation. |
| V-A3-11 | `30fdaa35` | m19 et m20 ne font rien de plus que pr-ci et ne sont pas requis ; leurs filtres de chemins ne voient plus le code déplacé. | remarque | **résolu** : filtres rétablis au groupe n du jalon 4 (`minos-engine/**`) ; retrait des deux workflows renvoyé au § 8. |

### Jalon 4 et clôture

« Jalon 4 et clôture (b56413bb → 34642f4d) — inspectés par verif-archi sur un clean verify complet. BUILD SUCCESS ; 1369/0/0/46 (base 1355 + 14 tests ajoutés, seuls écarts par classe de test) ; caractérisation A2 5/5 et 12/12 ; golden identiques (sha256) ; 22 gates verts. SPI `mcp` : chargeur explicite, échec sans chemin et code 1 à zéro ou plusieurs fournisseurs, routage avant `MinosApplication.open`, prouvés par mutation (chargeur de contexte, fournisseur multiple, ouverture avant la route : tous rouges). Jar ombré du clean build : descripteur `com.minos.cli.McpLaunchRoute` présent, `--help` et `--version` fonctionnent, `mcp` atteint le fournisseur (échec contrôlé du routeur, aucun magasin créé). `MinosLauncher` et `DockerRuntimeBootstrap` : noms qualifiés inchangés et résolubles. Portée m29 scindée sans assouplissement (même union de classes, mêmes seuils imposés à chaque moitié). Alias : ne faisaient que déléguer, aucun comportement perdu. Cliquet supprimé : règle stricte, auto-test probant (5 mutations rouges), preuve rouge sur 10486cb7 = 59 violations ; témoins rouges. Workflows m19/m20 : seuls les chemins changent. `java-api.md` : les 93 renommages, le SPI et les alias sont documentés. »

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| V-A3-12 | `34642f4d` | `check-module-boundaries.py:90` : `NS ={` a perdu son espace (même régression que V-A3-03). | remarque | **résolu** par l'orchestrateur au commit de bilan (`NS = {`). |

### Bilan du lot A3 (base `10486cb7` → tête)

- Packages éclatés : 14 → 0. Tests en package étranger : 45 → 0. Accès package-private entre jars : 0.
- 4 alias CLI supprimés. A7 fermé (liste des modules confrontée au reactor).
- 93 classes renommées, toutes documentées ; points d'entrée de processus inchangés.
- Seules visibilités élargies : `IndexingRun.portable` et `FileFingerprint.requireSha256`, justifiées.
- Aucune duplication ajoutée ; aucun test perdu ; golden identiques octet pour octet du premier au dernier commit.
- Portées JaCoCo : même appartenance classe par classe (m29 scindée sans assouplissement) ; mesure inchangée ou en hausse, hormis l'oscillation connue de m25.
- Les 7 mutations témoins de verif-archi sont rouges, A2 compris.

| Constat | Statut | Correction |
|---|---|---|
| V-A3-01 | résolu | `7c97bff9` |
| V-A3-02 | résolu | `7c97bff9` |
| V-A3-03 | résolu | `7c97bff9` |
| V-A3-04 | résolu | `34642f4d` (ligne Status de l'ADR 0044) |
| V-A3-05 | résolu | `7c97bff9` |
| V-A3-06 | résolu | `5eefb2ec` |
| V-A3-07 | clos | aléa de test sur m25, sans lien avec le lot |
| V-A3-08 | reporté hors lot | § 8 |
| V-A3-09 | résolu | `59345b6f` (`56682e0e` l'annonçait sans le contenir) |
| V-A3-10 | résolu | `56682e0e` |
| V-A3-11 | résolu | `0ef46b12` ; retrait de m19/m20 reporté au § 8 |
| V-A3-12 | résolu | commit de bilan |

**Statut du lot : accepté par verif-archi, aucun constat ouvert.**

## 8. À traiter plus tard (hors périmètre)

| Origine | Description | Renvoi |
|---|---|---|
| impl-archi (jalon 1) | `StableFileSystemIdentity` (engine) découvre `ExecutionPathIdentityProvider` par `ServiceLoader.load(Class)`, donc par le chargeur de contexte du thread : même défaut que A8, sur un autre service. A3 ne touche que le contenu du fichier de service (nom de la classe fournie), pas le chargeur. | A8 (étendre) |
| verif-archi (V-A3-08) | Constantes `RESOURCE` mortes dans `WindowsJobObjectProcessOwnership` et `WindowsAppContainerWorkerSandboxBackend` (runtime-local) : elles désignent un `.ps1` qui n'existe pas, les lanceurs étant assemblés depuis leurs `.ps1.template` par `WindowsContainmentScript`. À supprimer ou à faire pointer sur le gabarit. | hygiène runtime-local |
| verif-archi (V-A3-11) | Retrait de m19/m20, redondants avec pr-ci, au titre de l'ADR 0043. | G3 / ADR 0043 |
| impl-archi (jalon 4) | Les tests de minos-cli qui utilisent des adaptateurs fichiers (via la dépendance `runtime` sur minos-bootstrap) sont plus nombreux : les 6 tests du lanceur relogés depuis minos-app s'y ajoutent. Permis par les règles POM (portée test transitive), c'est la limite connue de l'ADR 0042 § 8.3 ; un contrôle des sources de test contre les adaptateurs la fermerait. | A2 (suite) |
| SPRINT-2-SUIVI (V19) | `minos-cli/LazyAutonomousIndexOperations` n'est câblé nulle part en production. Hors de la question des packages. | A4 |


---

# Lot 2 — A4 : surfaces d'objet (classes-dieux et constructeurs télescopiques)

> Branche : `archi/a4-surfaces`, créée depuis la tête d'A3 (`324d85bb`), worktree `minos-wt/a4-surfaces` ; elle sera rebasée sur `develop` après la fusion d'A3 (PR draft #302).
> Constat : **A4** (`AUDIT-2026-09.md` § 3). Décision d'architecture : [ADR 0045](../adr/0045-constructeur-unique-et-point-d-entree-nomme.md) (Accepted).
> Agents : `impl-archi` (implémentation), `verif-archi` (inspection de chaque commit). Aucun push, aucune PR ouverte par les agents.
> Règles du lot : aucun changement de comportement (les deux tests de caractérisation A2 verts, les 12 golden de `minos-app/src/test/resources/characterization/` identiques octet pour octet, jamais régénérés) ; un constructeur unique et un point d'entrée nommé à la place des constructeurs télescopiques ; aucun alias déprécié ; toute rupture d'une signature publique de `minos-api` ou de `MinosApplication` listée, justifiée et documentée (`docs/user/java-api.md` § Ruptures, en-tête de PR) ; cache de `LocalProjectArchitectureQuery` seulement si son invalidation est prouvée.

## A4.1 Tableau de bord

| Jalon | Contenu | Statut | Commits |
|---|---|---|---|
| 1 | Inventaire (accesseurs de `MinosApplication`, constructeurs de `LocalProjectArchitectureQuery` et `MinosCli`, balayage ≥ 4 constructeurs), conception cible, analyse du cache, références littérales, ADR 0045 en brouillon | livré, accepté par verif-archi (V-A4-01, V-A4-02 : remarques) ; arbitré (§ A4.5) | `970273b2` |
| 2 | Garde-fous par réflexion (rouges sur la base, § A4.6), `LocalProjectArchitectureQuery.defaults` (b), `MinosCli.builder` (c), identités de câblage puis `MinosApplication` regroupée, option B (d), docs et ADR accepté (e) | livré | `08ea7a76`, `b89f20c5`, `2d0a06e4`, `98df6d4c`, `910f78fc`, `39216e09` ; V-A4-04 : `9ad3e074`, `df593b1d` ; V-A4-06 : `6fb603ab` et commit de docs |
| **Bilan** | Constructeurs : `LocalProjectArchitectureQuery` 9 → 1, `MinosCli` 10 → 1 ; `MinosApplication` : constructeur 24 → 9 paramètres, surface publique identique (35 accesseurs, `javap -public` inchangé) ; pas de cache (non prouvable) ; golden intacts | lot terminé, en attente de verif-archi | — |

## A4.2 Inventaire daté (base `324d85bb`, 29 septembre 2026)

Méthode : les appelants viennent du **bytecode** (pool de constantes de chaque `.class` de production et de test des 14 modules, après `./mvnw -DskipTests test-compile`), pas d'une recherche textuelle : un appel par référence de méthode compte, un homonyme d'une autre classe ne compte pas. Les classes de `minos-app` sont compilées sous `target/` racine (répertoire de build du module).

### A4.2.1 `MinosApplication` (456 lignes)

Membres publics : 12 constantes, `open(Path)`, `builder(Path)`, `close()`, **36 méthodes d'instance hors `close()`** (35 accesseurs (dont 31 de services) et la fabrique `indexerRegistry(String)`, soit 32 méthodes de services), et le `Builder` (22 mutateurs et `build()`). Le constructeur (24 paramètres) est package-private, appelé par le seul `MinosApplicationAssembler`. Colonne « domaine » : regroupement proposé (§ A4.3.3).

| Accesseur | Type retourné | Domaine | Appelants de production (module : classes) | Appelants de test (module : nb de classes) |
|---|---|---|---|---|
| `home()` | `Path` | identité | application : `LocalProjectOperations` ; cli : `LocalAutonomousIndexOperations`, `LocalRemoteIndexOperations`, `MinosCliRunner` | bootstrap 2 |
| `storageBackendId()` | `String` | identité | — | app 1 (`A2CompositionCharacterizationTest`), bootstrap 1 |
| `compositionRoot()` | `MinosApplicationComposer` | identité | cli : `LocalAutonomousIndexOperations`, `LocalRemoteIndexOperations` | bootstrap 1 |
| `hostedControlPlaneService()` | `Optional<HostedControlPlaneService>` | hébergé | api : `LocalMinosTeamApi` ; cli : `MinosCliRunner` ; mcp : `MinosApplicationMcpBackend` | bootstrap 1, cli 1, mcp 1 |
| `projectRegistry()` | `ProjectRegistry` | stockage | api : `LocalMinosApi` ; application : `LocalProjectOperations` ; cli : `LocalAutonomousIndexOperations`, `LocalRemoteIndexOperations`, `MinosCliRunner` | api 4, app 1 (`A2CompositionCharacterizationTest`), bootstrap 4, cli 4, mcp 3, nexus 1 |
| `snapshotStore()` | `CodeKnowledgeSnapshotStore` | stockage | api : `LocalMinosApi` ; application : `LocalProjectOperations` ; cli : `LocalAutonomousIndexOperations`, `MinosCliRunner` | api 3, app 1 (`A2SurfaceCharacterizationTest`), bootstrap 4, cli 3, mcp 1, nexus 1 |
| `indexStateStore()` | `IndexStateStore` | stockage | api : `LocalMinosApi` ; application : `LocalProjectOperations` ; cli : `LocalAutonomousIndexOperations` ; mcp : `MinosApplicationMcpBackend` | api 1, app 2 (les deux caractérisations), bootstrap 1, cli 2, mcp 1 |
| `fingerprintStore()` | `ProjectFingerprintSnapshotStore` | stockage | cli : `LocalAutonomousIndexOperations` | api 2, app 1 (`A2SurfaceCharacterizationTest`), bootstrap 2, cli 1, mcp 1 |
| `semanticVectorStore()` | `SemanticVectorStore` | stockage | — | bootstrap 1 |
| `runtimeObservationStore()` | `RuntimeObservationStore` | stockage | — | bootstrap 1 |
| `retentionService()` | `StorageRetentionService` | stockage | cli : `LocalAutonomousIndexOperations` | app 2 (les deux caractérisations) |
| `discoveryService()` | `ProjectDiscoveryService` | indexation | cli : `LocalAutonomousIndexOperations` | bootstrap 1 |
| `fingerprintService()` | `ProjectFingerprintService` | indexation | cli : `LocalAutonomousIndexOperations` | api 2, app 1 (`A2SurfaceCharacterizationTest`), bootstrap 2, cli 1, mcp 1 |
| `invalidationService()` | `ProjectInvalidationService` | indexation | cli : `LocalAutonomousIndexOperations` | — |
| `incrementalIndexingPlanner()` | `IncrementalIndexingPlanner` | indexation | cli : `LocalAutonomousIndexOperations` | — |
| `providerRuntimeManager()` | `ProviderRuntimeManager` | indexation | application : `ProviderPlatformService` ; cli : `LocalAutonomousIndexOperations` | app 2 (`A2CompositionCharacterizationTest`, `M24PolyglotProviderTest`), bootstrap 1 |
| `indexerDescriptors()` | `List<IndexerDescriptor>` | indexation | cli : `LocalRemoteIndexOperations` | app 1 (`A2CompositionCharacterizationTest`), bootstrap 1 |
| `providerCatalog()` | `IndexerProviderCatalog` | indexation | application : `ProviderPlatformService` | app 1 (`ProviderCatalogPortTest`) |
| `scipArtifactImporter()` | `ScipArtifactImporter` | indexation | application : `LocalProjectOperations` | — |
| `snapshotStager()` | `SnapshotStager` | indexation | cli : `LocalAutonomousIndexOperations` | bootstrap 1 |
| `snapshotPromoter()` | `SnapshotPromoter` | indexation | cli : `LocalAutonomousIndexOperations` | bootstrap 1 |
| `indexerRegistry(String)` | `IndexerRegistry` (fabrique : instance neuve à chaque appel) | indexation | cli : `LocalAutonomousIndexOperations` | — |
| `projectInspectionService()` | `ProjectInspectionService` | requêtes | application : `LocalProjectOperations` ; mcp : `MinosApplicationMcpBackend` | — |
| `projectQueryService()` | `ProjectQueryService` | requêtes | application : `LocalProjectSymbolQuery` ; mcp : `MinosApplicationMcpBackend` | — |
| `architectureQuery()` | `ProjectArchitectureQuery` | requêtes | api : `LocalMinosApi` ; cli : `MinosCliRunner` ; mcp : `MinosApplicationMcpBackend` | bootstrap 1, cli 1 |
| `impactQuery()` | `ProjectImpactQuery` | requêtes | api : `LocalMinosApi` ; cli : `MinosCliRunner` ; mcp : `MinosApplicationMcpBackend` | bootstrap 1, cli 1 |
| `programGraphService()` | `ProgramGraphService` | requêtes | api : `LocalAdvancedCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand` ; mcp : `MinosApplicationMcpBackend` | bootstrap 2 |
| `advancedImpactService()` | `AdvancedImpactService` | requêtes | api : `LocalAdvancedCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand` ; mcp : `MinosApplicationMcpBackend` | — |
| `securityAnalysisService()` | `SecurityAnalysisService` | requêtes | api : `LocalAdvancedCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand` ; mcp : `MinosApplicationMcpBackend` | — |
| `workspaceIntelligence()` | `WorkspaceIntelligenceService` | requêtes | api : `LocalMinosMultiRepositoryApi` | bootstrap 1 |
| `gitIntelligence()` | `GitIntelligence` | requêtes | api : `LocalMinosMultiRepositoryApi` ; cli : `MinosCliRunner` | bootstrap 1 |
| `runtimeIntelligenceService()` | `RuntimeIntelligenceService` | requêtes | cli : `MinosCliRunner` ; mcp : `MinosApplicationMcpBackend` | bootstrap 1, cli 1 |
| `semanticIndexService()` | `SemanticIndexService` | sémantique | api : `LocalSemanticCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand`, `LocalAutonomousIndexOperations`, `MinosCliRunner` ; mcp : `MinosApplicationMcpBackend` | bootstrap 3, nexus 1 |
| `semanticSearchService()` | `SemanticSearchService` | sémantique | api : `LocalSemanticCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand` ; mcp : `MinosApplicationMcpBackend` | bootstrap 1 |
| `hybridSearchService()` | `HybridSearchService` | sémantique | api : `LocalSemanticCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand` ; mcp : `MinosApplicationMcpBackend` ; nexus : `NexusSemanticSignalService` | bootstrap 1 |
| `hybridContextBuilder()` | `HybridContextBuilder` | sémantique | api : `LocalSemanticCodeIntelligenceApi` ; cli : `IdeIntelligenceCommand` ; mcp : `MinosApplicationMcpBackend` | bootstrap 1 |

Trois accesseurs n'ont aucun appelant de production (`storageBackendId()`, `semanticVectorStore()`, `runtimeObservationStore()`) ; ils sont nommés par des tests et, pour le dernier, par un script (§ A4.2.5). Aucun n'est retiré par ce lot (règle 5 : même surface, mêmes instances).

Les 32 méthodes de services des domaines stockage, indexation, requêtes et sémantique (`indexerRegistry(String)`, fabrique, comprise) sont appelés par **13 classes de production** (api 4, application 3, cli 4, mcp 1, nexus 1) et **23 classes de test** (api 4, app 4 dont les **deux caractérisations A2**, bootstrap 5, cli 6, mcp 3, nexus 1).

### A4.2.2 `LocalProjectArchitectureQuery` (145 lignes) — 9 constructeurs

| # | Visibilité | Paramètres | Valeurs par défaut injectées | Appelants |
|---|---|---|---|---|
| 1 | public | `ProjectRegistry, CodeKnowledgeSnapshotStore` | `new ProjectResolver(registry)`, `new ProjectDiscoveryService()`, les 5 analyseurs neufs | test : `LocalProjectArchitectureQueryTest` (bootstrap), 3 appels |
| 2 | public | `ProjectRegistry, CodeKnowledgeSnapshotStore, ProjectDiscoveryService` | `new ProjectResolver(registry)`, les 5 analyseurs neufs | production : `MinosApplication:161` (seul appel de production) |
| 3 | public | `ProjectResolver, CodeKnowledgeSnapshotStore, ProjectDiscoveryService` | les 5 analyseurs neufs | **aucun** |
| 4 | package | n° 2 + `ArchitectureTopologyService` | 4 analyseurs neufs | **aucun** |
| 5 | package | n° 4 + `ArchitectureDependencyService` | 3 analyseurs neufs | **aucun** |
| 6 | package | n° 5 + `ArchitectureConcentrationService` | 2 analyseurs neufs | **aucun** |
| 7 | package | n° 6 + `ArchitectureCentralityService` | `new ArchitectureTechnologyService()` | **aucun** |
| 8 | package | n° 7 + `ArchitectureTechnologyService` | `new ProjectResolver(registry)` | **aucun** |
| 9 | private | `ProjectResolver`, magasin, découverte, 5 analyseurs | aucune (canonique) | les 8 autres |

Le champ `intelligenceService` est déjà initialisé en ligne (`new ArchitectureIntelligenceService()`), hors constructeur. Les analyseurs n'ont aucun état d'instance (constructeur par défaut, aucun champ) : les injecter n'a jamais servi. Les constructeurs 3 à 8 sont du code mort ; aucune classe, de production ou de test, n'y fait appel.

### A4.2.3 `MinosCli` (337 lignes) — 10 constructeurs (l'audit en compte 9)

Chaque constructeur complète le précédent d'un paramètre et délègue avec `null`. Un collaborateur `null` désactive la commande correspondante (`error: <cmd> is not configured in this CLI bootstrap`, code 1).

| # | Visibilité | Paramètres (cumulés) | Valeurs par défaut | Appelants |
|---|---|---|---|---|
| 1 | public | `ProjectSymbolQuery` | tout le reste `null` | test : `MinosCliTest` (cli, 3 appels), `ScipRelatedTestSnapshotIntegrationTest` (app, 1) |
| 2 | public | + `ProjectOperations, ProjectArchitectureQuery, ProjectImpactQuery` | reste `null` | **aucun** |
| 3 | package | + `NexusExportCommand` | reste `null` | **aucun** |
| 4 | package | + `AutonomousIndexOperations, Path home` | reste `null` | production : `MinosCliRunner.statelessHelpCli()` (mandataires qui lèvent à tout appel, `home = Path.of(".")`) |
| 5 | package | + `ProviderPlatformService` | reste `null` | **aucun** |
| 6 | package | + `GitIntelligence` | reste `null` | **aucun** |
| 7 | package | + `RemoteIndexOperations` | reste `null` | **aucun** |
| 8 | package | + `RuntimeIntelligenceService` | reste `null` | **aucun** |
| 9 | package | + `HostedControlPlaneService` | `resumeStatus = null` | test : `TeamCommandTest` (cli, 1 appel : nexus, provider, git, remote et hosted à `null`) |
| 10 | package | + `IndexResumeStatusSource` | aucune (canonique) | production : `MinosCliRunner.run` (13 arguments, `hostedControlPlaneService().orElse(null)`, `resumeStatus = autonomousIndex`) |

Règles de câblage du constructeur canonique, à conserver à l'identique : `symbolQuery` obligatoire (`Objects.requireNonNull(symbolQuery, "symbolQuery")`) ; `project`, `index`, `import-scip`, `index-status` exigent `projectOperations` ; `resumeStatus == null` → `projectId -> Optional.empty()` ; `tools` exige `autonomousOperations` ; `doctor` exige `autonomousOperations` **et** `home` ; `git-activity` exige `projectOperations` **et** `gitIntelligence` ; `team` lit `MINOS_TEAM_TOKEN` par `System.getenv` à chaque appel ; `ide` et les commandes de symboles sont toujours présentes ; ordre de construction des commandes inchangé. Tous les types de paramètres sont publics ; seuls les constructeurs 1 et 2 le sont.

### A4.2.4 Balayage : constructeurs multiples ailleurs (≥ 4 constructeurs déclarés non synthétiques, classes de production)

| Constructeurs | Module | Classe | Répartition | Traitement |
|---|---|---|---|---|
| 10 | minos-cli | `MinosCli` | public 2, package 8 | **lot A4** |
| 9 | minos-application | `LocalProjectArchitectureQuery` | public 3, package 5, private 1 | **lot A4** |
| 6 | minos-engine | `orchestration.IndexingLifecycleService` | public 3, package 3 | plus tard |
| 5 | minos-api | `api.LocalMinosApi` | public 2, package 1, private 2 | plus tard (les deux publics sont l'API documentée : `(Path)` et `(MinosApplication)`) |
| 4 | minos-cli | `LocalAutonomousIndexOperations` | public 3, private 1 | plus tard |
| 4 | minos-cli | `LocalRemoteIndexOperations` | public 1, package 2, private 1 | plus tard |
| 4 | minos-application | `impact.LocalProjectImpactQuery` | public 2, package 1, private 1 | plus tard (`MinosApplication` appelle son constructeur public à 2 arguments, que la refonte ne touche pas) |
| 4 | minos-engine | `orchestration.IndexingRuntimePorts$IndexingExecutionRequest` | public 4 | plus tard |
| 4 | minos-application | `program.analysis.ProgramGraphService` | public 3, package 1 | plus tard |
| 4 | minos-storage-local | `storage.local.store.FileSymbolSnapshotStore` | public 1, package 3 | plus tard |

Aucune de ces classes n'est une dépendance directe des trois refontes ; elles vont en § A4.8.

### A4.2.5 Références littérales (scripts et docs) à ces surfaces

Scripts qui affirment des chaînes des fichiers touchés, à préserver à assertion identique :

| Script | Fichier lu | Chaînes exigées | Exposition |
|---|---|---|---|
| `scripts/quality/check-hosted-control-plane-consistency.py` | `MinosApplication.java` | `HOSTED_MODE_ENV = "MINOS_HOSTED_MODE"`, `hostedControlPlaneService`, `MinosApplicationRuntimeConfiguration.apply(settings, builder)` | aucune option ne les touche |
| `scripts/quality/check-runtime-dynamic-consistency.py` | `MinosApplication.java` ; `MinosApplicationAssembler.java` | `RuntimeObservationStore`, `RuntimeIntelligenceService`, `runtimeObservationStore()` ; `selected.runtimeObservationStore()`, `effectiveRuntimeObservations` | option A : `runtimeObservationStore()` quitte `MinosApplication.java` si la façade est un fichier à part |
| `scripts/quality/check-semantic-retrieval-consistency.py` | `MinosApplication.java` | les quatre `SEMANTIC_*_ENV = "..."`, `MinosApplicationRuntimeConfiguration.apply(settings, builder)` | aucune |
| `scripts/remediation/check-p0-p2.py` (CI) | `MinosApplication.java` ; `MinosApplicationTest.java` | `ProgramGraphService.productionProviders(effectiveFingerprints)` ; `productionCompositionExposesM22CapabilitiesFromOpen`, `MinosApplication.open`, `ProgramGraphCapability.CONTROL_FLOW` | aucune |
| `scripts/remediation/check-post-mne.py` (CI) | `LocalAutonomousIndexOperations.java` | `application.retentionService().compact(prepared.project().id())`, **au moins 4 occurrences** | **option A** : l'expression devient `application.storage().retentionService()...`, la chaîne du script change (même assertion) |
| `scripts/remediation/check-mnd.py` (CI) | `LocalRemoteIndexOperations.java` | `RemoteIndexLease.acquire(application.home(), source.cacheKey())` | aucune (`home()` reste sur `MinosApplication`) |
| `scripts/quality/check-remote-distributed-consistency.py` | `MinosCli.java` | `RemoteIndexCommand.NAME`, `remoteIndexCommand.run` | aucune (le répartiteur `run` ne change pas) |
| `scripts/docs/product-facts.py` (CI) | `MinosCli.java` | bloc `private static final String USAGE = """…""".stripTrailing();` (liste des commandes) | aucune |
| `scripts/m15/run-final.ps1` | `MinosApplication.java` ; `MinosMcpTools.java` | `public final class MinosApplication` ; absence de `MinosCli` | aucune |
| `scripts/history/m15/run-s5.ps1` (archivé, non exécuté ; sans contrainte) | `LocalProjectArchitectureQuery.java` | `ProjectResolver`, `projectResolver.resolve(` ; absence de `UUID.fromString(` et de deux anciens messages | sans contrainte (le champ `projectResolver` reste d'ailleurs) |

`scripts/quality/check-jacoco.py` n'a aucune portée sur `com/minos/architecture`, `com/minos/cli/MinosCli` ni `com/minos/application/MinosApplication`.

Documentation qui nomme ces surfaces : `docs/developer/public-surfaces.md` (diagramme de `MinosApplication`, 15 accesseurs listés : à réécrire en option A seulement) ; `docs/developer/architecture.md` (diagramme `MinosCli --> LocalProjectArchitectureQuery`, déjà inexact puisque `MinosCli` ne connaît que le port `ProjectArchitectureQuery` ; hors lot) ; `docs/user/java-api.md` § Ruptures ; ADR 0042 (§ 1.2 et § 5, historiques, cite `MinosApplication.gitIntelligence()`) et ADR 0044 (six signatures touchées par A3). `docs/architecture/arc42/08-concepts-transverses.md` fixe le contrat CLI stable à `MinosCli.run(String[], Appendable, Appendable)` (ADR 0016) : la refonte n'y touche pas.

## A4.3 Conception cible

### A4.3.1 `LocalProjectArchitectureQuery` : constructeur privé unique et fabrique `defaults`

Convention du module : un constructeur complet et une fabrique statique `defaults(...)` qui fixe les valeurs par défaut (`ProviderPlatformService.defaults(MinosApplication)`, `SearchRequest.defaults(...)`, `HybridRequest.defaults(...)`).

```java
public static LocalProjectArchitectureQuery defaults(
        ProjectRegistry projectRegistry, CodeKnowledgeSnapshotStore snapshotStore, ProjectDiscoveryService discoveryService)
private LocalProjectArchitectureQuery(
        ProjectResolver projectResolver, CodeKnowledgeSnapshotStore snapshotStore, ProjectDiscoveryService discoveryService)
```

- Les cinq analyseurs deviennent des champs initialisés en ligne, comme `intelligenceService` l'est déjà : c'est ce que produisent aujourd'hui les deux seuls constructeurs appelés. Ordre d'évaluation inchangé (`new ProjectResolver(registry)` d'abord, dans la fabrique, puis les analyseurs) ; messages de `requireNonNull` inchangés (`registry` levé par `ProjectResolver`, puis `snapshotStore`, `discoveryService`).
- Appelants : `MinosApplication:161` → `LocalProjectArchitectureQuery.defaults(projectRegistry, snapshotStore, discoveryService)` ; `LocalProjectArchitectureQueryTest` (3 appels) → `defaults(registry, snapshots, new ProjectDiscoveryService())`, la valeur que le constructeur à deux arguments injectait.
- Signatures publiques retirées : les trois constructeurs publics (dont deux appelés). La classe n'est ni dans `minos-api` ni `MinosApplication`, mais elle est publique (point d'arbitrage 4).
- Tests : un test de la fabrique (collaborateurs obligatoires, rejet de `null` avec le nom attendu, même résultat que la chaîne file-backed existante) ; un garde-fou par réflexion : exactement un constructeur déclaré, privé, `(ProjectResolver, CodeKnowledgeSnapshotStore, ProjectDiscoveryService)`, et une seule méthode statique publique qui rend le type. Le garde-fou est écrit d'abord et est **rouge sur `324d85bb`** (9 constructeurs).

### A4.3.2 `MinosCli` : constructeur privé unique et `builder`

Convention : `MinosApplication.Builder` (`public static Builder builder(<obligatoire>)`, classe imbriquée `public static final class Builder`, un mutateur par collaborateur nommé comme lui, qui refuse `null` par `Objects.requireNonNull`, puis `build()`).

```java
public static Builder builder(ProjectSymbolQuery symbolQuery)   // seul collaborateur obligatoire
private MinosCli(Builder builder)                                // le corps actuel du constructeur canonique
public  Builder projectOperations(ProjectOperations)            // publics : ce qu'ouvrait le constructeur public n° 2
public  Builder architectureQuery(ProjectArchitectureQuery)
public  Builder impactQuery(ProjectImpactQuery)
Builder nexusExportCommand(NexusExportCommand)                   // package-private : ce que seuls les
Builder autonomousOperations(AutonomousIndexOperations)          // constructeurs package-private ouvraient
Builder home(Path)
Builder providerPlatformService(ProviderPlatformService)
Builder gitIntelligence(GitIntelligence)
Builder remoteIndexOperations(RemoteIndexOperations)
Builder runtimeIntelligenceService(RuntimeIntelligenceService)
Builder hostedControlPlaneService(HostedControlPlaneService)
Builder resumeStatus(IndexResumeStatusSource)
public  MinosCli build()
```

- Aucune visibilité élargie : ce qui était atteignable hors du package (symboles seuls, puis opérations projet, architecture, impact) reste public ; le reste reste package-private.
- Collaborateur absent = mutateur non appelé (au lieu de `null`) : même effet, la commande reste « not configured ». `symbolQuery` reste contrôlé avec le même message.
- Appelants, dans le même commit : `MinosCliRunner.run` (`app.hostedControlPlaneService().ifPresent(builder::hostedControlPlaneService)` au lieu de `orElse(null)`), `MinosCliRunner.statelessHelpCli()`, `TeamCommandTest`, `MinosCliTest` (3 appels), `ScipRelatedTestSnapshotIntegrationTest` (minos-app).
- Tests : un test du `builder` par combinaison réellement utilisée (symboles seuls ; aide sans état ; câblage complet : chaque commande présente, `doctor` absent sans `home`, `git-activity` absent sans Git, `index-status` sans source de reprise) et un test de rejet de `null` ; garde-fou par réflexion : un seul constructeur déclaré, privé, de paramètre `MinosCli.Builder`, **rouge sur `324d85bb`** (10 constructeurs).

### A4.3.3 `MinosApplication` : deux options

Regroupement par domaine (colonne « domaine » de § A4.2.1) :

| Domaine | Façade (option A) | Contenu |
|---|---|---|
| identité et cycle de vie | reste sur `MinosApplication` | `open`, `builder`, `close`, `home`, `storageBackendId`, `compositionRoot` |
| hébergé | reste sur `MinosApplication` | `hostedControlPlaneService` (déjà un `Optional`, un seul membre) |
| stockage | `storage()` → `MinosApplication.Storage` | `projectRegistry`, `snapshotStore`, `indexStateStore`, `fingerprintStore`, `semanticVectorStore`, `runtimeObservationStore`, `retentionService` (7) |
| indexation | `indexing()` → `MinosApplication.Indexing` | `discoveryService`, `fingerprintService`, `invalidationService`, `incrementalIndexingPlanner`, `providerRuntimeManager`, `indexerDescriptors`, `providerCatalog`, `scipArtifactImporter`, `snapshotStager`, `snapshotPromoter`, `indexerRegistry(String)` (11) |
| requêtes | `queries()` → `MinosApplication.Queries` | `projectInspectionService`, `projectQueryService`, `architectureQuery`, `impactQuery`, `programGraphService`, `advancedImpactService`, `securityAnalysisService`, `workspaceIntelligence`, `gitIntelligence`, `runtimeIntelligenceService` (10) |
| sémantique | `semantic()` → `MinosApplication.Semantic` | `semanticIndexService`, `semanticSearchService`, `hybridSearchService`, `hybridContextBuilder` (4) |

**Option A : façades publiques, accesseurs plats retirés.** Pas d'alias (règle 3) : les accesseurs plats disparaissent dans le même commit. Signatures publiques de `com.minos.application.MinosApplication` qui changent (rupture de source et binaire) :

- **retirées (32)** : `projectRegistry()`, `snapshotStore()`, `indexStateStore()`, `fingerprintStore()`, `semanticVectorStore()`, `runtimeObservationStore()`, `retentionService()`, `discoveryService()`, `fingerprintService()`, `invalidationService()`, `incrementalIndexingPlanner()`, `providerRuntimeManager()`, `indexerDescriptors()`, `providerCatalog()`, `scipArtifactImporter()`, `snapshotStager()`, `snapshotPromoter()`, `indexerRegistry(String)`, `projectInspectionService()`, `projectQueryService()`, `architectureQuery()`, `impactQuery()`, `programGraphService()`, `advancedImpactService()`, `securityAnalysisService()`, `workspaceIntelligence()`, `gitIntelligence()`, `runtimeIntelligenceService()`, `semanticIndexService()`, `semanticSearchService()`, `hybridSearchService()`, `hybridContextBuilder()` ;
- **ajoutées (4 méthodes et 4 types)** : `storage()`, `indexing()`, `queries()`, `semantic()` et les classes imbriquées `Storage`, `Indexing`, `Queries`, `Semantic` (finales, constructeur package-private, accesseurs aux mêmes noms rendant les mêmes instances) ;
- **inchangées** : `open`, `builder`, `close`, `home`, `storageBackendId`, `compositionRoot`, `hostedControlPlaneService`, les constantes et tout le `Builder`. Aucune signature de `com.minos.api` ne change (les constructeurs `Local*Api(MinosApplication)` gardent leur type).
- Coût : 13 classes de production et 23 classes de test dans 7 modules ; **assertions des deux tests de caractérisation A2 réécrites** (`A2CompositionCharacterizationTest` : `indexStateStore`, `projectRegistry`, `retentionService` ×2, `indexerDescriptors`, `providerRuntimeManager` ; `A2SurfaceCharacterizationTest` : `fingerprintService`, `fingerprintStore`, `indexStateStore`, `retentionService`, `snapshotStore`) ; `check-post-mne.py` (chaîne exigée 4 fois) ; `public-surfaces.md` ; `java-api.md` § Ruptures (seconde rupture de `MinosApplication` en deux lots).
- Gain : la surface de premier niveau passe de 36 méthodes d'instance à 8 ; lecture par domaine. **Le couplage ne baisse pas** : chaque surface reçoit toujours la `MinosApplication` entière, et les façades exposent les mêmes 32 services.

**Option B : regroupement interne, aucune signature changée (recommandée).** `MinosApplication` garde ses 36 méthodes d'instance publiques, mêmes noms, mêmes types, mêmes instances. À l'intérieur :

- les collaborateurs résolus par l'assembleur sont regroupés en deux porteurs package-private par domaine (stockage : les six magasins et la rétention ; indexation : découverte, empreintes, invalidation, planificateur, runtimes, descripteurs, catalogue, import SCIP, cycle de vie des snapshots) ; le constructeur package-private passe de 24 à une dizaine de paramètres ;
- les 13 services dérivés (requêtes et sémantique) restent construits dans le constructeur, dans le même ordre, regroupés en deux porteurs privés ;
- les accesseurs publics sont rangés par domaine, sous un en-tête de section, et délèguent en une ligne au porteur ; ce ne sont pas des alias (chaque service n'a qu'un chemin d'accès) ;
- `storageBackend.id()` reste consulté par le constructeur **après** les magasins et la rétention (ordre figé par `A2CompositionCharacterizationTest`) ; les porteurs sont des records sans effet de bord construits par l'assembleur, qui ne leur passe jamais `null`.
- Coût : `MinosApplication` et `MinosApplicationAssembler` seulement ; aucune rupture, aucun appelant, aucun script, aucun test de caractérisation modifié. Limite : la surface publique reste de 36 méthodes ; le constat « expose environ 30 services » est traité par décision (une racine de composition expose son graphe ; la réduction du couplage passe par des surfaces qui reçoivent des ports étroits, § A4.8) plutôt que par déplacement.

Pourquoi B : A remplace 32 signatures par 4 façades qui exposent exactement les mêmes services, sans réduire la dépendance de chaque surface à l'objet entier. Sans alias possible (règle 3), c'est une rupture en bloc pour tout consommateur Java, la deuxième en deux lots (A3 a déjà changé six signatures), et elle oblige à réécrire les assertions des deux caractérisations, qui sont précisément le témoin de la règle 1. B obtient le regroupement demandé par la règle 5 sans rien casser. Le vrai levier contre la classe-dieu, des surfaces qui ne demandent que ce qu'elles utilisent, touche les constructeurs publics de `minos-api` et relève d'un autre lot.

### A4.3.4 Cache de `LocalProjectArchitectureQuery` : **non prouvable, non fait**

Chaque appel refait : (1) la résolution du projet dans le registre ; (2) le chargement du snapshot actif ; (3) la **découverte du projet** (`ProjectDiscoveryService.discover(rootPath)`) ; (4) topologie, dépendances, concentration, centralité, technologies.

- **Le snapshot n'est pas redécodé** (l'audit est inexact sur ce point) : `FileSymbolSnapshotStore` relit le pointeur actif à chaque appel (`activeSnapshotRepository.read`) et sert la vue en cache si le `SnapshotDescriptor` (identifiant et `sha256`) est inchangé ; `PostgresCodeKnowledgeSnapshotStore` fait de même avec `(projectId, snapshotId, sha256)`. Ce pointeur relu à chaque appel voit les écritures des autres processus sur le même home. Il existe donc une identité de snapshot observable, bon marché et sûre entre processus (`loadActiveQueryView(id).descriptor()`).
- **La découverte lit l'arborescence vivante du projet** : `Files.walkFileTree` depuis la racine, règles d'ignorance (`ProjectIgnorePolicy.load`), détection des modules et des racines de sources, budget de parcours. Elle change sans aucun changement de snapshot (un `pom.xml` ou un module ajouté, un `.gitignore` modifié, la racine déplacée ou supprimée), et **aucune génération ne la date** : savoir si elle a changé coûte le parcours qu'on voudrait éviter. Toutes les vues d'architecture en dépendent (`topologyService.build(discovery, snapshot)`, et le reste en découle).
- Un cache indexé sur le snapshot servirait donc une topologie périmée dès que l'arborescence change entre deux indexations, et masquerait les échecs actuels (racine supprimée : `IllegalArgumentException` aujourd'hui, réponse en cache demain ; budget de parcours dépassé). La réponse deviendrait « l'arborescence au premier appel » au lieu de « l'arborescence maintenant » : changement de comportement.
- Le seul cache prouvable serait une mémoïsation de l'étape (4), clé (descripteur du snapshot, `ProjectDiscovery` par égalité de valeur), les analyseurs étant sans état. Elle laisserait (1) à (3) intacts, donc le parcours de fichiers, et son gain n'est pas mesuré. Elle n'entre pas dans ce lot : constat de performance en § A4.8, avec mesure préalable.

Conclusion : **pas de cache** dans A4.

**Correction de la formulation de l'audit.** « `LocalProjectArchitectureQuery` recalcule la découverte, le snapshot et la topologie à chaque appel » est inexact pour le snapshot : `FileSymbolSnapshotStore` (`storage.local.store`) et `PostgresCodeKnowledgeSnapshotStore` (`storage.postgresql`) gardent la vue du snapshot actif en cache, sous la clé identifiant + `sha256` du pointeur actif, relu à chaque appel. Seuls la découverte (parcours de l'arborescence) et les analyses (topologie, dépendances, concentration, centralité, technologies) sont recalculés.

## A4.4 Points soumis à arbitrage (jalon 1)

1. **`MinosApplication` : option A (façades, 32 ruptures) ou option B (regroupement interne, aucune rupture).** Recommandation : **B** (§ A4.3.3).
2. **Cache de `LocalProjectArchitectureQuery`** : recommandation **non** (§ A4.3.4) ; constat de performance consigné en § A4.8.
3. **`MinosCli.builder`** : garder publics les trois mutateurs que le constructeur public n° 2 (sans appelant) rendait accessibles (`projectOperations`, `architectureQuery`, `impactQuery`), pour ne rien restreindre au passage, ou ne rendre publics que `builder(symbolQuery)` et `build()`. Recommandation : les garder publics (ni élargissement, ni restriction non demandée).
4. **Documentation des constructeurs publics retirés** hors `minos-api` et `MinosApplication` (3 de `LocalProjectArchitectureQuery`, 2 de `MinosCli`) : la règle 4 ne l'exige pas, mais A3 a listé dans `java-api.md` § Ruptures des types publics internes. Recommandation : un paragraphe court dans `java-api.md` § Ruptures et une ligne en tête de PR.
5. **`LazyAutonomousIndexOperations`** (renvoyé à « A4 » par la liste du lot A3) : code mort de `minos-cli`, sans appelant ni test, sans lien avec les constructeurs. Recommandation : hors lot, renvoyé à Q10 (l'audit l'y cite déjà).
6. **Nom de la fabrique** `LocalProjectArchitectureQuery.defaults(...)` (convention `ProviderPlatformService.defaults`).

## A4.5 Décisions (arbitrage de l'orchestrateur, 29 septembre 2026)

| # | Point | Décision | Mise en œuvre |
|---|---|---|---|
| 1 | `MinosApplication` | **option B**, regroupement interne sans rupture ; porteurs package-private immuables qui ne créent aucune instance ; constructeur ramené à une dizaine de paramètres ; invariants d'identité couverts par un test ; option A consignée comme écartée | `98df6d4c` ; `MinosApplicationWiringTest` (`2d0a06e4`) ; ADR 0045 § 2 |
| 2 | Cache | **non** ; constat de performance renvoyé à A6 ; formulation de l'audit corrigée | § A4.3.4, § A4.8, ADR 0045 § 3 |
| 3 | `MinosCli.builder` | mutateurs publics = exactement ce qu'ouvraient les constructeurs publics (`builder`, `projectOperations`, `architectureQuery`, `impactQuery`, `build`) ; les autres package-private | `b89f20c5` ; `MinosCliSurfaceTest` |
| 4 | Constructeurs publics retirés | paragraphe dans `java-api.md` § Ruptures et ligne en tête de PR | `910f78fc` |
| 5 | `LazyAutonomousIndexOperations` | hors lot, renvoi Q10 | § A4.8 |
| 6 | Nom de la fabrique | `defaults(...)` | `08ea7a76` |

Ligne pour l'en-tête de PR : « Ruptures (A4, ADR 0045) : 5 constructeurs publics retirés — `LocalProjectArchitectureQuery` ×3 → `LocalProjectArchitectureQuery.defaults(...)`, `MinosCli` ×2 → `MinosCli.builder(...)...build()` ; aucune signature de `com.minos.api` ni de `MinosApplication` ne change (voir `docs/user/java-api.md` § Ruptures). »

## A4.6 Journal

- 2026-09-29 — impl-archi, jalon 1 : worktree `a4-surfaces` sur `324d85bb`, compilation complète (`test-compile`, 14 modules) verte. Inventaire tiré du bytecode (§ A4.2), conception (§ A4.3), analyse du cache (non prouvable), six points soumis (§ A4.4). ADR 0045 en `Proposed`. Aucun code touché.

- 2026-09-29 — impl-archi : `970273b2` (docs) — ce suivi et l'ADR 0045 (Proposed). Gates docs verts.
- 2026-09-29 — impl-archi, jalon 2 étape a (garde-fous) : `LocalProjectArchitectureQuerySurfaceTest` (minos-application) et `MinosCliSurfaceTest` (minos-cli) écrits d'abord et exécutés sur la base `324d85bb` : **6 tests sur 6 rouges** (LPAQ : `expected: <1> but was: <9>`, `static factories: []`, `NoSuchMethodException` sur `defaults` ; MinosCli : `expected: <1> but was: <10>`, deux `ClassNotFoundException: com.minos.cli.MinosCli$Builder`). **Écart** : ils ne sont pas commités seuls, un commit rouge enfreignant « chaque commit garde les gates verts » ; chacun entre dans le commit de la refonte qu'il garde (b, c), la preuve rouge est ce journal (journal Maven conservé hors dépôt).
- 2026-09-29 — impl-archi, étape b (`08ea7a76`) : `LocalProjectArchitectureQuery` — constructeur privé `(ProjectResolver, CodeKnowledgeSnapshotStore, ProjectDiscoveryService)` et `defaults(ProjectRegistry, CodeKnowledgeSnapshotStore, ProjectDiscoveryService)` ; 8 constructeurs supprimés ; analyseurs sans état en champs initialisés en ligne. Valeurs par défaut vérifiées argument par argument : `MinosApplication` passe sa propre découverte ; `LocalProjectArchitectureQueryTest` passe `new ProjectDiscoveryService()` (ce qu'injectait l'ancien constructeur à 2 arguments) ; `new ProjectResolver(registry)` et les analyseurs neufs comme avant ; messages `registry`, `snapshotStore`, `discoveryService` inchangés (testés). Garde-fou vert ; tests d'architecture, `MinosApplicationTest`, A2 (17) verts.
- 2026-09-29 — impl-archi, étape c (`b89f20c5`) : `MinosCli.builder(ProjectSymbolQuery)`, `private MinosCli(Builder)` (corps du constructeur canonique inchangé), `public static final class Builder` (constructeur privé). 10 constructeurs supprimés ; 5 appelants migrés dans le commit. `MinosCliRunner.run` : `autonomousIndex` passé deux fois (`autonomousOperations` et `resumeStatus`), `hostedControlPlaneService().ifPresent(...)` au lieu de `orElse(null)`, ordre d'évaluation des collaborateurs inchangé ; `statelessHelpCli` : `home(Path.of("."))` conservé. `symbolQuery` nul : même `NullPointerException("symbolQuery")`, levée par `builder(null)`. `MinosCliBuilderTest` : les 15 commandes facultatives « not configured » sans collaborateur (même message, code 1), `doctor` exige opérations et `home`, `git-activity` exige opérations et Git, rejet de `null` par chaque mutateur. 128 tests de minos-cli, A2 et `ScipRelatedTestSnapshotIntegrationTest` verts ; `check-remote-distributed-consistency`, `product-facts --check`, `check-mnd`, `check-post-mne` verts.
- 2026-09-29 — impl-archi, étape d, test (`2d0a06e4`) : `MinosApplicationWiringTest` (minos-bootstrap) fige avant la refonte les identités de câblage : `AdvancedImpactService` reçoit `impactQuery()` et `programGraphService()`, `SecurityAnalysisService` reçoit `programGraphService()`, `SemanticIndexService` et `HybridSearchService` partagent le même `ProjectResolver`, chaîne sémantique et magasins exposés. Ces collaborateurs n'ont pas d'accesseur : lecture des champs privés par réflexion dans le test (classpath, pas de JPMS), aucune visibilité de production élargie. Vert sur la tête d'alors.
- 2026-09-29 — impl-archi, étape d (`98df6d4c`) : `MinosApplication` option B — records package-private `Stores` (6 magasins + rétention) et `Indexing` (10 collaborateurs), records privés `Queries` (9 services) et `Semantic` (4) ; constructeur 24 → 9 paramètres ; `storageBackend.id()` toujours consulté avant la validation des magasins, `requireNonNull` dans l'ordre et avec les messages d'avant, copie défensive des descripteurs toujours faite par le constructeur (champ dédié), services dérivés créés dans le même ordre. `javap -public` identique à `324d85bb` à l'ordre près (51 membres) ; `Builder` intact. Deux mutants (résolveur distinct pour la recherche hybride ; requête d'impact distincte pour l'analyse avancée) rendent `MinosApplicationWiringTest` rouge (2 échecs), puis retirés. `check-runtime-dynamic`, `check-hosted-control-plane`, `check-semantic-retrieval`, `check-p0-p2`, `check-module-boundaries` verts.
- 2026-09-29 — impl-archi, étape e (docs) : ADR 0045 Accepted ; `java-api.md` § Ruptures (5 constructeurs publics et leurs remplaçants) ; ce suivi (décisions, V-A4-01, V-A4-02). `docs/developer/public-surfaces.md` ne décrit aucun des constructeurs retirés et liste des accesseurs de `MinosApplication` inchangés : pas de mise à jour.
- 2026-09-29 — impl-archi, clôture du jalon 2 (tête `910f78fc`) : `./mvnw -B clean verify` complet **vert**, 15 modules, **1382 tests, 0 échec, 0 erreur, 46 ignorés** (base 1369/0/46 ; +13 = `LocalProjectArchitectureQuerySurfaceTest` 3, `MinosCliSurfaceTest` 3, `MinosCliBuilderTest` 4, `MinosApplicationWiringTest` 3) ; par module : domain 18, engine 353, runtime-local 214, storage-local 147, provider-scip 91, integration-git 27, application 90, storage-postgresql 60, bootstrap 72, nexus 10, cli 128, api 56, mcp 33, app 76 + 7. `check-jacoco.py` : seul m24 rouge, chiffres de la base (`ManagedPolyglotScipRuntimeManager` line 0.232, branch 0.161). Gates : 23 scripts verts (les deux scripts de release à arguments obligatoires ne s'appliquent pas hors release). `git diff --stat 324d85bb -- minos-app/src/test/resources/characterization` vide ; les deux tests de caractérisation A2 inchangés.
- 2026-09-29 — impl-archi, V-A4-04 (`9ad3e074`) : `MinosCliRunnerResumeStatusTest` (minos-cli) passe par `MinosCliRunner.run` avec la composition réelle (`LocalAutonomousIndexOperations` comme `IndexResumeStatusSource`) et une **copie** de la fixture de run interrompu reprenable de `MinosApplicationMcpBackendResumeStatusTest` (ADR 0039 § 6), factorisée ensuite (V-A4-06, `6fb603ab`) ; `index-status --format json` doit afficher `resumableRunId`, la phase et les cibles. C'est le test de « câblage complet » annoncé au § A4.3.2, que `MinosCliBuilderTest` ne contenait pas. Preuve : vert sur `39216e09` ; **rouge** avec la mutation `.resumeStatus(autonomousIndex)` retirée de `MinosCliRunner` (`"resumableRunId":null`), vert après restauration. Tests ciblés : 129 tests de minos-cli, A2 et `ScipRelatedTestSnapshotIntegrationTest` (18) verts. Pas de nouveau `clean verify` complet (un test et des docs seulement) ; V-A4-03 et V-A4-05 traités dans le commit de docs.
- 2026-09-29 — impl-archi, V-A4-06 (`6fb603ab`) : la fixture recopiée est extraite dans `com.minos.bootstrap.ResumableRunFixtures` (test-jar de minos-bootstrap, sur le modèle de `LocalRemoteIndexingRuntimeFixtures`) : `seedInterruptedResumableRun(MinosApplication, Path)` crée et enregistre le projet `PROJECT_NAME`, dépose le run `INTERRUPTED` reprenable et l'état `FAILED` qui le désigne, et rend l'identifiant du run. `MinosCliRunnerResumeStatusTest` et `MinosApplicationMcpBackendResumeStatusTest` l'appellent ; leurs assertions restent dans chaque test (JSON de la CLI contre vue MCP, qui vérifie en plus l'absence de chemins). Seule différence avec la copie : le chemin de l'artefact est dérivé de `application.home()` au lieu de `temp/home`, même valeur. POM : `minos-mcp` déclare `minos-bootstrap` `test-jar` en portée `test` (comme minos-cli ; minos-mcp ne déclarait que `minos-bootstrap` en portée `runtime`) ; commentaire du test-jar de `minos-bootstrap` mis à jour. `check-module-boundaries` et son auto-test verts. Mutation `run-resume` rejouée : `MinosCliRunnerResumeStatusTest` rouge (`"resumableRunId":null`), vert après restauration. Tests ciblés (`-am`) : bootstrap 72 (1 ignoré), cli 129, mcp 33, verts.

## A4.7 Constats verif-archi

« 970273b2 (docs : inventaire, conception, ADR 0045 Proposed) — inspecté par verif-archi. Ajout pur après la L483 (partie A3 intacte), index ADR au format, aucun numéro de jalon dans un nom de fichier. 23 gates verts, golden identiques, comptes/helpers/éclatés inchangés, 7 témoins rouges. Inventaire recoupé indépendamment par le bytecode : constructeurs appelés (LPAQ n° 1-2 ; MinosCli n° 1, 4, 9, 10), 6 + 6 morts, 36 lignes d'accesseurs et totaux 13 classes main / 23 classes test identiques. »

« Jalon 2 (08ea7a76, b89f20c5, 2d0a06e4, 98df6d4c) et docs (910f78fc, 39216e09) — inspectés par verif-archi. Code de 39216e09 identique à 98df6d4c. clean verify complet de 98df6d4c (worktree verif) : BUILD SUCCESS, 1382 tests / 0 échec / 0 erreur / 46 ignorés (+13), JaCoCo seul m24 rouge (chiffres de la base). Sur les six commits : 23 gates verts, 12 golden identiques, 7 témoins rouges, 0 package éclaté, helpers 63/31/8, 0 @Disabled, aucun pom touché. javap -public : MinosApplication et son Builder identiques à 324d85bb (à l'ordre près), minos-api identique ; LocalProjectArchitectureQuery −3 constructeurs publics +defaults ; MinosCli −2 constructeurs publics +builder, Builder public (projectOperations, architectureQuery, impactQuery, build). Ordre de construction et de validation de MinosApplication relu ligne à ligne ; A2 verts. Pas de cache. Mutations rouges : constructeur public ou package-private réintroduit (LPAQ), constructeur MinosCli(ProjectSymbolQuery) réintroduit, mutateur home rendu public, résolveur distinct, requête d'impact distincte, hosted non câblé (team.golden). Mutation survivante : resumeStatus retiré du runner (V-A4-04). »

« V-A4-04 et docs (9ad3e074, df593b1d) — inspectés par verif-archi. clean verify complet de df593b1d (worktree verif) : BUILD SUCCESS, 1383 tests / 0 échec / 0 erreur / 46 ignorés, check-jacoco.py seul m24 rouge (chiffres de la base). 23 gates verts, 12 golden identiques, 7 témoins rouges, helpers 63/31/8, 0 package éclaté, 0 @Disabled, aucun pom touché. Mutation run-resume rejouée sur df593b1d : MinosCliRunnerResumeStatusTest rouge ("resumableRunId":null), vert sans mutation ; test indépendant de l'hôte. V-A4-03 résolu. Nouveau : V-A4-06 (à corriger) — fixture recopiée. »

Les cinq écarts déclarés au jalon 2 (§ A4.6 et rapport : pas de commit rouge pour l'étape a, `NullPointerException("symbolQuery")` levée par `builder(null)`, `ifPresent` au lieu de `orElse(null)`, Javadoc mixte préexistante, `public-surfaces.md` inchangé) sont acceptés par verif-archi.

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|
| V-A4-01 | `970273b2` | L'ADR 0045 (contexte et conséquences) écrit « 36 méthodes d'instance publiques » ; `javap` en compte 37 (35 accesseurs, `indexerRegistry(String)`, `close()`). | remarque | **résolu** (`910f78fc`) : « 36 méthodes d'instance publiques hors `close()` », dans l'ADR et au § A4.2.1 ; décompte précisé par V-A4-03. |
| V-A4-02 | `970273b2` | § A4.2.5 classe `scripts/history/m15/run-s5.ps1` comme « gelé » ; il est archivé (ADR 0043), non exécuté, et lit déjà un fichier supprimé par A3. | remarque | **résolu** (`910f78fc`) : « archivé, non exécuté ; sans contrainte ». |
| V-A4-03 | `910f78fc` | ADR 0045 (contexte) et § A4.2.1 : « 35 accesseurs, dont 32 méthodes de services » ; il faut « 35 accesseurs (dont 31 de services) et la fabrique `indexerRegistry(String)`, soit 32 méthodes de services ». | remarque | **résolu** (`df593b1d`) : formulation reprise mot pour mot aux deux endroits. |
| V-A4-04 | `98df6d4c` | `MinosCliRunner.java:105` : retirer `.resumeStatus(autonomousIndex)` laisse 87 tests de cli et de caractérisation verts (trou préexistant, mais ligne réécrite par A4 ; le test de « câblage complet » annoncé au § A4.3.2 manquait). | à corriger | **résolu** (`9ad3e074`) : `MinosCliRunnerResumeStatusTest`, rouge avec la mutation, vert sans elle (§ A4.6). |
| V-A4-05 | `b89f20c5` | `statelessHelpCli` : `home(Path.of("."))` n'est jamais atteint, `doctor` ne figurant pas dans `STATELESS_HELP_COMMANDS`. Préexistant, hors lot. | remarque | **noté** au § A4.8, code inchangé. |
| V-A4-06 | `9ad3e074` | `MinosCliRunnerResumeStatusTest` recopie la fixture de `MinosApplicationMcpBackendResumeStatusTest` (≈ 19 lignes, 3 assertions) alors que commit, Javadoc et § A4.6 disent « réutilise ». | à corriger | **résolu** (`6fb603ab`) : fixture factorisée dans `ResumableRunFixtures` (test-jar de minos-bootstrap), appelée par les deux tests ; dépendance test-jar ajoutée à `minos-mcp` ; Javadoc, § A4.6 et journal corrigés ; mutation `run-resume` toujours rouge. |

« V-A4-06 et docs (6fb603ab, 4faed2d3) — inspectés par verif-archi. clean verify complet de 4faed2d3 (worktree verif) : BUILD SUCCESS, 1383 tests / 0 échec / 0 erreur / 46 ignorés, check-jacoco.py seul m24 rouge (chiffres de la base). 23 gates verts (check-module-boundaries et son auto-test ; diagramme généré non périmé, l'arête minos-mcp → minos-bootstrap existait déjà en runtime), 12 golden identiques, 7 témoins rouges, helpers 63/31/8, 0 package éclaté, 0 @Disabled. Fixture unique (ResumableRunFixtures, test-jar de minos-bootstrap), identique à l'ancienne (chemin d'artefact dérivé de home(), même valeur normalisée) ; assertions CLI et MCP conservées, dont l'absence de chemin côté MCP ; dépendance test-jar de minos-mcp en portée test seulement. Mutation run-resume rejouée sur 4faed2d3 : rouge. V-A4-06 résolu. Bilan : aucun constat ouvert (V-A4-05 remarque hors lot, § A4.8) ; lot A4 prêt pour la PR. »

**Statut du lot A4 : accepté par verif-archi, aucun constat ouvert.**

## A4.8 À traiter plus tard (hors périmètre)

| Origine | Description | Renvoi |
|---|---|---|
| impl-archi (jalon 1) | Constructeurs multiples hors des trois classes du lot (§ A4.2.4) : `IndexingLifecycleService` (6), `LocalMinosApi` (5), `LocalAutonomousIndexOperations` (4), `LocalRemoteIndexOperations` (4), `LocalProjectImpactQuery` (4), `IndexingRuntimePorts.IndexingExecutionRequest` (4), `ProgramGraphService` (4), `FileSymbolSnapshotStore` (4). | A4 (suite) |
| impl-archi (jalon 1) | Performance de `LocalProjectArchitectureQuery` : chaque appel reparcourt l'arborescence du projet (découverte) et recalcule topologie et dépendances. Un cache n'est pas prouvable sur la découverte (arborescence vivante sans génération) ; seule une mémoïsation des analyses, clé (descripteur du snapshot, `ProjectDiscovery`), l'est. À décider après mesure (part du parcours contre part des analyses sur un gros dépôt). | A6 (mémoire et scalabilité) |
| impl-archi (jalon 1) | Couplage réel à `MinosApplication` : les surfaces (`Local*Api`, `MinosApplicationMcpBackend`, `LocalAutonomousIndexOperations`, `NexusSemanticSignalService`…) reçoivent l'application entière pour n'en utiliser que quelques services. Les faire dépendre de ports étroits touche les constructeurs publics de `minos-api`. | A4 (suite), rupture documentée |
| impl-archi (jalon 1) | `docs/developer/architecture.md` : le diagramme relie `MinosCli` à `LocalProjectArchitectureQuery`, `LocalProjectSymbolQuery` et `LocalProjectImpactQuery` ; `MinosCli` ne connaît que les ports. | docs |
| lot A3 (SPRINT-2-SUIVI V19) | `minos-cli/LazyAutonomousIndexOperations` n'est câblé nulle part en production (point 5 de § A4.4, décision 5 de § A4.5). | Q10 |
| verif-archi (V-A4-05) | `MinosCliRunner.statelessHelpCli` câble `home(Path.of("."))` (et donc `doctor`), mais `doctor` n'est pas dans `STATELESS_HELP_COMMANDS` : ce collaborateur n'est jamais atteint. Préexistant ; A4 l'a conservé à l'identique. À supprimer ou à justifier. | hygiène minos-cli |
| impl-archi (jalon 2) | `MinosApplication.java` mélange Javadoc anglaise (classe, `open`, `Builder`, porteurs ajoutés par A4) et française (`providerCatalog`, `scipArtifactImporter`, `compositionRoot`, champ `composer`), antérieur à A4 ; A4 n'ajoute que de l'anglais, langue majoritaire du fichier. | hygiène docs |

# Lot 3 — A6 : mémoire et scalabilité

> Branche : `archi/a6-scalabilite`, créée depuis la tête d'A4 (`d9ae1005`), worktree `minos-wt/a6-scalabilite`.
> Constat : **A6** (`AUDIT-2026-09.md` § 3, statut PLAUSIBLE), plus le constat de performance renvoyé par A4 (`LocalProjectArchitectureQuery`, § A4.8).
> Agents : `impl-archi` (implémentation), `verif-archi` (inspection de chaque commit). Aucun push, aucune PR ouverte par les agents.
> Règles du lot : aucun changement de comportement (les deux tests de caractérisation A2 verts, les 12 golden de `minos-app/src/test/resources/characterization/` identiques octet pour octet, jamais régénérés) ; **aucun changement recevable sans mesure avant/après** ; ordre imposé : (1) alignement des plafonds, (2) chaînes en UTF-8 (nouvelle version de format), (3) bornes de la traversée d'impact, (4) normalisation de la recherche hybride à classement identique, (5) snapshot paginé ou mappé : ADR `Proposed` seulement.

## A6.1 Tableau de bord

| Jalon | Contenu | Statut | Commits |
|---|---|---|---|
| 1 | Corpus réel (ce dépôt indexé par scip-java géré), harnais `benchmarks/scalability/`, mesures avant, verdicts et recommandations ; aucune optimisation | livré, accepté par verif-archi, arbitré (§ A6.9) | `fb363253`, `d491519d` |
| 2.1 | Étape 1 — plafond : taille encodée exacte, refus avant toute écriture (fichier et PostgreSQL) | livré, vérifié par verif-archi | `e23b5ded` ; V-A6-01 à 04 : `1acb68e8` |
| 2.2 | Étape 2 — format V3 UTF-8 (fichier, pointeur, PostgreSQL), ADR 0046 ; exception `retention.golden` prouvée | livré, vérifié par verif-archi | `a68c560e`, `ebdc6969` (golden seul + preuve), `095c4c60` (banc), `5f0c2d53` (docs) |
| 2.3 | Étape 3 — traversée d'impact : non faite, verdict consigné (§ A6.12) | consigné | `ea5132b6` |
| 2.4 | Étape 4 — contenu normalisé mis en cache dans le corpus hybride, classement identique à l'octet (§ A6.13) | livré | `1c54a3a4` (référence, avant), `bba7a91b` ; M3 : `e32bd9f6` |
| 2.5 | Étape 5 — ADR 0047 `Proposed` : dédoublonnage des chaînes, table de chaînes, pagination ou mappage (§ A6.14) | livré | `a3aa4baf` |
| **Bilan** | Ce dépôt redevient indexable (V3, 194,6 Mo au lieu d'un refus à 355 Mo en V2) ; un snapshot trop grand est refusé sans rien écrire ; lecture à froid ≈ 2,5 fois plus rapide ; recherche hybride en mémoire 4 à 6 fois plus rapide, à classement identique à l'octet ; traversée d'impact non touchée ; seul golden modifié : `retention.golden` (suffixes sha, preuve) ; aucun gain mémoire revendiqué | lot terminé, en attente de verif | — |

## A6.2 Procédure de mesure

**Corpus réel.** Ce dépôt à la révision `d9ae1005`, indexé par le runtime scip-java 0.13.1 géré par MINOS (`benchmarks/scalability/prepare-corpus.ps1`) : 883 documents, SCIP de **23 816 951 octets** (22,7 Mio), 40 112 symboles, 281 342 occurrences à la pré-analyse. Rejoué deux fois : même taille à un octet près. Trois écarts à la procédure produit, tous documentés dans le README du banc :

- `minos index` n'a pas pu servir : le bac à sable AppContainer non élevé refuse `pwsh.exe` installé sous `Program Files` (« cannot grant AppContainer read access … without administrator privileges »). Le runner Windows géré (`scip-java-windows-runner.ps1`) est appelé directement, avec les mêmes arguments que `ScipJavaProcessPlanFactory`.
- `mvnw`, `mvnw.cmd` et `.mvn` sont retirés de la copie : scip-java préfère `./mvnw`, qui n'est pas un exécutable Win32 (`CreateProcess error=193`).
- `minos-app` construit dans le `target/` racine ; son `clean` efface `target/scip-targetroot`, où scip-java agrège tous les modules. Sans correctif, l'index ne contient que `minos-app` (39 documents, 1 040 831 octets, conservé comme point de ratio `minos-app-module`). La copie construit `minos-app` dans son propre `target/` (§ A6.8).

SCIP complémentaires pour le ratio : `ariane-chatbot` (2,49 Mo) et `nexus-context-engine` (2,38 Mo), déjà présents sur le poste, et trois fixtures TypeScript suivies du dépôt.

**Montée en échelle calibrée.** Deux familles de jeux, sans synthèse ex nihilo :

- `file-fX` : tranche stable de fichiers du corpus réel (sélection par hachage du `fileId`, fraction X ; symboles, occurrences et relations de ces fichiers). Elle garde les distributions par fichier du corpus réel. Publiée dans le magasin fichier et mesurée par le chemin produit (`FileSymbolSnapshotStore`, cache de vues, `HybridSearchService` de `MinosApplication`).
- `mem-kN` : le corpus entier (non persistable en V2, § A6.3.2) répliqué N fois, identifiants préfixés `r<i>~`, servi par un magasin en mémoire du banc. Il mesure ce que coûteraient les requêtes si le snapshot pouvait être chargé.

**Harnais.** `minos-bootstrap/src/test/java/com/minos/bootstrap/scalability/SnapshotScalabilityBenchmark.java` (`main` des sources de test, nom hors des motifs d'inclusion de Surefire : il ne tourne pas dans `mvn verify`, comme `InMemoryBackendBenchmark` et `CodeSearchBenchmark`), `benchmarks/scalability/run-scalability-benchmark.ps1`, `scalability.json`, `README.md`. Les tailles sont obtenues par un recensement qui parcourt le snapshot dans l'ordre des champs du codec V2 ; il est vérifié contre l'encodeur réel (`censusMatchesEncoder=true` sur tous les jeux sous le plafond). Le tas est mesuré après trois `System.gc()` ; les parts de temps par échantillonnage JFR (Java et natif) du seul fil de mesure ; les allocations par `ThreadMXBean.getCurrentThreadAllocatedBytes`.

**Environnement.** AMD Ryzen 7 5700G (16 fils logiques), 48 Go, Windows 10 19045, OpenJDK 24.0.1+9-30, G1, `-Xmx24g`. Préchauffage 3, 15 itérations (imports : 3 itérations, donc p95 = maximum ; chargements à froid : 5 ; architecture : 1 + 5). Données (corpus, `MINOS_HOME` jetable, résultats bruts) dans le scratch de la session, jamais dans le dépôt. Exécutions : `run1` (ratio, jeux `file-f*`, impact `mem-k1`) et `run2` (jeux `mem-k*`, architecture), § A6.6.

## A6.3 Mesures avant (base `d9ae1005`)

### A6.3.1 Ratio SCIP → snapshot V2 et composition

| SCIP | Octets SCIP | Occurrences | Snapshot V2 | V2/SCIP | Part des chaînes | UTF-8/V2 | Table de chaînes/V2 | Tas du snapshot importé / V2 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| minos-full (ce dépôt) | 23 816 951 | 281 342 | 338 368 857 | **14,21** | 97,0 % | 0,550 | 0,194 | 0,48 |
| minos-app-module | 1 040 831 | 13 050 | 13 859 114 | 13,32 | 97,1 % | 0,551 | 0,200 | 0,50 |
| ariane-chatbot | 2 489 722 | 25 956 | 31 827 003 | 12,78 | 97,0 % | 0,551 | 0,206 | 0,50 |
| nexus-context-engine | 2 376 589 | 23 275 | 26 696 018 | 11,23 | 97,0 % | 0,551 | 0,204 | 0,51 |
| typescript-simple | 14 546 | 100 | 131 481 | 9,04 | 96,9 % | 0,551 | 0,234 | 0,55 |
| typescript-modules | 10 611 | 67 | 108 253 | 10,20 | 96,9 % | 0,550 | 0,226 | 0,49 |
| typescript-inheritance | 10 839 | 57 | 112 407 | 10,37 | 96,7 % | 0,552 | 0,222 | 0,47 |

- Tous les corpus sont 100 % ASCII (aucun caractère non ASCII, aucun surrogate isolé) : l'UTF-8 ramène le fichier à **0,55×** ; la « table de chaînes » (chaque chaîne distincte stockée une fois, un index de 4 octets par occurrence de champ) à **0,19–0,23×**.
- Composition des 338 Mo de `minos-full` : identifiants d'entités 23,8 %, `fileId` 20,7 %, références provider (symbole SCIP complet répété sur chaque occurrence) 16,1 %, `Origin` (4 chaînes répétées sur chaque entité) 10,2 %, noms d'énumérations écrits en toutes lettres 8,9 %, `projectId` (UUID en texte, répété) 7,5 %, textes de symboles 4,1 %, divers 5,8 %, octets fixes 3,0 %. La redondance, plus que l'UTF-16, fait la taille : 5 985 691 champs chaîne pour 439 400 chaînes distinctes.
- 1 062 à 1 972 octets V2 par occurrence de la pré-analyse (1 203 pour `minos-full`). Le ratio dépend aussi de la longueur de `indexRunId`, recopié dans chaque `Origin` : les chiffres de verif-archi (12,45 ; 10,79 ; 8,51), obtenus avec un autre `indexRunId`, en diffèrent de 3 à 6 %.
- Recoupement avec verif-archi (§ A6.7) : mêmes ordres de grandeur, même UTF-8 à 0,55×.
- **Origin du produit (V-A6-03).** Le tableau ci-dessus a été mesuré avec l'`Origin` du banc d'alors (`indexRunId` de 22 à 34 caractères, sans version de fournisseur). `minos import-scip` écrit `indexRunId = "application-" + snapshotId` (41 caractères) et la version du fournisseur, recopiés sur chaque entité : de l'ordre de 50 octets V2 de plus par entité. Remesuré avec une requête d'import calquée sur le produit (banc corrigé, même commit que V-A6-01) : ce dépôt **355 045 885 octets, ratio 14,91**, nexus 11,58, typescript-simple 9,34, ariane 13,32 ; UTF-8/V2 inchangé (0,548 à 0,550). Seuil SCIP du plafond pour ce dépôt : **18,0 Mo** (et non 18,9) ; plage 18,0 à 28,7 Mo. Le refus de l'étape 1 calcule la taille sur le snapshot réellement ingéré, donc avec l'`Origin` réelle.

### A6.3.2 Plafond : le dépôt ne peut pas s'indexer lui-même

| Mesure (`minos-full`) | Médiane | p95 |
|---|---:|---:|
| Pré-analyse (`ScipIngestionLimits.preflight`) | 27 ms | 45 ms |
| Décodage protobuf seul | 134 ms | 244 ms |
| Import sans persistance (gel + sha256, décodage, ingestion) | 1 371 ms | 1 845 ms |
| Import produit dans le magasin fichier | 3 743 ms, **refusé** | 3 830 ms |

`importOutcome = REFUSED IOException: knowledge snapshot exceeds byte limit: 268436194/268435456`. Le SCIP (23,8 Mo) est à 4,4 % de la limite SCIP (512 Mio) ; le snapshot V2 (338 Mo) dépasse le plafond persisté (256 Mio) de 26 %. **63 % du temps de l'import refusé** est passé à écrire 256 Mio dans le fichier temporaire avant l'échec. Tranches : `file-f0.90` (292 Mo V2) est refusée aussi (2,4 s) ; `file-f0.50` (162 Mo) passe.

Seuil SCIP au-delà duquel le snapshot V2 ne tient plus : 256 Mio / ratio, soit **18,9 Mo** pour ce dépôt, 21,0 Mo (ariane), 23,9 Mo (nexus), 25,9 à 29,7 Mo (TypeScript). En UTF-8, 34,3 Mo pour ce dépôt ; avec une table de chaînes, 97 Mo.

### A6.3.3 Mémoire et cache de vues de requête

| Jeu | V2 sur disque | Chargement à froid (médiane / p95) | dont index | Tas vue complète | Tas snapshot seul | Tas index | Vue / V2 | Mise en cache |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| file-f0.25 | 76 096 547 | 753 / 893 ms | 93 ms | 106 436 544 | 87 635 816 | 18 800 728 | 1,40 | **non** |
| file-f0.50 | 162 226 063 | 1 459 / 1 549 ms | 123 ms | 226 007 584 | 186 554 424 | 39 453 160 | 1,39 | **non** |
| mem-k1 (corpus entier) | (338 368 857, non persistable) | — | 251 / 506 ms (`run1` / `run2`) | — | 163 253 096 (importé) | 81 605 736 | — | — |
| mem-k2 / mem-k3 | (682 439 968 / —) | — | 718–760 / 980 ms | — | — | 161 846 488 / 254 041 984 | — | — |

- Un snapshot relu depuis le disque pèse **1,15× sa taille V2** en tas, un snapshot fraîchement ingéré **0,48×** : la lecture crée une instance `String` par champ (le `projectId`, les noms d'énumérations, les quatre chaînes d'`Origin` et les `fileId` sont dupliqués par entité), alors que l'ingestion partage ces instances. En tas, les chaînes ASCII sont déjà compactes (1 octet par caractère) : l'UTF-16 ne coûte rien en mémoire, seulement sur disque.
- Le cache de vues estime le poids d'une vue à `max(comptages, 8 × taille du fichier)`. Le poids réel est de 1,4× la taille du fichier : l'estimation est **5,7 fois trop forte**. Conséquence : toute vue d'un snapshot de plus de 64 Mio persistés (512 Mio / 8) est refusée par le cache (`queryCacheEntriesAfterLoad = 0` dès `file-f0.25`, 76 Mo), et **chaque requête relit et redécode le snapshot entier** (0,75 s à 76 Mo, 1,46 s à 162 Mo). Le « cache de 512 Mo » de l'audit ne sert donc pas les gros projets. Même règle dans `PostgresCodeKnowledgeSnapshotStore` (amplification 8, plafond 512 Mio).

### A6.3.4 Recherche hybride

Chemin produit (`MinosApplication.hybridSearchService()`, magasin fichier, fournisseur sémantique désactivé), 4 requêtes, 15 itérations :

| Jeu | Documents | Construction du corpus (1re recherche) | Requête (médiane / p95) | Allocation par requête | Part normalisation (JFR) | Part rechargement du snapshot |
|---|---:|---:|---:|---:|---:|---:|
| file-f0.25 | 19 115 | 112,7 s | 1 349–1 381 / ≤ 1 522 ms | 627 Mo | 3,0 % | 96,4 % |
| file-f0.50 | 39 452 | 241,0 s | 3 075–3 172 / ≤ 3 574 ms | 1 338 Mo | 2,7 % | 96,8 % |

- Une recherche hybride charge le snapshot **deux fois** : `HybridSearchService.search` puis `SemanticIndexService.status`, qui appelle `loadActiveKnowledge` même quand le fournisseur sémantique est désactivé, pour renvoyer l'identifiant du snapshot. Au-delà de 64 Mio persistés (§ A6.3.3), ce sont deux relectures complètes par requête : 3,1 s à 162 Mo contre 1,46 s pour un chargement.
- La construction du corpus coûte environ 6 ms par document, linéairement (112,7 s pour 19 115 documents, 241 s pour 39 452, 467 à 515 s pour 81 095 en `mem-k1`, 1 005 s pour 161 307 en `mem-k2`) : `LocalSourceReader` relit le fichier source entier pour chaque extrait (son petit cache a été retiré pour la fraîcheur), alors que le commentaire de `SemanticDocumentFactory` affirme encore le contraire (§ A6.8).
- Hors rechargement (magasin en mémoire du banc, `run2`), la normalisation domine :

| Jeu | Documents | Construction du corpus | Requête (médiane / p95) | Allocation par requête | Part normalisation (JFR) | `containsTerm` | Reste de la recherche |
|---|---:|---:|---:|---:|---:|---:|---:|
| mem-k1 | 81 095 | 467,1 s | 147–161 / ≤ 222 ms | 63–65 Mo | **81,5 %** | 0,7 % | 17,7 % |
| mem-k2 | 161 307 | 1 005,2 s | **non mesurable** : corpus refusé par le cache | — | — | — | — |

  À 161 307 documents, le poids estimé du corpus dépasse le plafond du cache de corpus (256 Mio) : `corpusCacheEntries = 0`, et **chaque requête reconstruit le corpus entier** (1re recherche : 1 062,6 s). La mesure a été arrêtée là (4 × 18 requêtes auraient pris une vingtaine d'heures). La limite annoncée de 250 000 documents n'est donc pas atteignable en pratique : au-delà d'environ 158 000 documents (256 Mio / 1 697 octets estimés par document sur ce corpus), la recherche hybride devient inutilisable. Ce poids estimé compte 2 octets par caractère (`Character.BYTES`) ; le tas réellement retenu par le corpus `mem-k1` est de 55,2 Mo pour 137,6 Mo estimés (2,5× de trop), de sorte que le refus arrive bien avant que le tas ne l'exige.

  Chaque requête relance `normalize` sur le contenu de chaque document (minuscules, découpage en termes par point de code) : c'est l'essentiel du temps et des allocations. Mettre en cache le contenu normalisé dans `CachedCorpus` supprimerait ce travail sans toucher au calcul du score (même fonction pure, même contenu), donc à classement identique par construction ; coût : une chaîne normalisée par document, au plus la taille du contenu (poids estimé du corpus `mem-k1` : 137,6 Mo pour un plafond de 256 Mio).

### A6.3.5 Analyse d'impact profonde

`ImpactAnalysisService.analyze` appelé directement sur le snapshot en mémoire ; « coût fixe » = racine sans arête entrante (`maxDepth` 1, `maxResults` 1), donc la préparation seule (table des symboles, index des arêtes entrantes trié, limitations de base, tri final) ; trois racines de plus forte portée choisies parmi les 25 symboles les plus référencés.

| Jeu | Relations | Coût fixe (médiane / p95) | Racine la plus large : portée à `maxDepth` 32 | `maxResults` 10 000 | `maxResults` 10 | Allocation |
|---|---:|---:|---:|---:|---:|---:|
| file-f0.25 | 3 713 | 3,5 / 4,1 ms | 15 symboles | 2,9 ms | 2,4 ms | 0,59 Mo |
| file-f0.50 | 6 934 | 4,6 / 8,5 ms | 24 symboles | 4,4 ms | 4,2 ms | 1,3 Mo |
| mem-k1 | 14 299 | 15,0 / 16,9 ms | 52 symboles | 17,3 ms | 15,6 ms | 3,3 Mo |
| mem-k2 (`run3`) | 28 598 | 38,4 / 45,1 ms | 52 symboles | 40,6 ms | 43,7 ms | — |
| mem-k3 (`run3`) | 42 897 | 68,5 / 81,6 ms | 52 symboles | 70,7 ms | 69,6 ms | — |

Répartition JFR (racine la plus large, `maxResults` 10) : index des arêtes entrantes 64 à 77 % (`mem-k1` sur les deux exécutions, `mem-k2`, `mem-k3`), limitations de base 9 à 15 %, corps d'`analyze` (table des symboles, file de priorité, tri final) 14 à 26 %, construction des chemins 0,0 %. Le coût fixe croît linéairement avec le snapshot (15 → 38 → 68 ms pour 1, 2, 3 répliques) ; la portée, elle, ne bouge pas. Le graphe issu de scip-java est clairsemé (0,36 relation par symbole) ; la portée maximale observée est de 52 symboles à profondeur 32. La traversée est dans le bruit de mesure : **tout le coût est la préparation, refaite à chaque appel**, et elle croît avec la taille du snapshot, pas avec `maxResults`.

### A6.3.6 `LocalProjectArchitectureQuery` (renvoi d'A4, § A4.8)

Corpus entier (`mem-k1`, magasin en mémoire, snapshot chargé : `snapshotLoadActive` ≈ 0 ms), 1 + 5 itérations :

| Mesure | Médiane | p95 |
|---|---:|---:|
| Découverte seule (`ProjectDiscoveryService.discover`, copie du dépôt sous `%TEMP%`) | 36 292 ms | 38 153 ms |
| Découverte seule du worktree (`N:`, arborescence comparable, avec `fixtures/` et `minos-intellij/`) | 12 693 ms | 13 861 ms |
| `getArchitectureOverview` | 40 154 ms | 41 068 ms |
| `getArchitectureIntelligence` (découverte + 5 analyses) | 37 781 ms | 40 986 ms |

Part de la découverte dans l'intelligence complète : **96 %** en médiane. JFR sur un appel : 3 250 échantillons natifs, tous dans la découverte (99,6 % dans le parcours des racines de modules, `discoverModuleRoots`), contre 257 échantillons Java dont 68 % dans les cinq analyses, soit de l'ordre de 0,5 s de calcul sur 38 s. La découverte est un parcours de fichiers limité par les entrées-sorties (2,9 fois plus lent sous `%TEMP%` que dans le worktree sur `N:`, pour une arborescence comparable ; cause non instruite : disque ou antivirus) ; les analyses pèsent de l'ordre de 1 %.

## A6.4 Verdicts et recommandations

| # | Constat de l'audit | Verdict | Chiffres | Recommandation |
|---|---|---|---|---|
| 1 | Plafond persisté (256 Mio) < limite SCIP (512 Mio) : échec tardif | **CONFIRMÉ, et plus grave** | Le seuil réel est un SCIP de 18,9 à 29,7 Mo selon le corpus (ratio 9,0 à 14,2), pas 256 ou 512 Mio ; **ce dépôt ne peut pas s'indexer** (338 Mo V2) ; l'échec arrive après 1,4 s d'ingestion et 2,4 s d'écriture inutile (63 % du temps) | **Faire, sous une autre forme que l'audit** : aucun critère fiable n'existe avant le décodage (ratio de 9,0 à 14,2 selon le corpus ; les occurrences de la pré-analyse sont un majorant des occurrences persistées, pas un minorant, l'ingestion pouvant en écarter ; un minorant par occurrence tiré des tailles minimales du codec, environ 200 octets, reste 5 à 10 fois sous l'observé (1 062 à 1 972 octets) et ne refuserait rien d'utile). Le seul contrôle exact et précoce est le calcul de la taille encodée à partir du snapshot ingéré, **avant toute écriture**, avec un message `PublicErrorMessages` clair (taille, plafond, sans chemin) ; il économise l'écriture de 256 Mio, pas l'ingestion. Le vrai levier est le point 2. |
| 2 | Chaînes en UTF-16 : taille doublée | **CONFIRMÉ sur disque, INFIRMÉ en mémoire** | Chaînes = 97 % du fichier ; UTF-8 = 0,55× sur les sept corpus ; tas inchangé (chaînes compactes) | **Faire** (V3, lecture V1/V2 conservée) : ce dépôt redevient indexable (186 Mo) et le seuil SCIP passe à ~34 Mo. Contraintes de verif-archi intégrées (§ A6.7). Ne pas présenter la baisse du poids estimé du cache comme un gain mémoire. La table de chaînes (0,19×) est un gain bien supérieur mais un format plus profond : à instruire dans l'ADR du point 5. |
| 3 | `maxResults` appliqué après une traversée complète | **INFIRMÉ en pratique** (vrai dans le code, sans coût mesurable) | Traversée ≈ 0 ; préparation = 100 % (15 ms sur le corpus entier), indépendante de `maxResults` ; portée ≤ 52 symboles | **Ne pas faire** : l'arrêt anticipé ne gagnerait rien de mesurable et changerait le rapport (limitations et marques de test posées pendant la traversée, § A6.7). Le coût réel est la reconstruction de l'index des arêtes entrantes à chaque appel : hors périmètre, § A6.8. |
| 4 | `HybridSearchService` re-normalise tout le corpus à chaque requête | **CONFIRMÉ** (masqué dans le chemin produit au-delà de 64 Mio) | Snapshot en mémoire : normalisation 81,5 % d'une requête de 147–161 ms sur 81 095 documents, 63–65 Mo alloués ; chemin produit au-delà de 64 Mio : 2,7–3,0 %, le rechargement du snapshot fait 96–97 % | **Faire** : cache du contenu normalisé dans `CachedCorpus`, classement identique par construction (même fonction, même contenu), test d'équivalence scores et ordre, égalités exactes comprises ; pas d'index inversé (le bonus de phrase porte sur une sous-chaîne brute, § A6.7). Gain attendu ≈ 80 % du temps de requête quand le snapshot est en cache ou en mémoire ; quasi nul tant que le cache de vues refuse les gros snapshots, et sans objet au-delà d'environ 158 000 documents, où le corpus lui-même n'est plus mis en cache (§ A6.3.4, § A6.8). |
| 5 | Snapshots chargés entièrement en mémoire, cache de 512 Mo | **CONFIRMÉ, avec une cause dominante différente** | Vue = 1,4× le fichier ; cache inopérant au-delà de 64 Mio persistés (estimation 5,7× trop forte) ; chaque requête relit alors tout (0,75–1,46 s, deux fois pour l'hybride) | **ADR (Proposed)** pour le snapshot paginé ou mappé et la table de chaînes, après le point 2. Avant toute refonte, deux corrections mesurables et bornées relèvent d'un autre lot (§ A6.8) : l'estimation du poids des vues (8× → mesurée) et le double chargement de `SemanticIndexService.status`. |
| A4.8 | `LocalProjectArchitectureQuery` : chaque appel refait découverte et analyses | **CONFIRMÉ, mais la découverte fait tout** | Découverte 96 % (36,3 s sur 37,8 s ; 12,7 s sur `N:`), analyses de l'ordre de 1 % (≈ 0,5 s) | **Ne pas mémoïser les analyses** (gain ≤ 1–2 %) : la décision d'A4 (pas de cache) est confirmée par la mesure. Le coût est le parcours de découverte, non prouvable en cache (arborescence vivante) : sa performance relève d'un autre lot (§ A6.8). |

## A6.5 Points soumis à arbitrage (jalon 1)

1. **Point 1** : remplacer « refus précoce d'après la taille du SCIP » par « contrôle exact de la taille encodée avant écriture » (aucun critère fiable avant décodage) ; le faire après le point 2, qui déplace le seuil.
2. **Point 2** : format V3 UTF-8 (pointeur actif compris, PostgreSQL compris), lecture V1/V2 conservée ; test d'ouverture d'un snapshot V2 écrit par le code d'avant.
3. **Point 3** : ne pas faire.
4. **Point 4** : cache du contenu normalisé dans `CachedCorpus` (pas d'index inversé), avec test d'équivalence ; à faire après la correction du poids des vues si l'on veut que le gain atteigne le chemin produit des gros projets.
5. **Point 5** : ADR `Proposed` (snapshot paginé ou mappé, table de chaînes), sans implémentation.
6. **Hors périmètre, à ouvrir ailleurs** (§ A6.8) : estimation du poids des vues du cache, double chargement par `SemanticIndexService.status`, relecture des sources par extrait, index d'impact reconstruit à chaque appel, `minos-app` qui construit dans le `target/` racine.

## A6.6 Journal

- 2026-09-29 — impl-archi, jalon 1 : worktree `a6-scalabilite` sur `d9ae1005`. Tentative `minos index` sur une copie du dépôt : refusée par le bac à sable AppContainer (`pwsh.exe` sous `Program Files`). Runner scip-java géré appelé directement : premier index à 39 documents (le `clean` de `minos-app` efface l'agrégat de scip-java), puis 883 documents après correctif de la copie. Procédure scriptée (`prepare-corpus.ps1`) et rejouée : 23 816 951 octets (±1).
- 2026-09-29 — impl-archi : `run1` (ratio, `file-f*`, impact `mem-k1`) ; mesures hybrides `mem-k1` de `run1` écartées : `SemanticIndexService.status` y rechargeait la tranche `file-f0.50` restée active dans le magasin fichier (constat du § A6.3.4). Le harnais publie désormais un snapshot vide avant les jeux en mémoire. `run2` (jeux `mem-k*`, architecture) : complet pour `mem-k1` ; arrêté sur `mem-k2` après la première recherche hybride (corpus refusé par le cache, chaque requête le reconstruit en ~17 min). `run3` : impact et index sur `mem-k2` et `mem-k3` (73 s). La configuration par défaut du banc garde `mem-k1` seul ; `README` du banc pour le complément.
- 2026-09-29 — impl-archi : `fb363253` (test) — harnais, lanceur, préparation du corpus, README ; `./mvnw -q -pl minos-bootstrap -am test` vert, le banc absent des rapports Surefire ; gates `check-module-boundaries`, `check-milestone-artifact-references`, `check-current-docs` verts. Commit de docs suivant : ce suivi. Aucune ligne de production modifiée.
- 2026-09-29 — impl-archi, étape 1 (tests d'abord) : `PersistedSnapshotLimitTest` exécuté sur la base : **2 tests sur 2 rouges**. Le premier écrit 256 Mio en 2,3 s puis échoue sur `knowledge snapshot exceeds byte limit: 268443632/268435456`. Le second, dont le répertoire du projet est occupé par un fichier ordinaire, échoue sur `private storage path is not a directory: C:\Users\…` : une écriture était tentée avant tout refus, et ce message, qui porte un chemin absolu, aurait été remplacé par un repli générique à la frontière publique.
- 2026-09-29 — impl-archi, étape 1 : commit `perf(archi)` (§ A6.10) ; `./mvnw -q -pl minos-storage-postgresql -am test -Dminos.postgresql.tests.required=true` vert (PostgreSQL sous Docker) ; gates `remediation/*`, `check-module-boundaries`, `check-milestone-artifact-references`, `check-current-docs` verts.
- 2026-09-29 — impl-archi : V-A6-01 à 04 (commit `test(archi)`) : Javadoc du banc en français, prérequis du README, requête d'import du banc calquée sur `import-scip`, constante publique au lieu de la réflexion, taille V2 par `encodedSize` ; remesure des ratios (§ A6.3.1).
- 2026-09-29 — impl-archi, étape 2 : `a68c560e` (V3). Au premier essai, `A2SurfaceCharacterizationTest.retentionEffectIsUnchanged` était rouge : les noms de fichier de `retention.golden` finissent par le sha256 du contenu. Arrêt et signalement ; arbitrage : voie 1 sous preuve stricte (§ A6.11). `ebdc6969` : golden seul, plus la preuve `RetentionFormatEquivalenceTest`. `095c4c60` : banc (taille V3 du vrai encodeur, publication décidée par le magasin). Fixtures V1/V2 : `git add` refusé sous Windows (« Filename too long ») pour les noms d'origine ; ils ont été remplacés par `snapshot.<extension>`, le test reconstitue le nom d'origine, octets inchangés. Tests : `-pl minos-storage-postgresql -am test -Dminos.postgresql.tests.required=true` vert ; tests A2 (12 + 5) et `RetentionFormatEquivalenceTest` verts ; gates verts.
- 2026-09-29 — impl-archi, étape 3 : `ea5132b6` (docs), verdict « infirmé en pratique » (§ A6.12).
- 2026-09-29 — impl-archi, étape 4 : `1c54a3a4`, référence du classement écrite par le `minos-application` de `d9ae1005` (identique à la base, `git diff --quiet`) et test vert sur ce commit ; `bba7a91b`, cache du contenu normalisé, avec la même référence verte à l'octet ; `e32bd9f6`, suggestion M3 de verif. `-pl minos-bootstrap -am test` vert, gates verts.
- 2026-09-29 — impl-archi, étape 5 : ADR 0047 (`Proposed`), sans implémentation (§ A6.14). Constats V-A6-05 à 08 consignés (§ A6.7) ; ADR 0046 complété (retour arrière, asymétrie au ré-import, « snapshot de connaissance », commit volontairement rouge).
- 2026-09-29 — impl-archi, fin de lot (tête `a3aa4baf`) : `./mvnw -B clean verify -Dminos.postgresql.tests.required=true` **vert**, 564 s, **1409 tests, 0 échec, 0 erreur, 46 ignorés** (PostgreSQL sous Docker). `check-jacoco.py` : seul `m24-polyglot-provider-platform` est rouge (préexistant sous Windows : `ManagedPolyglotScipRuntimeManager`) ; `persistence-cache-indexes` et `semantic-hybrid-retrieval` passent. 15 gates Python verts. `git diff --stat d9ae1005 -- minos-app/src/test/resources/characterization` : `retention.golden` seul, 3 lignes.

## A6.7 Constats verif-archi

| # | Commit | Constat | Gravité | Suite |
|---|---|---|---|---|
| V-A6-01 | `fb363253` | `SnapshotScalabilityBenchmark` mélangeait les langues de Javadoc (classe en français, membres en anglais). | à corriger | **Corrigé** : Javadoc et commentaires en français, sans effet sur le comportement. |
| V-A6-02 | `fb363253` | Rejeu Windows seulement (`pwsh`, `mvnw.cmd`, `Get-CimInstance`, runner Windows) et `-Xmx24g` imposé. | remarque | **Documenté** : section « Prérequis » du README du banc (Windows, PowerShell 7, JDK 24, mémoire mesurée et conseillée) ; aucun correctif de code. |
| V-A6-03 | `d491519d` | L'écart de 3 à 6 % avec les ratios de verif vient de l'`Origin` recopiée sur chaque entité (`indexRunId` et `providerVersion`) ; le produit écrit un `indexRunId` de 41 caractères et une version, donc un ratio plus haut et un seuil SCIP plus bas que 18,9 Mo. Calculer le plafond sur l'`Origin` réelle. | remarque | **Intégré** : requête d'import du banc calquée sur `import-scip` (`indexRunId`, `providerVersion`) ; remesure au § A6.3.1 (ratio 14,91, seuil 18,0 Mo) ; le refus de l'étape 1 porte sur le snapshot réellement ingéré. |
| V-A6-04 | `fb363253` | Le banc lisait `MAX_PERSISTED_SNAPSHOT_BYTES` par réflexion, et son recensement n'était vérifié que contre V2 ; après V3, les tailles devront venir du vrai encodeur. | remarque | **Corrigé** : le banc lit `SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES` (public depuis l'étape 1) ; la taille V2 vient de `SnapshotCodecV2.encodedSize` (sans plafond) ; le recensement ne sert plus qu'à la composition et aux projections, les tailles de chaque format seront rapportées par leur vrai encodeur. |
| — | contrôles | Contrôles `fb363253`/`d491519d` : banc absent de tous les rapports Surefire ; clean verify 1383/0/0/46 ; JaCoCo seulement m24 préexistant ; 23 gates verts, 12 golden identiques, 7 témoins rouges ; helpers 63/31/8 ; aucune donnée volumineuse ; suivi A3/A4 intact (+170/−0). Tas rejoué par verif : vue 1,50–1,52× V2, snapshot relu 1,23–1,25× (ariane, nexus), cohérent avec 1,39–1,40× et 1,15× du suivi ; ×8 reste 5,3 à 5,7 fois trop fort. | information | — |
| V-A6-05 | `a68c560e` | Pris seul, ce commit est rouge (`retention.golden`) : c'est la conséquence du commit séparé imposé par l'arbitrage. | décision de l'orchestrateur | **Consigné** : pas de réécriture d'historique ; ADR 0046 § Conséquences (« pour un `git bisect`, traiter la paire `a68c560e` / `ebdc6969` comme une seule étape ») ; annoncé en tête de PR. |
| V-A6-06 | `a68c560e` | Retour arrière mesuré par verif : un ancien binaire refuse proprement la lecture et la compaction (« unsupported active snapshot pointer version: 3 »), ne modifie aucun fichier, et se rétablit en republiant un import (pointeur v2 réécrit). | remarque | **Documenté** dans l'ADR 0046 § Conséquences. |
| V-A6-07 | `a68c560e` | Magasin fichier : un ré-import du même `snapshotId` laisse le V2 de même préfixe comme historique, qui occupe un emplacement de rétention jusqu'à la compaction ; PostgreSQL conserve le V2. | remarque | **Documenté** dans l'ADR 0046 (asymétrie), non corrigé. |
| V-A6-08 | `a68c560e` | `FileSymbolSnapshotStore.publish(UUID, String, Collection<Symbol>)` écrit toujours en V1 UTF-16 ; l'ADR doit dire « un nouveau snapshot de connaissance ». | remarque | **Corrigé** dans l'ADR 0046. La méthode n'a aucun appelant de production (vérifié : `ScipSymbolSnapshotImporter` et `ScipProjectSnapshotLifecycle` publient avec occurrences et relations) ; renvoyée au § A6.8. |
| M3 | `a68c560e` | Suggestion : un aller-retour positif d'une chaîne non ASCII de plus de 8 Mio et d'au plus 8 Mi caractères, pour que la mutation « borne en caractères » soit prise par un test positif. | suggestion | **Fait** : `e32bd9f6` (8 Mi « é », 16 Mio en UTF-8, restitués exactement). |
| — | contrôles étape 1 | Étape 1 vérifiée (`e23b5ded`, `1acb68e8`) : octets V1/V2 identiques après réécriture ; frontière exacte ; aucun effet de bord au refus ; A/B rejoué avant 3 492/3 211 ms et ≈256 Mio écrits, après 1 777/1 738 ms et 0 octet ; refus pendant l'ingestion écarté à raison. | information | — |
| — | contrôles étape 2 | Étape 2 vérifiée (`a68c560e`…`5f0c2d53`) : golden masqué identique ; fixtures régénérées 8/8 identiques ; fixtures piégées de verif exactes ; mutations rouges ; anti-bombe OK ; PostgreSQL requis vert ; import de ce dépôt publié à 194 552 030 o ; lecture froide ariane 128–133 ms contre 314–328 ms ; tas identique ; clean verify 1407/0/0/46. | information | — |
| V-A6-00 | (avant le jalon 1) | Mesures indépendantes de verif (ratio 12,45 / 10,79 / 8,51, UTF-8 ≈ 0,55×, lecture froide d'ariane 452 ms) et huit pièges pour la suite : pointeur actif encodé avec les mêmes `writeString`/`readString` (l'encodage doit dépendre de la version, pointeur compris) ; PostgreSQL décode toujours en V2 sans colonne de version, plafond dupliqué ; ré-import du même SCIP après changement de format (« already exists with different content ») ; `logicalIdHash` et `listSnapshotFiles` à ne pas changer ; poids du cache (8×) qui baisserait sans gain de tas ; surrogates isolés (V2 les conserve, `getBytes(UTF_8)` les remplace par `?`) ; hybride : bonus de phrase sur sous-chaîne brute et égalités de score ; impact : limitations et marque de test posées pendant la traversée. | information | Intégré : recoupement au § A6.3.1 (écarts de 3 à 6 % expliqués par `indexRunId`), poids du cache présenté comme poids estimé et non comme mémoire (§ A6.3.3), pièges reportés dans les recommandations (§ A6.4). |

## A6.8 À traiter plus tard (hors périmètre)

| Origine | Description | Renvoi |
|---|---|---|
| impl-archi (jalon 1) | Estimation du poids des vues de requête : `QUERY_VIEW_PERSISTED_AMPLIFICATION = 8` (fichier et PostgreSQL) contre 1,4 mesuré ; toute vue d'un snapshot de plus de 64 Mio persistés est refusée par le cache et chaque requête relit tout le snapshot. Correction bornée, mesurable, sans changement de résultat. | A6 suite ou lot perf |
| impl-archi (jalon 1) | Cache de corpus hybride : poids estimé 2,5 fois trop fort (2 octets par caractère contre des chaînes compactes) ; au-delà d'environ 158 000 documents le corpus n'est plus mis en cache et chaque requête le reconstruit (17 min à 161 307 documents). | lot perf |
| impl-archi (jalon 1) | `SemanticIndexService.status` appelle `loadActiveKnowledge` même fournisseur désactivé : une recherche hybride charge le snapshot deux fois. | lot perf |
| impl-archi (jalon 1) | `LocalSourceReader` relit le fichier entier pour chaque extrait (≈ 6 ms par document de corpus hybride, 515 s pour ce dépôt) ; le commentaire de `SemanticDocumentFactory` (« a file is not reread once per symbol ») est devenu faux. | lot perf, docs |
| impl-archi (jalon 1) | `ImpactAnalysisService` reconstruit la table des symboles et l'index trié des arêtes entrantes à chaque appel (100 % du coût mesuré). | lot perf |
| impl-archi (jalon 1) | `minos-app` construit dans le `target/` racine : sur un dépôt organisé ainsi, scip-java perd tous les modules sauf le dernier (l'agrégat `target/scip-targetroot` est effacé par le `clean` du module). Vaut pour ce dépôt, et pour tout projet utilisateur de même forme. | provider scip-java |
| impl-archi (jalon 1) | Découverte de projet (`ProjectDiscoveryService.discover`) : 12,7 s sur ce worktree, 36 s sous `%TEMP%`, dont 99,6 % des échantillons natifs dans `discoverModuleRoots` ; elle fait 96 % de chaque requête d'architecture (§ A6.3.6). Profil du parcours (répertoires `target/`, détecteurs par répertoire, règles d'ignorance) à instruire. | lot perf |
| verif-archi (V-A6-08) | `FileSymbolSnapshotStore.publish(UUID, String, Collection<Symbol>)` (publication « symboles seuls », V1) n'a aucun appelant de production ; elle écrit encore en UTF-16. À retirer du port ou à justifier. | hygiène stockage |
| impl-archi (jalon 1) | `minos index` impossible sur un poste où PowerShell 7 est sous `Program Files` sans élévation (bac à sable AppContainer). | A1 / D1 |

## A6.9 Arbitrages de l'orchestrateur (29 septembre 2026)

| # | Point | Décision |
|---|---|---|
| 1 | Plafonds | **À faire, en premier.** Évaluer d'abord un refus pendant l'ingestion (borne inférieure monotone et exacte) ; sinon taille encodée exacte après ingestion, refus avant toute écriture, message `PublicErrorMessages`, fichier et PostgreSQL. Tests : rouge d'abord, frontière au plafond, taille calculée = taille écrite pour tout format. |
| 2 | Format V3 UTF-8 | **À faire**, contrôlé en bloquant par verif : lecture V1/V2 conservée (fixtures écrites à `d9ae1005`, fichier et PostgreSQL), repli V2 pour les surrogates isolés, décodage strict borné en octets, pointeur actif compatible, payload PostgreSQL auto-décrit, ré-import du même SCIP sans « different content », `logicalIdHash`, noms, `listSnapshotFiles` et compaction inchangés ; ADR. |
| 3 | Traversée d'impact | **Ne pas faire** (infirmé en pratique). |
| 4 | Normalisation hybride | **À faire** : cache du contenu normalisé dans `CachedCorpus`, pas d'index inversé, test d'équivalence ; arrêt si le classement bouge. |
| 5 | Snapshot paginé ou mappé | **ADR `Proposed`**, sans implémentation. |
| — | § A6.8 | Hors lot, remonté à l'utilisateur. |

## A6.10 Étape 1 — plafond : taille exacte, refus avant toute écriture

**Refus pendant l'ingestion : évalué, écarté.**

- La taille encodée est une propriété du codec du magasin : fichier V1/V2, PostgreSQL, bientôt V3 avec un repli V2 décidé sur le snapshot entier. L'importeur SCIP (`minos-provider-scip`) ne connaît que le port `CodeKnowledgeSnapshotStore`. Un adaptateur ne dépend pas d'un autre (`check-module-boundaries`) : il faudrait un nouveau port.
- Le magasin de capture de l'importeur indexe les entités par identifiant et remplace une entité ré-émise. La somme courante n'est donc pas monotone sans suivre ces remplacements.
- Le gain serait borné par l'ingestion elle-même. Or le travail perdu mesuré est surtout l'écriture : 2,1 à 3,1 s d'écriture inutile, contre 1,2 à 1,6 s pour tout l'import sans persistance (gel, sha256 et décodage compris).

**Aucun critère n'est possible avant le décodage.** Le ratio V2/SCIP va de 9,0 à 14,2 selon le corpus (§ A6.3.1). Les comptes de la pré-analyse majorent les entités persistées au lieu de les minorer, l'ingestion pouvant en écarter. Tout refus avant l'ingestion pourrait rejeter un snapshot qui tient. Le Javadoc de `SnapshotCodec.requirePersistable` le dit.

**Réalisation.**

- `SnapshotBinaryCodecSupport` : les écrivains parcourent le snapshot contre un `EncodingSink`, flux réel (`StreamSink`) ou compteur (`CountingSink`). La taille annoncée et la taille écrite viennent du même parcours et ne peuvent pas diverger ; le compteur applique aussi le contrôle de longueur de chaîne de l'écrivain. Nouvelles méthodes : `encodedSymbolSnapshotV1Size`, `encodedKnowledgeSnapshotV2Size`, `requirePersistable(long)`.
- `SnapshotCodec` : `encodedSize`, `requirePersistable` (défaut) et `MAX_PERSISTED_SNAPSHOT_BYTES`, qui reprend la constante de `SnapshotBinaryCodecSupport` (littéral exigé par `check-mne.py`, inchangé). `PostgresCodeKnowledgeSnapshotStore` n'a plus de copie du plafond : il reprend `SnapshotCodec.MAX_PERSISTED_SNAPSHOT_BYTES`.
- `FileSymbolSnapshotStore.publishSnapshot` et `PostgresCodeKnowledgeSnapshotStore.publishSnapshot` appellent `requirePersistable` **avant** de créer le fichier temporaire ou le fichier de travail, puis vérifient que la taille écrite est la taille annoncée.
- Message : `knowledge snapshot is too large to persist: <n> encoded bytes exceed the 268435456-byte limit (256 MiB); nothing was written`. Il ne porte que des tailles et passe `PublicErrorMessages.sanitize` inchangé (testé).

**Tests.**

- `PersistedSnapshotLimitTest` (3 tests) :
  - un snapshot trop grand ne laisse aucun fichier ajouté ni modifié, et le snapshot actif reste inchangé ;
  - le refus est décidé avant toute E/S : le répertoire du projet est occupé par un fichier ordinaire, et seul un refus décidé avant l'I/O passe ;
  - frontière : un snapshot d'exactement 268 435 456 octets est publié (fichier de 268 435 456 octets), un octet de plus est refusé.
- `SnapshotEncodedSizeTest` (4 tests) : taille annoncée = taille écrite en V1 et V2, sur un snapshot qui passe par chaque branche des codecs (`CodecFixtures`), un grand snapshot et un snapshot vide. `encodeToBytes` concorde ; une chaîne trop longue est refusée de la même façon.
- `PostgresCodeKnowledgeSnapshotStoreTest.oversizedSnapshotIsRefusedBeforeAnyScratchFileOrRow` : aucun fichier de travail, aucune ligne insérée, snapshot actif inchangé, message identique.
- Les snapshots de plus de 256 Mio partagent une seule chaîne de 8 millions de caractères (`PersistedSizeFixtures`) : ils ne coûtent que quelques mégaoctets de tas.

**Mesures avant/après.** Pour « avant », le `minos-storage-local` de `d9ae1005` est recompilé et placé en tête du classpath du banc. 7 itérations ; deux paires A/B en ordre inversé, car l'écart d'une exécution à l'autre dépasse 30 %.

| Mesure (médiane, paire 1 / paire 2) | Avant | Après |
|---|---:|---:|
| Import de ce dépôt (refusé) | 4 734 / 3 372 ms | **2 274 / 1 772 ms** |
| dont temps après l'ingestion (import − import sans persistance) | 3 117 / 2 138 ms, 256 Mio écrits puis effacés | **660 / 379 ms, 0 octet écrit** |
| Import publié, ariane-chatbot (31,8 Mo) | 552 / 420 ms | 398 / 408 ms |
| Import publié, nexus-context-engine (26,7 Mo) | 429 / 313 ms | 324 / 306 ms |
| Publication de la tranche `file-f0.50` (162 Mo) | 1 935 / 1 375 ms | 1 400 / 1 294 ms |

- Le passage de comptage n'a pas de coût mesurable sur les imports qui passent : les écarts restent dans le bruit.
- Le refus de ce dépôt arrive 1,6 à 2,5 s plus tôt et n'écrit plus rien. Les 379 à 660 ms restants sont le tri et le contrôle d'unicité du magasin, plus le comptage.
- La taille est calculée sur le snapshot réellement ingéré, donc sur l'`Origin` du produit (`indexRunId`, `providerVersion`), jamais sur une estimation (V-A6-03).

## A6.11 Étape 2 — format V3 UTF-8

Décision : [ADR 0046](../adr/0046-format-de-snapshot-v3-chaines-utf8.md).

**Réalisation** (`a68c560e`).

- `SnapshotCodecV3` : même mise en page que V2, version 3, chaînes UTF-8 préfixées par leur longueur en octets.
  - Écriture exacte : une chaîne avec un surrogate isolé est refusée, jamais remplacée par `?`.
  - Lecture stricte (`CodingErrorAction.REPORT`), bornée à 3 × 8 Mi octets par chaîne avant toute lecture, par `readNBytes` sans pré-allocation.
- `SnapshotBinaryCodecSupport` : les lecteurs passent par un `SnapshotInput` qui décode les chaînes selon le format ; les écrivains, par un `EncodingSink` paramétré par l'encodage. V1, V2 et V3 partagent ainsi un seul parcours.
- `KnowledgeSnapshotCodecs` : politique commune au magasin fichier et à PostgreSQL.
  - `select` : V3 par défaut ; V2 pour le snapshot entier dès qu'une chaîne contient un surrogate isolé ; taille exacte et refus au plafond calculés avant toute E/S.
  - `forVersion` et `read` : lecture d'un payload dans le format que déclare son en-tête.
- `ActiveSnapshotRepository` : version 3 acceptée. La mise en page du pointeur est inchangée depuis V2, donc les pointeurs V1/V2 gardent leurs octets.
- PostgreSQL :
  - `PostgresSnapshotPayloadCodec` passe par `KnowledgeSnapshotCodecs` ;
  - le descripteur porte la version lue dans l'en-tête, et non plus un 2 écrit en dur ;
  - ré-import sous le même identifiant avec d'autres octets : les deux payloads sont décodés et comparés comme contenus ; un V2 identique est conservé, un contenu différent reste refusé.
- Inchangés : `logicalIdHash`, le schéma des noms, `listSnapshotFiles`, la compaction, le calcul d'intégrité (sha256 des octets), `CodeKnowledgeSnapshotBinaryCodec` (pont V2).

**Fixtures de la base.**

- `LegacySnapshotFixtureGenerator` et `LegacySnapshotContent` (sources de test, autonomes) se compilent contre le `minos-storage-local` de `d9ae1005`, compilé depuis `git archive d9ae1005`. `minos-domain` et `minos-engine` sont identiques à `d9ae1005` (`git diff --quiet d9ae1005 -- minos-domain minos-engine`).
- Trois magasins : `v1-symbols`, `v2-well-formed`, `v2-lone-surrogate`. Chaînes piégées : accents, emoji (paire), CJK, NUL, U+FFFF, et un surrogate isolé dans la troisième variante.
- L'écriture est déterministe : deux générations donnent des octets identiques (`diff -r`).
- sha256 des fichiers de snapshot :
  - `v1` : `ae66cfbfd0c3653a71dc8aca085806c7b673dfdee35ef150dc7877c8bd6912b2`
  - `v2-well-formed` : `f29cf835e97ba31a88f1149db4c7b153306edc8ec06364c7855c25f64398aae3`
  - `v2-lone-surrogate` : `49e434c3d070e9ea19cac719eb4378732cceab629af3baf926ad87e4120212a4`
- sha256 des pointeurs :
  - `v1` : `c369dea7…`
  - `v2-well-formed` : `af0c2ea7…`
  - `v2-lone-surrogate` : `99af981d…`
- Stockés sous `src/test/resources/snapshot-formats/base-d9ae1005/` avec un nom court (limite de chemin de Git sous Windows), marqués `binary` dans `.gitattributes`. Les deux payloads V2 sont recopiés pour PostgreSQL.

**Tests.**

- `SnapshotFormatCompatibilityTest` :
  - les stores V1 et V2 de la base s'ouvrent, leurs octets restent intacts et le descripteur porte la version 1 ou 2 ;
  - un home V2 existant s'ouvre, puis un nouvel import est promu en V3 : pointeur 3, en-tête 3, checksum cohérent ; `listSnapshotFiles` voit les deux fichiers et la compaction garde le V2 comme historique valide ;
  - le ré-import du même snapshot après le changement de format passe, avec des noms issus du même `logicalIdHash` ;
  - un snapshot avec surrogate isolé est republié en V2, exact.
- `SnapshotCodecV3Test` :
  - aller-retour de toutes les chaînes piégées ;
  - taille ASCII ≈ moitié de V2 ;
  - surrogate isolé : `encodedSizeIfEncodable` vide, écriture V3 refusée, `select` → V2 ;
  - UTF-8 mal formé (CESU-8, suite invalide) signalé ;
  - bornes en octets : longueur excessive refusée, longueur au-delà du fichier → « truncated », sans allocation de 24 Mo ;
  - chaque codec refuse la version de l'autre.
- `SnapshotEncodedSizeTest` : taille V3 annoncée = taille écrite.
- `PersistedSnapshotLimitTest` : frontière du plafond mesurée en V3 ; un snapshot qui tient en V3 mais que son surrogate isolé force en V2 est refusé sur sa taille V2.
- `PostgresSnapshotFormatCompatibilityTest` :
  - payload V2 de la base inséré tel quel : lu (descripteur 2) ;
  - ré-import du même snapshot : le payload V2 est conservé octet pour octet ;
  - contenu différent sous le même identifiant : toujours refusé ;
  - nouvel import : payload V3 ;
  - payload avec surrogate isolé : lu exactement et republié en V2.

**Exception `retention.golden`** (arbitrage : voie 1 sous preuve stricte ; seul golden modifié du chantier, commit `ebdc6969` qui ne contient que ce fichier et sa preuve).

- **Cause.** Le golden liste les noms `snapshot-<logicalIdHash>-<sha256 du contenu>.knowledge`. V3 change les octets, donc le suffixe.
- **Preuve mécanique.** Dans l'ancien golden (`d9ae1005`) et le nouveau, on remplace chaque `-<64 hex>.knowledge` par `-<SHA>.knowledge`. Résultat :
  - textes identiques octet pour octet, 4 312 octets chacun ;
  - 8 noms de chaque côté ;
  - seules les lignes 11, 14 et 17 diffèrent, et seulement par ces suffixes ;
  - préfixes `logicalIdHash`, comptes de suppression (1 puis 2), snapshot actif (`a2-snapshot-4`) et ordre des lignes inchangés.
- **Même contenu logique.** `RetentionFormatEquivalenceTest` rejoue l'import du scénario : même fixture, identité de projet figée, `indexRunId = "application-" + snapshotId`, `scip-typescript` 0.4.0. Pour chacun des 4 snapshots :
  - le fichier V3 porte le sha du nouveau golden ;
  - son modèle décodé, réencodé en V2, redonne à l'octet près le sha que `d9ae1005` avait écrit (ancien golden).

  Le V2 étant déterministe, le modèle V3 est exactement celui que la base avait écrit.

  | Snapshot | sha V2 écrit par la base | sha V3 |
  |---|---|---|
  | a2-snapshot-1 | `ae70a8e4…00ea` | `65c5b788…003a` |
  | a2-snapshot-2 | `0aa1a3f1…0ce87d` | `cdc627e4…ab8f239c` |
  | a2-snapshot-3 | `cf9fefcb…effcd00ec` | `43f45d5f…d9e65da6b30` |
  | a2-snapshot-4 | `97f23527…5c9ff5c` | `57ba81ec…3f9c88839` |

- **Autres golden.** Les 11 autres golden et `A2CompositionCharacterizationTest` sont verts et inchangés (`git diff --stat d9ae1005 -- minos-app/src/test/resources/characterization` : `retention.golden` seul, 3 lignes).
- **Documentation.** ADR 0046 § Conséquences. `docs/user/` n'a pas de section qui décrive le stockage ou la rétention des snapshots locaux : rien à y ajouter.

**Mesures avant/après.** Banc : `minos-storage-local` de `d9ae1005` en tête du classpath pour « avant », deux paires A/B en ordre inversé. Tailles données par le vrai encodeur (V-A6-04).

| Mesure | V2 (avant) | V3 (après) |
|---|---:|---:|
| Import de ce dépôt | refusé (355 045 885 octets) | **publié, 194 552 030 octets** (0,548×) |
| ariane-chatbot / nexus-context-engine | 33 157 453 / 27 515 826 | 18 198 069 / 15 120 767 (0,549 / 0,550) |
| Tranche `file-f0.50` sur disque | 170 196 551 | 93 266 764 (0,548×) |
| Lecture à froid de `file-f0.50` (médiane, deux paires) | 1 494 / 1 728 ms | **614 / 597 ms** |
| Tas de `file-f0.50` : vue / snapshot seul | 238,7 / 199,2 Mo | 237,9 / 198,3 Mo |
| Publication de `file-f0.50` | 1 394 / 1 283 ms | 1 098 / 1 035 ms |
| Import publié : ariane / nexus | 354 / 288 ms | 305 / 296 ms |
| Dépôt entier en V3 : publication, lecture à froid, construction des index | — | 2 209 ms, 1 655 ms, 395 ms |
| Dépôt entier en V3 : tas vue / snapshot seul | — | 494,7 / 413,2 Mo, non mis en cache |

- **Aucun gain mémoire.** Le tas réel est identique en V2 et en V3. Le poids **estimé** par le cache de vues (8 fois la taille du fichier) baisse de 45 %, et le cache admet donc des snapshots un peu plus gros ; le tas, lui, ne bouge pas.
- Le dépôt entier (vue de 495 Mo en tas, poids estimé 1,56 Go) n'est toujours pas mis en cache ; § A6.8.
- Seuil SCIP du plafond pour ce corpus, avec l'`Origin` du produit : de 18,0 à 32,9 Mo.

## A6.12 Étape 3 — traversée d'impact : non faite (infirmé en pratique)

Arbitrage : **ne pas faire**. Le constat de l'audit est exact dans le code : `ImpactAnalysisService.analyze` trie tous les candidats sélectionnés et n'applique `maxResults` qu'à la fin. Il est infirmé en pratique : la traversée n'a aucun coût mesurable, et l'arrêt anticipé risque de changer le rapport.

**Chiffres** (§ A6.3.5, `run1` et `run3`, corpus réel et ses répliques).

| Jeu | Relations | Coût fixe, racine sans arête entrante (médiane) | Racine la plus large, `maxDepth` 32 | `maxResults` 10 000 | `maxResults` 10 |
|---|---:|---:|---:|---:|---:|
| file-f0.25 | 3 713 | 3,5 ms | 15 symboles | 2,9 ms | 2,4 ms |
| file-f0.50 | 6 934 | 4,6 ms | 24 symboles | 4,4 ms | 4,2 ms |
| mem-k1 (dépôt entier) | 14 299 | 15,0 ms (`run2` : 16,6) | 52 symboles | 17,3 ms | 15,6 ms |
| mem-k2 | 28 598 | 38,4 ms | 52 symboles | 40,6 ms | 43,7 ms |
| mem-k3 | 42 897 | 68,5 ms | 52 symboles | 70,7 ms | 69,6 ms |

- Avec ou sans `maxResults` petit, le temps est celui de la préparation, à la dispersion de mesure près.
- JFR : l'index des arêtes entrantes pèse 64 à 77 %, les limitations de base 9 à 15 %, le corps d'`analyze` (table des symboles, file de priorité, tri final) 14 à 26 %, la construction des chemins 0 %.
- Le coût croît avec la taille du snapshot (15, 38 puis 68 ms pour 1, 2 et 3 répliques), pas avec `maxResults`.
- La portée maximale observée est de 52 symboles : le graphe issu de scip-java est clairsemé, 0,36 relation par symbole.

**Risque sur le rapport.** Un arrêt dès `maxResults` candidats finalisés changerait le rapport produit, et donc celui d'`AdvancedImpactService`, qui s'appuie sur `ProjectImpactQuery`.

- **Limitations posées pendant la traversée.** `MAX_DEPTH_REACHED` est ajoutée quand un nœud à la profondeur maximale a encore des arêtes vers des symboles non sélectionnés. `EXTERNAL_TARGETS_NOT_TRAVERSED` et `GENERATED_SYMBOLS_NOT_TRAVERSED` sont ajoutées dès qu'une arête mène à un symbole externe ou généré. Un arrêt anticipé les omettrait alors que la traversée complète les aurait posées.
- **Marque de test.** `potentiallyImpactedTests` et le drapeau `testImpact` viennent de la meilleure arête `RELATED_TEST` vers chaque symbole. Cette arête peut partir d'un nœud finalisé *après* le symbole marqué : le candidat de test est découvert plus tard, par un chemin plus profond.
- **Aucun gain mesurable.** Il n'y aurait donc rien à mettre en balance avec ce risque.

**Levier réel, hors lot** (§ A6.8) : la table des symboles et l'index trié des arêtes entrantes sont reconstruits à chaque appel. Les construire une fois par snapshot, sur le modèle de la vue de requête, supprimerait l'essentiel du coût sans rien changer au parcours, donc sans risque pour le rapport. Ce levier demande sa propre mesure et son propre test d'équivalence.

## A6.13 Étape 4 — normalisation hybride mise en cache

**Réalisation** (`bba7a91b`).

- `CachedCorpus` porte `normalizedContents`, un texte normalisé par document. Il est calculé une seule fois à la construction du corpus, par la même fonction `normalize` que le score appelait à chaque requête.
- Même durée de vie que le corpus, donc mêmes péremptions : même identité (`snapshotId`, index sémantique), même éviction. Les deux péremptions déjà connues du cache de corpus (même `snapshotId` republié avec un autre contenu, sources modifiées) s'appliquent à l'identique, sans en ajouter.
- `LexicalQuery.scoreNormalized` évalue le texte déjà normalisé ; `score(content)` délègue à `normalize` puis à `scoreNormalized`.
- Pas d'index inversé : le bonus de phrase porte sur une sous-chaîne brute, qu'un index par termes perdrait.
- Le poids estimé du corpus ne compte pas les textes normalisés, par choix documenté dans le Javadoc. Les compter réduirait de moitié la taille de corpus que le cache admet, alors que l'estimation reste au-dessus du tas mesuré avec eux (ci-dessous).

**Équivalence du classement** (`1c54a3a4`, puis vert à l'octet après `bba7a91b`).

- `HybridRankingEquivalenceTest` compare octet pour octet à `hybrid-ranking-d9ae1005.tsv`, produite par le même test avec le `minos-application` de `d9ae1005` (sha256 `a4a77406…45244`, épinglée en LF).
- Corpus déterministe : 250 fichiers, 1 500 symboles, environ 3 300 documents.
- Vocabulaire piégé pour la normalisation : casse, accents, ligature `ﬁ`, `İ` dont la minuscule change de longueur, sigma grec, CJK, emoji, chiffres exotiques, soulignés, ponctuation.
- 30 requêtes, 60 résultats, en mode structuré et en mode sémantique (`local-hash`) : 3 300 lignes, avec la clé stable, les bits IEEE 754 de chaque score et le mode ; 1 489 égalités exactes départagées par `stableKey`.

**Mesures avant/après** : ce dépôt en mémoire (`mem-k1`, 81 095 documents), 4 requêtes, préchauffage 3, 15 itérations ; `minos-application` de `d9ae1005` en tête du classpath pour « avant ». L'effet dépasse de loin l'écart d'une exécution à l'autre, d'où une seule paire.

| Mesure | Avant | Après |
|---|---:|---:|
| Requête (médiane, quatre requêtes) | 133–148 ms | **23–37 ms** |
| Requête (p95) | 164–212 ms | 28–45 ms |
| Allocation par requête | 63–65 Mo | **2,1–4,6 Mo** |
| Part de `normalize` (JFR) | 82,9 % | 0 % |
| Tas retenu par le corpus | 55,2 Mo | 74,4 Mo (+19,2 Mo) |
| Poids estimé du corpus | 137,6 Mo | 137,6 Mo (inchangé, toujours un majorant) |
| Construction du corpus (1re recherche) | 506 s | 542 s (normalisation + dispersion des E/S) |

**Portée dans le chemin produit** (dit honnêtement) : le gain est **nul** tant que le cache de vues refuse les snapshots de plus de 64 Mio persistés. Au-delà, chaque requête relit le snapshot, deux fois (§ A6.3.4), et ce rechargement fait 96–97 % du temps. Le gain est aussi sans objet au-delà d'environ 158 000 documents, où le corpus lui-même n'est plus mis en cache. Il porte sur les projets dont la vue est en cache, et sur tout chemin qui garde le snapshot en mémoire. Corrections renvoyées au § A6.8.

## A6.14 Étape 5 — ADR 0047 (`Proposed`)

[ADR 0047](../adr/0047-snapshot-pagine-ou-mappe-et-table-de-chaines.md), appuyé sur les mesures des § A6.3 et § A6.11, sans implémentation.

Constats :
- le tas d'un snapshot relu (413 Mo pour ce dépôt) est 2,5 fois celui du même snapshot ingéré (163 Mo), parce que le décodage recrée une chaîne par champ ;
- une table de chaînes réduirait le fichier à environ 0,35 fois V3 ;
- les gros projets ne sont pas en cache.

Ordre proposé, chaque passage conditionné par une mesure sur le corpus réel :
1. les corrections bornées du § A6.8 ;
2. dédoublonnage des chaînes au décodage (pas de nouveau format) ;
3. table de chaînes sur disque (format V4) si nécessaire ;
4. snapshot paginé ou mappé seulement si le tas d'un projet cible reste hors budget, en chantier propre, précédé de l'inventaire des 25 classes de production qui consomment `CodeKnowledgeSnapshot`.
