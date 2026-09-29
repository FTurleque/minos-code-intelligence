# Suivi — chantier Architecture, sévérité moyenne (lot 1 : A3, un package, un module)

> Branche : `archi/a3-packages` (depuis `develop`, base `10486cb7`), worktree `minos-wt/a3-packages`.
> Constats : **A3** (packages éclatés entre modules et alias CLI dépréciés, `AUDIT-2026-09.md` § 3) et **A7** (le script de frontières ne se confronte jamais au reactor), fermé au passage.
> Décision d'architecture : [ADR 0044](../adr/0044-un-package-un-module.md).
> Agents : `impl-archi` (implémentation), `verif-archi` (inspection de chaque commit). Aucun push, aucune PR ouverte par les agents.
> Règles du lot : aucun changement de comportement (les deux tests de caractérisation A2 et les 12 golden de `minos-app/src/test/resources/characterization/` restent verts et identiques octet pour octet, jamais régénérés) ; déplacer plutôt que renommer ; aucune règle de `check-module-boundaries.py` assouplie ; aucune dépendance Maven ajoutée hors de `ALLOWED_DEPENDENCIES`.

## 1. Tableau de bord

| Jalon | Contenu | Statut | Commits |
|---|---|---|---|
| 1 | Inventaire, table de correspondance prévue, ADR 0044, contrôle « aucun package éclaté » en cliquet, A7, auto-test, branchement CI | livré, en attente de verif-archi | `073f4a43`, `26e63775` |
| 2 et suivants | Déplacements et renommages package par package, retrait des entrées tolérées, relogement des tests, suppression des alias CLI | à venir | — |

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
| storage-local `incremental.FileProjectFingerprintSnapshotStore$ActivePointer` → engine `FileFingerprint.requireSha256` | production | **à décider** : le renommage de storage-local coupe l'accès ; voir § 3.2 |
| storage-local `orchestration.FileIndexStateStore` → engine `IndexingRun.portable(Path)` | production | **à décider** : même cause ; voir § 3.2 |
| application (test) `storage.StorageBackendConfigurationTest` → engine `MinosRuntimeSettings.testing`, `StorageBackendConfiguration.resolve` | test | le test ne référence que des classes d'engine : il rejoint les tests d'engine |
| bootstrap (test) `dynamic.RuntimeIntelligenceServiceTest` → application `RuntimeIntelligenceService(…, Clock)` | test | **à décider** : le test utilise aussi des adaptateurs fichiers, il ne peut pas rejoindre application ; voir § 3.2 |
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

## 4. Table de correspondance prévue (ancien module/FQN → nouveau)

Générée depuis les sources de `10486cb7` ; mise à jour à chaque lot si un arbitrage du § 3.2 la modifie. « inchangé » = déplacement pur, seul le jar change. La ligne 1 (`DockerRuntimeBootstrap`) suit la proposition du § 3.2 (1).

| # | Nature | Ancien module | Ancien FQN | Nouveau module | Nouveau FQN |
|---|---|---|---|---|---|
| 1 | déplacement | `app` | `com.minos.cli.DockerRuntimeBootstrap` | `cli` | inchangé |
| 2 | déplacement | `app` | `com.minos.cli.MinosLauncher` | `cli` | inchangé |
| 3 | déplacement | `application` | `com.minos.discovery.DefaultDiscoveryPlugins` | `engine` | inchangé |
| 4 | déplacement | `application` | `com.minos.discovery.ProjectDiscoveryService` | `engine` | inchangé |
| 5 | déplacement | `application` | `com.minos.discovery.ProjectIgnorePolicy` | `engine` | inchangé |
| 6 | déplacement | `application` | `com.minos.discovery.spi.BuildSystemDetector` | `engine` | inchangé |
| 7 | déplacement | `application` | `com.minos.discovery.spi.LanguageDetector` | `engine` | inchangé |
| 8 | déplacement | `application` | `com.minos.discovery.spi.ProjectDetector` | `engine` | inchangé |
| 9 | déplacement | `application` | `com.minos.discovery.spi.SourceRootDetector` | `engine` | inchangé |
| 10 | déplacement | `application` | `com.minos.hosted.HmacHostedIdentityProvider` | `engine` | inchangé |
| 11 | déplacement | `application` | `com.minos.hosted.HostedAuditChain` | `engine` | inchangé |
| 12 | déplacement | `application` | `com.minos.hosted.HostedAuditDelivery` | `engine` | inchangé |
| 13 | déplacement | `application` | `com.minos.hosted.HostedAuditSink` | `engine` | inchangé |
| 14 | déplacement | `application` | `com.minos.hosted.HostedAuthorizationService` | `engine` | inchangé |
| 15 | déplacement | `application` | `com.minos.hosted.HostedAvailabilityPort` | `engine` | inchangé |
| 16 | déplacement | `application` | `com.minos.hosted.HostedCommitRecovery` | `engine` | inchangé |
| 17 | déplacement | `application` | `com.minos.hosted.HostedControlPlaneService` | `engine` | inchangé |
| 18 | déplacement | `application` | `com.minos.hosted.HostedDenialThrottle` | `engine` | inchangé |
| 19 | déplacement | `application` | `com.minos.hosted.HostedIdentityProvider` | `engine` | inchangé |
| 20 | déplacement | `application` | `com.minos.hosted.HostedMembershipService` | `engine` | inchangé |
| 21 | déplacement | `application` | `com.minos.hosted.HostedProductionBoundary` | `engine` | inchangé |
| 22 | déplacement | `application` | `com.minos.hosted.HostedRetentionService` | `engine` | inchangé |
| 23 | déplacement | `application` | `com.minos.hosted.HostedTenantMutationWriter` | `engine` | inchangé |
| 24 | déplacement | `application` | `com.minos.hosted.HostedTenantService` | `engine` | inchangé |
| 25 | déplacement | `application` | `com.minos.hosted.HostedTokenService` | `engine` | inchangé |
| 26 | déplacement | `application` | `com.minos.hosted.HostedTransportSecurityPort` | `engine` | inchangé |
| 27 | déplacement | `application` | `com.minos.hosted.HostedWorkspaceService` | `engine` | inchangé |
| 28 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingCoordinator` | `engine` | inchangé |
| 29 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingPlan` | `engine` | inchangé |
| 30 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingPlanReason` | `engine` | inchangé |
| 31 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingPlanner` | `engine` | inchangé |
| 32 | déplacement | `application` | `com.minos.incremental.IncrementalIndexingResult` | `engine` | inchangé |
| 33 | déplacement | `application` | `com.minos.incremental.ProjectChangeSet` | `engine` | inchangé |
| 34 | déplacement | `application` | `com.minos.incremental.ProjectFingerprintService` | `engine` | inchangé |
| 35 | déplacement | `application` | `com.minos.incremental.ProjectFingerprintSnapshotAlignmentService` | `engine` | inchangé |
| 36 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationAssessment` | `engine` | inchangé |
| 37 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationReason` | `engine` | inchangé |
| 38 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationScope` | `engine` | inchangé |
| 39 | déplacement | `application` | `com.minos.incremental.ProjectInvalidationService` | `engine` | inchangé |
| 40 | déplacement | `application` | `com.minos.orchestration.AuthoritativeProjectStateReconciler` | `engine` | inchangé |
| 41 | déplacement | `application` | `com.minos.orchestration.ExecutionCheckpoints` | `engine` | inchangé |
| 42 | déplacement | `application` | `com.minos.orchestration.IndexerExecutionScopeResolver` | `engine` | inchangé |
| 43 | déplacement | `application` | `com.minos.orchestration.IndexingExecutionTarget` | `engine` | inchangé |
| 44 | déplacement | `application` | `com.minos.orchestration.IndexingLifecyclePlanSupport` | `engine` | inchangé |
| 45 | déplacement | `application` | `com.minos.orchestration.IndexingLifecycleService` | `engine` | inchangé |
| 46 | déplacement | `application` | `com.minos.orchestration.IndexingResumePlanner` | `engine` | inchangé |
| 47 | déplacement | `application` | `com.minos.orchestration.IndexingResumePolicy` | `engine` | inchangé |
| 48 | déplacement | `application` | `com.minos.orchestration.IndexingRunExecutor` | `engine` | inchangé |
| 49 | déplacement | `application` | `com.minos.orchestration.ResumableArtifactPolicy` | `engine` | inchangé |
| 50 | déplacement | `application` | `com.minos.orchestration.ResumableRunMarkers` | `engine` | inchangé |
| 51 | déplacement | `application` | `com.minos.orchestration.ResumableRunSummary` | `engine` | inchangé |
| 52 | déplacement | `application` | `com.minos.runtime.MinosVersion` | `engine` | inchangé |
| 53 | déplacement | `domain` | `com.minos.dynamic.CorrelatedRuntimeObservation` | `engine` | inchangé |
| 54 | déplacement | `domain` | `com.minos.dynamic.CorrelatedRuntimeSession` | `engine` | inchangé |
| 55 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservation` | `engine` | inchangé |
| 56 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservationCompleteness` | `engine` | inchangé |
| 57 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservationSession` | `engine` | inchangé |
| 58 | déplacement | `domain` | `com.minos.dynamic.RuntimeObservationType` | `engine` | inchangé |
| 59 | déplacement | `domain` | `com.minos.dynamic.RuntimeResolutionStatus` | `engine` | inchangé |
| 60 | déplacement | `domain` | `com.minos.dynamic.RuntimeSymbolReference` | `engine` | inchangé |
| 61 | déplacement | `domain` | `com.minos.dynamic.RuntimeSymbolResolution` | `engine` | inchangé |
| 62 | déplacement | `domain` | `com.minos.hosted.HostedAccessClaims` | `engine` | inchangé |
| 63 | déplacement | `domain` | `com.minos.hosted.HostedAuditEvent` | `engine` | inchangé |
| 64 | déplacement | `domain` | `com.minos.hosted.HostedPermission` | `engine` | inchangé |
| 65 | déplacement | `domain` | `com.minos.hosted.HostedPrincipal` | `engine` | inchangé |
| 66 | déplacement | `domain` | `com.minos.hosted.HostedProjectBinding` | `engine` | inchangé |
| 67 | déplacement | `domain` | `com.minos.hosted.HostedRetentionPlan` | `engine` | inchangé |
| 68 | déplacement | `domain` | `com.minos.hosted.HostedRetentionPolicy` | `engine` | inchangé |
| 69 | déplacement | `domain` | `com.minos.hosted.HostedRole` | `engine` | inchangé |
| 70 | déplacement | `domain` | `com.minos.hosted.HostedTenantState` | `engine` | inchangé |
| 71 | déplacement | `domain` | `com.minos.hosted.SharedWorkspace` | `engine` | inchangé |
| 72 | renommage | `app` | `com.minos.cli.DockerMcpTransport` | `app` | `com.minos.app.DockerMcpTransport` |
| 73 | renommage | `app` | `com.minos.cli.McpBackend` | `app` | `com.minos.app.McpBackend` |
| 74 | renommage | `app` | `com.minos.cli.McpBackendConfiguration` | `app` | `com.minos.app.McpBackendConfiguration` |
| 75 | renommage | `app` | `com.minos.cli.McpBackendConfigurationStore` | `app` | `com.minos.app.McpBackendConfigurationStore` |
| 76 | renommage | `app` | `com.minos.cli.McpBackendRouter` | `app` | `com.minos.app.McpBackendRouter` |
| 77 | renommage | `application` | `com.minos.dynamic.RuntimeIntelligenceService` | `application` | `com.minos.application.dynamic.RuntimeIntelligenceService` |
| 78 | renommage | `application` | `com.minos.dynamic.RuntimeObservationEnvelopeCodec` | `application` | `com.minos.application.dynamic.RuntimeObservationEnvelopeCodec` |
| 79 | renommage | `application` | `com.minos.semantic.EmbeddingProvider` | `application` | `com.minos.application.semantic.EmbeddingProvider` |
| 80 | renommage | `application` | `com.minos.semantic.HybridContextBuilder` | `application` | `com.minos.application.semantic.HybridContextBuilder` |
| 81 | renommage | `application` | `com.minos.semantic.HybridSearchService` | `application` | `com.minos.application.semantic.HybridSearchService` |
| 82 | renommage | `application` | `com.minos.semantic.LocalHashEmbeddingProvider` | `application` | `com.minos.application.semantic.LocalHashEmbeddingProvider` |
| 83 | renommage | `application` | `com.minos.semantic.OllamaEmbeddingProvider` | `application` | `com.minos.application.semantic.OllamaEmbeddingProvider` |
| 84 | renommage | `application` | `com.minos.semantic.SemanticDocumentFactory` | `application` | `com.minos.application.semantic.SemanticDocumentFactory` |
| 85 | renommage | `application` | `com.minos.semantic.SemanticIndexBudget` | `application` | `com.minos.application.semantic.SemanticIndexBudget` |
| 86 | renommage | `application` | `com.minos.semantic.SemanticIndexService` | `application` | `com.minos.application.semantic.SemanticIndexService` |
| 87 | renommage | `application` | `com.minos.semantic.SemanticSearchEvaluator` | `application` | `com.minos.application.semantic.SemanticSearchEvaluator` |
| 88 | renommage | `application` | `com.minos.semantic.SemanticSearchService` | `application` | `com.minos.application.semantic.SemanticSearchService` |
| 89 | renommage | `application` | `com.minos.storage.StorageBackends` | `application` | `com.minos.application.StorageBackends` |
| 90 | renommage | `engine` | `com.minos.adapter.scip.ScipSymbolSnapshotReport` | `engine` | `com.minos.orchestration.ScipSymbolSnapshotReport` |
| 91 | renommage | `engine` | `com.minos.adapter.scip.ScipSymbolSnapshotRequest` | `engine` | `com.minos.orchestration.ScipSymbolSnapshotRequest` |
| 92 | renommage | `integration-git` | `com.minos.git.GitIntelligenceService` | `integration-git` | `com.minos.integration.git.GitIntelligenceService` |
| 93 | renommage | `integration-git` | `com.minos.git.JGitCloneDeadline` | `integration-git` | `com.minos.integration.git.JGitCloneDeadline` |
| 94 | renommage | `integration-git` | `com.minos.git.JGitRemoteGitClient` | `integration-git` | `com.minos.integration.git.JGitRemoteGitClient` |
| 95 | renommage | `integration-git` | `com.minos.git.JGitRemoteRepositoryMaterializer` | `integration-git` | `com.minos.integration.git.JGitRemoteRepositoryMaterializer` |
| 96 | renommage | `integration-git` | `com.minos.git.RemoteCloneBudget` | `integration-git` | `com.minos.integration.git.RemoteCloneBudget` |
| 97 | renommage | `integration-git` | `com.minos.git.RemoteRepositoryCachePolicy` | `integration-git` | `com.minos.integration.git.RemoteRepositoryCachePolicy` |
| 98 | renommage | `nexus` | `com.minos.integration.nexus.NexusExportContract` | `nexus` | `com.minos.nexus.NexusExportContract` |
| 99 | renommage | `nexus` | `com.minos.integration.nexus.NexusExportService` | `nexus` | `com.minos.nexus.NexusExportService` |
| 100 | renommage | `nexus` | `com.minos.integration.nexus.NexusSemanticSignalContract` | `nexus` | `com.minos.nexus.NexusSemanticSignalContract` |
| 101 | renommage | `nexus` | `com.minos.integration.nexus.NexusSemanticSignalService` | `nexus` | `com.minos.nexus.NexusSemanticSignalService` |
| 102 | renommage | `runtime-local` | `com.minos.runtime.BoundedProcessOutput` | `runtime-local` | `com.minos.runtime.local.BoundedProcessOutput` |
| 103 | renommage | `runtime-local` | `com.minos.runtime.CgroupJobOwnership` | `runtime-local` | `com.minos.runtime.local.CgroupJobOwnership` |
| 104 | renommage | `runtime-local` | `com.minos.runtime.CommandLocator` | `runtime-local` | `com.minos.runtime.local.CommandLocator` |
| 105 | renommage | `runtime-local` | `com.minos.runtime.CompositeProviderRuntimeManager` | `runtime-local` | `com.minos.runtime.local.CompositeProviderRuntimeManager` |
| 106 | renommage | `runtime-local` | `com.minos.runtime.DistributedArtifactBundleStore` | `runtime-local` | `com.minos.runtime.local.DistributedArtifactBundleStore` |
| 107 | renommage | `runtime-local` | `com.minos.runtime.DistributedArtifactCachePolicy` | `runtime-local` | `com.minos.runtime.local.DistributedArtifactCachePolicy` |
| 108 | renommage | `runtime-local` | `com.minos.runtime.DistributedIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.DistributedIndexerExecutor` |
| 109 | renommage | `runtime-local` | `com.minos.runtime.FileResumableRunMarkers` | `runtime-local` | `com.minos.runtime.local.FileResumableRunMarkers` |
| 110 | renommage | `runtime-local` | `com.minos.runtime.IndexerProcessPlan` | `runtime-local` | `com.minos.runtime.local.IndexerProcessPlan` |
| 111 | renommage | `runtime-local` | `com.minos.runtime.IndexerProcessPlanFactory` | `runtime-local` | `com.minos.runtime.local.IndexerProcessPlanFactory` |
| 112 | renommage | `runtime-local` | `com.minos.runtime.LinuxBubblewrapWorkerSandboxBackend` | `runtime-local` | `com.minos.runtime.local.LinuxBubblewrapWorkerSandboxBackend` |
| 113 | renommage | `runtime-local` | `com.minos.runtime.LinuxCgroupJob` | `runtime-local` | `com.minos.runtime.local.LinuxCgroupJob` |
| 114 | renommage | `runtime-local` | `com.minos.runtime.LocalIsolatedIndexWorker` | `runtime-local` | `com.minos.runtime.local.LocalIsolatedIndexWorker` |
| 115 | renommage | `runtime-local` | `com.minos.runtime.LocalProviderWorkspace` | `runtime-local` | `com.minos.runtime.local.LocalProviderWorkspace` |
| 116 | renommage | `runtime-local` | `com.minos.runtime.ProcessIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.ProcessIndexerExecutor` |
| 117 | renommage | `runtime-local` | `com.minos.runtime.ProcessOwnershipTracker` | `runtime-local` | `com.minos.runtime.local.ProcessOwnershipTracker` |
| 118 | renommage | `runtime-local` | `com.minos.runtime.ProcessSandboxCapableIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.ProcessSandboxCapableIndexerExecutor` |
| 119 | renommage | `runtime-local` | `com.minos.runtime.ProcessTreeTermination` | `runtime-local` | `com.minos.runtime.local.ProcessTreeTermination` |
| 120 | renommage | `runtime-local` | `com.minos.runtime.ProviderProcessEnvironment` | `runtime-local` | `com.minos.runtime.local.ProviderProcessEnvironment` |
| 121 | renommage | `runtime-local` | `com.minos.runtime.ProviderResidueReclamation` | `runtime-local` | `com.minos.runtime.local.ProviderResidueReclamation` |
| 122 | renommage | `runtime-local` | `com.minos.runtime.ProviderWorkspaceFiles` | `runtime-local` | `com.minos.runtime.local.ProviderWorkspaceFiles` |
| 123 | renommage | `runtime-local` | `com.minos.runtime.ProviderWriteQuota` | `runtime-local` | `com.minos.runtime.local.ProviderWriteQuota` |
| 124 | renommage | `runtime-local` | `com.minos.runtime.ProviderWriteQuotaSupervisor` | `runtime-local` | `com.minos.runtime.local.ProviderWriteQuotaSupervisor` |
| 125 | renommage | `runtime-local` | `com.minos.runtime.RunDirectoryRetention` | `runtime-local` | `com.minos.runtime.local.RunDirectoryRetention` |
| 126 | renommage | `runtime-local` | `com.minos.runtime.StrongProcessOwnershipIndexerExecutor` | `runtime-local` | `com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor` |
| 127 | renommage | `runtime-local` | `com.minos.runtime.WindowsAppContainerWorkerSandboxBackend` | `runtime-local` | `com.minos.runtime.local.WindowsAppContainerWorkerSandboxBackend` |
| 128 | renommage | `runtime-local` | `com.minos.runtime.WindowsContainmentScript` | `runtime-local` | `com.minos.runtime.local.WindowsContainmentScript` |
| 129 | renommage | `runtime-local` | `com.minos.runtime.WindowsExecutionPathIdentityProvider` | `runtime-local` | `com.minos.runtime.local.WindowsExecutionPathIdentityProvider` |
| 130 | renommage | `runtime-local` | `com.minos.runtime.WindowsJobObjectProcessOwnership` | `runtime-local` | `com.minos.runtime.local.WindowsJobObjectProcessOwnership` |
| 131 | renommage | `runtime-local` | `com.minos.runtime.WorkerResourceContainment` | `runtime-local` | `com.minos.runtime.local.WorkerResourceContainment` |
| 132 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxBackend` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxBackend` |
| 133 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxBackends` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxBackends` |
| 134 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxQualification` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxQualification` |
| 135 | renommage | `runtime-local` | `com.minos.runtime.WorkerSandboxSelection` | `runtime-local` | `com.minos.runtime.local.WorkerSandboxSelection` |
| 136 | renommage | `storage-local` | `com.minos.incremental.FileProjectFingerprintSnapshotStore` | `storage-local` | `com.minos.storage.local.incremental.FileProjectFingerprintSnapshotStore` |
| 137 | renommage | `storage-local` | `com.minos.orchestration.FileIndexStateStore` | `storage-local` | `com.minos.storage.local.orchestration.FileIndexStateStore` |
| 138 | renommage | `storage-local` | `com.minos.orchestration.IndexRunRetentionPolicy` | `storage-local` | `com.minos.storage.local.orchestration.IndexRunRetentionPolicy` |
| 139 | renommage | `storage-local` | `com.minos.orchestration.IndexRunRetentionService` | `storage-local` | `com.minos.storage.local.orchestration.IndexRunRetentionService` |
| 140 | renommage | `storage-local` | `com.minos.orchestration.ProjectIndexLease` | `storage-local` | `com.minos.storage.local.orchestration.ProjectIndexLease` |
| 141 | renommage | `storage-local` | `com.minos.registry.InterProcessLocalProjectRegistry` | `storage-local` | `com.minos.storage.local.registry.InterProcessLocalProjectRegistry` |
| 142 | renommage | `storage-local` | `com.minos.registry.LocalProjectRegistry` | `storage-local` | `com.minos.storage.local.registry.LocalProjectRegistry` |
| 143 | renommage | `storage-local` | `com.minos.registry.ProjectPathMappingStore` | `storage-local` | `com.minos.storage.local.registry.ProjectPathMappingStore` |
| 144 | renommage | `storage-local` | `com.minos.storage.LocalStorageBackend` | `storage-local` | `com.minos.storage.local.LocalStorageBackend` |
| 145 | renommage | `storage-local` | `com.minos.storage.LocalStorageRetentionService` | `storage-local` | `com.minos.storage.local.LocalStorageRetentionService` |
| 146 | renommage | `storage-local` | `com.minos.storage.SerializedRuntimeObservationStore` | `storage-local` | `com.minos.storage.local.SerializedRuntimeObservationStore` |
| 147 | renommage | `storage-local` | `com.minos.store.ActiveSnapshotRepository` | `storage-local` | `com.minos.storage.local.store.ActiveSnapshotRepository` |
| 148 | renommage | `storage-local` | `com.minos.store.CodeKnowledgeSnapshotBinaryCodec` | `storage-local` | `com.minos.storage.local.store.CodeKnowledgeSnapshotBinaryCodec` |
| 149 | renommage | `storage-local` | `com.minos.store.EnvironmentHostedTenantKeyProvider` | `storage-local` | `com.minos.storage.local.store.EnvironmentHostedTenantKeyProvider` |
| 150 | renommage | `storage-local` | `com.minos.store.FileHostedControlPlaneStore` | `storage-local` | `com.minos.storage.local.store.FileHostedControlPlaneStore` |
| 151 | renommage | `storage-local` | `com.minos.store.FileRuntimeObservationStore` | `storage-local` | `com.minos.storage.local.store.FileRuntimeObservationStore` |
| 152 | renommage | `storage-local` | `com.minos.store.FileSemanticVectorStore` | `storage-local` | `com.minos.storage.local.store.FileSemanticVectorStore` |
| 153 | renommage | `storage-local` | `com.minos.store.FileSymbolSnapshotStore` | `storage-local` | `com.minos.storage.local.store.FileSymbolSnapshotStore` |
| 154 | renommage | `storage-local` | `com.minos.store.ProjectMutationSemanticVectorStore` | `storage-local` | `com.minos.storage.local.store.ProjectMutationSemanticVectorStore` |
| 155 | renommage | `storage-local` | `com.minos.store.SnapshotBinaryCodecSupport` | `storage-local` | `com.minos.storage.local.store.SnapshotBinaryCodecSupport` |
| 156 | renommage | `storage-local` | `com.minos.store.SnapshotCodec` | `storage-local` | `com.minos.storage.local.store.SnapshotCodec` |
| 157 | renommage | `storage-local` | `com.minos.store.SnapshotCodecV1` | `storage-local` | `com.minos.storage.local.store.SnapshotCodecV1` |
| 158 | renommage | `storage-local` | `com.minos.store.SnapshotCodecV2` | `storage-local` | `com.minos.storage.local.store.SnapshotCodecV2` |
| 159 | renommage | `storage-local` | `com.minos.store.SnapshotCompactionService` | `storage-local` | `com.minos.storage.local.store.SnapshotCompactionService` |
| 160 | renommage | `storage-local` | `com.minos.store.SnapshotIntegrityService` | `storage-local` | `com.minos.storage.local.store.SnapshotIntegrityService` |
| 161 | renommage | `storage-local` | `com.minos.store.SnapshotProjectLease` | `storage-local` | `com.minos.storage.local.store.SnapshotProjectLease` |
| 162 | renommage | `storage-local` | `com.minos.store.SnapshotRepository` | `storage-local` | `com.minos.storage.local.store.SnapshotRepository` |
| 163 | renommage | `storage-local` | `com.minos.store.SnapshotRetentionPolicy` | `storage-local` | `com.minos.storage.local.store.SnapshotRetentionPolicy` |
| 164 | renommage | `storage-local` | `com.minos.store.SnapshotRetentionService` | `storage-local` | `com.minos.storage.local.store.SnapshotRetentionService` |
| 165 | suppression | `cli` | `com.minos.cli.LocalProjectOperations` | `—` | supprimé → appelants sur `com.minos.application.LocalProjectOperations` |
| 166 | suppression | `cli` | `com.minos.cli.LocalProjectSymbolQuery` | `—` | supprimé → appelants sur `com.minos.application.LocalProjectSymbolQuery` |
| 167 | suppression | `cli` | `com.minos.cli.ProjectOperations` | `—` | supprimé → appelants sur `com.minos.application.ProjectOperations` |
| 168 | suppression | `cli` | `com.minos.cli.ProjectSymbolQuery` | `—` | supprimé → appelants sur `com.minos.application.ProjectSymbolQuery` |

Total : 71 déplacements, 93 renommages, 4 suppressions.

## 5. Références littérales à mettre à jour, à assertion identique

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

## 7. Constats verif-archi

| # | Commit | Constat | Sévérité | Résolution |
|---|---|---|---|---|

## 8. À traiter plus tard (hors périmètre)

| Origine | Description | Renvoi |
|---|---|---|
| impl-archi (jalon 1) | `StableFileSystemIdentity` (engine) découvre `ExecutionPathIdentityProvider` par `ServiceLoader.load(Class)`, donc par le chargeur de contexte du thread : même défaut que A8, sur un autre service. A3 ne touche que le contenu du fichier de service (nom de la classe fournie), pas le chargeur. | A8 (étendre) |
| SPRINT-2-SUIVI (V19) | `minos-cli/LazyAutonomousIndexOperations` n'est câblé nulle part en production. Hors de la question des packages. | A4 |
