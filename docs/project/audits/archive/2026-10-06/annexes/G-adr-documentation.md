<!-- Annexe G de l'audit 2026-10. Rapport d'analyse brut, conservé tel que remis ; les identifiants G-NN y valent MINOS-AUD-GNN dans docs/audit/archive/2026-10-06/constats.md. Les chemins « scratchpad/ » cités désignent des reproductions jetables hors dépôt, non conservées. -->
# Audit G — Réconciliation ADR / documentation / code

Dépôt : `N:\workspace-dev\minos-code-intelligence`, HEAD `bc1d3421` (develop, 2026-10-06, merge PR #333). Lecture seule. Maven non lancé, MCP `minos` non utilisé.
Les gates Python existants (`check-milestone-artifact-references.py`, `check-module-boundaries.py`, `check-private-io.py`, `check-current-docs.py`, `product-facts.py`) ont été exécutés en lecture. Attention : `product-facts.py` réécrit `docs/generated/product-facts.md` ; `git status` ne montre aucun diff après exécution (contenu identique).

Résultat global : 57 ADR (0001 à 0057), aucun numéro manquant, aucun doublon, tous liés dans `docs/adr/README.md`.

| État réel | Nombre | ADR |
|---|---|---|
| IMPLÉMENTÉE | 41 | 0001-0003, 0005-0024, 0026-0030, 0032, 0034-0039, 0041-0046 |
| PARTIELLEMENT IMPLÉMENTÉE | 3 | 0033, 0040, 0057 |
| NON IMPLÉMENTÉE | 10 | 0047, 0048-0054, 0055, 0056 |
| CONTREDITE PAR LE CODE | 1 | 0031 (point 2 uniquement) |
| À RÉEXAMINER | 2 | 0004, 0025 |

Les 8 ADR « Proposed » du dépôt (0036 selon l'index, 0047-0054) : 0047-0054 sont conformes (rien d'implémenté de fait). 0036 est « Proposed » dans l'index mais « Accepted » dans son propre fichier et implémenté (voir G-02).

Convention des chemins : `engine` = `minos-engine/src/main/java/com/minos/…`, etc. Les numéros de ligne sont ceux du HEAD ci-dessus.

---

## 1. Tableau ADR

| ADR | Titre | Statut déclaré | État réel | Preuve | Remarque |
|---|---|---|---|---|---|
| 0001 | Cœur agnostique du langage et de l'indexeur | Acceptée | IMPLÉMENTÉE | `minos-engine/.../orchestration/IndexerCapability.java:10` (capacités explicites) ; `minos-application/src/test/.../architecture/ProviderBoundaryTest.java:12` | — |
| 0002 | SCIP protocole privilégié | Acceptée | IMPLÉMENTÉE | `minos-provider-scip/.../adapter/scip/ScipSymbolNormalizer.java`, `ScipIngestionAdapter.java` ; Grep `com.google.protobuf\|com.sourcegraph` : un seul fichier de prod, dans `minos-provider-scip` (aucun type Protobuf hors adaptateur) | « jamais obligatoire » : `IndexerRegistry` négocie des descriptors non-SCIP (ADR-0008) |
| 0003 | Glean optionnel derrière `CodeKnowledgeStore` | Acceptée | IMPLÉMENTÉE (port seul) | `minos-engine/.../store/CodeKnowledgeStore.java:19` (port) ; `minos-domain/.../OriginType.java:8` (`GLEAN` = simple valeur d'enum) | Aucun backend Glean dans le code : conforme au caractère optionnel |
| 0004 | Cœur Java 25 / Maven, sans framework | Partiellement remplacée par 0005 | À RÉEXAMINER | `pom.xml:125` `requireMavenVersion [3.9,4.0)` ; aucun `pom.xml` ne cite spring/quarkus/micronaut ; `.mvn/wrapper/maven-wrapper.properties` : `apache-maven-3.10.0` | La partie « sans framework » est tenue. « Maven 3.9.x » (ADR, `docs/TOOLCHAIN_POLICY.md`) est littéralement faux pour le wrapper (3.10.0, accepté par la plage de l'enforcer). Voir G-11 |
| 0005 | Aligner sur Java 24 | Acceptée | IMPLÉMENTÉE | `pom.xml:44` `maven.compiler.release=24` ; `pom.xml:124` `requireJavaVersion [24,25)` | — |
| 0006 | Promotion atomique des index | Acceptée | IMPLÉMENTÉE | `engine/orchestration/IndexingRuntimePorts.java:90` (`SnapshotPromoter`) ; `ScipProjectSnapshotLifecycle.java:32` (`implements SnapshotStager, SnapshotPromoter`) ; `engine/io/DurableAtomicFile.java:135` (`ATOMIC_MOVE`) | — |
| 0007 | Identités projet/workspace dans le registre local | Accepté | IMPLÉMENTÉE | `minos-storage-local/.../registry/LocalProjectRegistry.java:95,119` (`UUID.randomUUID()`) ; `InterProcessLocalProjectRegistry.java` | — |
| 0008 | Indexeurs négociés par capacités | Accepté | IMPLÉMENTÉE | `engine/orchestration/IndexerRegistry.java:25-27` (`NEGOTIATION_ORDER` : priorité décroissante puis id) ; `IndexerDescriptor.java` | — |
| 0009 | Identité de symbole sans canonicité inventée | Accepted | IMPLÉMENTÉE | `minos-domain/.../SymbolIdentityQuality.java:11,14` (`STRUCTURAL_FALLBACK`, `PROVIDER_SCOPED_FALLBACK`) ; `ScipSymbolNormalizer.java:108` | — |
| 0010 | Relations avec provenance/preuve/confiance | Accepted | IMPLÉMENTÉE | `minos-domain/.../Evidence.java:8` ; `RelationshipKind.java:6` ; `ScipRelationshipNormalizer.java` | — |
| 0011 | Recherche et contexte bornés | Accepted | IMPLÉMENTÉE | `minos-application/.../context/CodeSearchService.java:30` ; `CodeSearchCriteria.java:16,19` (`maxTokens`, `MAX_DEPTH = 3`) | — |
| 0012 | Tests liés = dérivations explicables | Accepted | IMPLÉMENTÉE | `engine/query/RelatedTestDerivationService.java:40` ; `minos-application/.../impact/ImpactAnalysisReport.java:48` (`RelationshipKind.RELATED_TEST`) | — |
| 0013 | Faits d'architecture séparés de leur interprétation | Accepted | IMPLÉMENTÉE | `minos-application/.../architecture/ArchitectureCentralityService.java:23`, `ArchitectureConcentrationService.java:24` | — |
| 0014 | Incrémental seulement sous preuve de capacité | Accepted | IMPLÉMENTÉE | `engine/incremental/IncrementalIndexingPlan.java:62-69` (INCREMENTAL exige `ALL_INDEXERS_SUPPORT_INCREMENTAL`) | — |
| 0015 | Impact = estimation potentielle | Accepted | IMPLÉMENTÉE | `minos-application/.../impact/ImpactAnalysisService.java:25` ; `ImpactAnalysisReport.java:48-50` | — |
| 0016 | CLI stable | Accepted | IMPLÉMENTÉE | `minos-cli/.../cli/MinosCli.java:23` ; codes de sortie 2/3 (voir Q22/Q8 en section 3) | La dernière puce (« `minos index` n'invente pas de runner ») reste vraie malgré l'indexation autonome ADR-0021 (`LocalAutonomousIndexOperations`) |
| 0017 | MCP STDIO read-only | Accepted | IMPLÉMENTÉE | `minos-mcp/.../MinosMcpServer.java:54` (`StdioServerTransportProvider`) ; `MinosMcpTools.java:83-144` : 31 tools, tous des lectures (aucun verbe d'écriture) | — |
| 0018 | API Java publique versionnée | Accepted | IMPLÉMENTÉE | `minos-api/.../MinosApi.java:18` ; `MinosApi.java:20` (`CONTRACT_VERSION = "1"`) | — |
| 0019 | Cross-repo par identité exacte ; faits Git séparés | Accepted | IMPLÉMENTÉE | `minos-application/.../workspace/WorkspaceIntelligenceService.java:124-136` (`providerId`+`externalId`, comptage `ambiguousCount`/`unresolvedCount`) ; `minos-integration-git/.../GitIntelligenceService.java:46` | — |
| 0020 | Frontière JSON MINOS/NEXUS | Accepted | IMPLÉMENTÉE | `minos-cli/.../NexusExportCommand.java:15` (`nexus-export`) ; `minos-nexus/.../NexusExportContract.java:15,24` (`contractVersion`) | — |
| 0021 | Runtime natif pour l'indexation autonome | Accepted (fichier) / Partially superseded by 0037 (index) | IMPLÉMENTÉE (parties vivantes) | `minos-cli/.../LocalAutonomousIndexOperations.java:187` ; `scripts/release/build-windows-distribution.ps1:156,239` (jpackage + jdeps, JRE embarqué) | La partie « Docker = MCP read-only non autonome » est supersédée par 0037. L'en-tête du fichier n'a pas été mis à jour. Voir G-03 |
| 0022 | Reactor Maven et frontières de modules | Accepted — implémenté M15-S2 | IMPLÉMENTÉE (principe) | `pom.xml:24-37` (14 modules) ; `scripts/architecture/check-module-boundaries.py` → « modules=14 … SUCCESS » | La liste de 12 modules + parent de l'ADR est périmée (14 + parent) ; amendée par 0042/0044/0055 sans bandeau dans 0022. Voir G-05 |
| 0023 | Persistance locale décomposée | Accepted — implémenté M15-S6 | IMPLÉMENTÉE | `minos-storage-local/.../store/FileSymbolSnapshotStore.java:38`, `SnapshotRepository.java`, `ActiveSnapshotRepository.java:29` (magic `0x4D4E4150`), `SnapshotBinaryCodecSupport.java:74` (magic `0x4D4E5359`) | Cite V1/V2 ; V3 ajouté ensuite par 0046 (sans renvoi) |
| 0024 | Vue de snapshot actif + index reconstruisibles | Accepted — implémenté M15-S7/S8 | IMPLÉMENTÉE | `engine/store/SnapshotQueryView.java:11` ; `InMemoryCodeKnowledgeStore.java` | Son contrat sera modifié par 0056 (non implémenté) |
| 0025 | Évolution du backend gouvernée par mesures | Accepted | À RÉEXAMINER | Backend par défaut = fichiers : `minos-bootstrap/.../StorageBackendSelection.java:19-20` (`"local"`) ; mais backend alternatif livré : `minos-storage-postgresql/.../PostgresStorageBackend.java`, `PostgresSemanticReadQueries.java:90` (pgvector) | La règle impose « nouvelle décision ADR avant intégration runtime » d'un backend alternatif ; aucun ADR de `docs/adr/` ne ratifie PostgreSQL/pgvector (décrit seulement dans `docs/roadmap/M30_EXECUTION.md`). Voir G-08 |
| 0026 | SPI discovery/providers et profils de capacités | Accepted | IMPLÉMENTÉE | `engine/discovery/spi/{ProjectDetector,BuildSystemDetector,SourceRootDetector,LanguageDetector}.java` ; `DefaultDiscoveryPlugins.java` ; `engine/orchestration/IndexerProvider.java`, `ProviderCapabilityProfile.java`, `IndexerProviderRegistry.java:11`, `CapabilitySupportLevel.java:5-8` (FULL/PARTIAL/EXPERIMENTAL/UNSUPPORTED) | — |
| 0027 | IntelliJ client externe, protocole CLI versionné | Accepted | IMPLÉMENTÉE | `minos-intellij/build.gradle.kts:56-62` (Java 21, `release=21`), dépendances = gson seul (`:20`), aucun `com.minos:*` ; `minos-cli/.../IdeCommand.java:14` (`PROTOCOL_ID = "minos-ide"`), `:47` (`handshake`) | `minos-intellij` hors reactor (Gradle) |
| 0028 | Program graph capability-honest | Accepted for M19 | IMPLÉMENTÉE | `minos-domain/.../program/ProgramGraph.java` ; `minos-application/.../program/analysis/ProgramGraphService.java:82` ; `RelationshipProgramGraphProvider.java:102` (`EXECUTION_ORDER_NOT_PROVEN`) | — |
| 0029 | Couche sémantique optionnelle, reconstruisible | Accepted | IMPLÉMENTÉE | `minos-application/.../MinosApplicationRuntimeConfiguration.java:28` (défaut `"disabled"`) ; `semantic/EmbeddingProvider.java:8` ; `LocalHashEmbeddingProvider.java:32` | Les tools MCP sémantiques ne construisent pas d'index (liste `MinosMcpTools.java:122-128`) |
| 0030 | Provider Java AST de référence | Accepted | IMPLÉMENTÉE | `minos-application/.../program/analysis/JavaAstParser.java:4,10` (JavacTask/JavaCompiler) ; `JavaSourceProgramGraphProvider.java:20` | — |
| 0031 | Provider learned local + ANN sous mesure | Accepted for M23 | CONTREDITE PAR LE CODE (point 2 seulement) | `minos-application/.../semantic/OllamaEmbeddingProvider.java:23-27,112` accepte « loopback **ou** le service Docker managé `minos-ollama` » (`MANAGED_DOCKER_ENDPOINT`, `:31`) ; ADR §2 : « loopback endpoints only » | Autres points tenus : float32 `FileSemanticVectorStore.java:214`, LRU 256, pas d'ANN (aucun `hnsw`/`ivfflat` dans `minos-storage-postgresql/src/main`, balayage exact `<=>`). Tension avec §8 « no vector database » : pgvector. Voir G-07, G-08 |
| 0032 | Providers SCIP polyglottes sous preuves | Accepted | IMPLÉMENTÉE | `minos-provider-scip/.../ScipIndexerCatalog.java:26-27` (scip-dotnet 0.2.14, scip-go 0.2.7), `:32` (scip-clang) ; `ProjectDiscovery.java:44,56` (enums `Language`/`BuildSystem`) | — |
| 0033 | Révisions distantes immuables, artefacts worker vérifiés | Accepted | PARTIELLEMENT IMPLÉMENTÉE | `minos-integration-git/.../JGitRemoteRepositoryMaterializer.java:41` ; `engine/remote/DistributedArtifactManifest.java:36-37` (v1/v2) ; `engine/remote/RemoteRepositoryRequest.java:58` (`FETCH_ONLY`) ; mais exécution distante non fiable fermée : `minos-cli/.../LocalRemoteIndexOperations.java:116` | Matérialisation/artefacts implémentés ; l'indexation distante de bout en bout est « fermée par décision » (ADR-0041) ; 0033 n'a aucun renvoi vers 0041. Voir G-06 |
| 0034 | Observations runtime partielles | Accepted | IMPLÉMENTÉE | `minos-application/.../dynamic/RuntimeObservationEnvelopeCodec.java:29` (`minos-runtime-observation-v1`, `CodingErrorAction.REPORT` `:54`) ; 3 tools `minos_runtime_*` (`MinosMcpTools.java:130-134`) | — |
| 0035 | Control plane tenant opt-in, clés externes | Accepted | IMPLÉMENTÉE | `MinosApplication.java:67` (`MINOS_HOSTED_MODE`) ; `minos-storage-local/.../EnvironmentHostedTenantKeyProvider.java:19` (`MINOS_TEAM_KEY_`) ; `FileHostedControlPlaneStore.java:247,249` (AES/GCM + AAD) ; 5 tools `minos_team_*` (`MinosMcpTools.java:136-144`) ; `minos-cli/.../TeamCommand.java:24` (`MINOS_TEAM_TOKEN`) | — |
| 0036 | Frontières de production fail-closed, ProgramGraph mesuré | Accepted (fichier) / **Proposed** (index, arc42) | IMPLÉMENTÉE | `FingerprintConstrainedJavaProgramGraphProvider.java:36` (code `JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT`), instancié `ProgramGraphService.java:82` ; `minos-runtime-local/.../WorkerSandboxQualification.java:119` ; `engine/hosted/HostedProductionBoundary.java:33,47` (`EMBEDDED_LOCAL_FIRST`) | Statut incohérent. Voir G-02 |
| 0037 | Backends MCP natif/Docker explicites, fail-closed | Accepted pour le contrat S1 ; parité non acquise (index : « parity pending ») | IMPLÉMENTÉE (contrat S1) | `minos-app/.../McpBackendConfigurationStore.java:23,28` (`backend.properties`, `formatVersion`) ; `DockerMcpTransport.java:78` (`docker exec -i`) ; `McpBackendRouter.java:13` | « Parité pending » obsolète au regard de M29 clos (STATUS, `risks/register.md` R-01 résolu). Voir G-04 |
| 0038 | Confinement agrégé des ressources workers | Accepted ; §4 amendé par 0041 | IMPLÉMENTÉE | `minos-runtime-local/.../LinuxCgroupJob.java:26` (`memory.max`…) ; `LinuxBubblewrapWorkerSandboxBackend.java:32,160-164` ; `WorkerSandboxQualification.java:28` | L'index n'indique pas l'amendement par 0041 |
| 0039 | Reprise d'indexation après interruption | Accepted — implémenté sprint 2 | IMPLÉMENTÉE | `minos-cli/.../LocalAutonomousIndexOperations.java:187` (`IndexingRun.Status.INTERRUPTED`) ; `engine/orchestration/IndexingResumePlanner.java:35,268` ; `minos-api/.../MinosApi.java:249` (`resumableRunId`) ; `engine/orchestration/IndexerCapability.java:29` (`RESUMABLE_ARTIFACT`) | « Écarts (f) » cite `IndexingResumePlanner` dans `minos-application` ; il est dans `minos-engine`. Voir G-17 |
| 0040 | Indexeurs embarqués dans le paquet | Accepted — implémentée lot D1 | PARTIELLEMENT IMPLÉMENTÉE | `minos-provider-scip/src/main/resources/.../embedded-tools.json` ; `.../runtime/EmbeddedToolsPayload.java:28` (`TOOLS-MANIFEST.json`) ; `scripts/release/{build-embedded-tools,sync-tools-manifest,check-tools-manifest}.py` | Par sa propre section « Mise en œuvre » : §3 (repli par téléchargement automatique) non réalisé, pas de JDK embarqué, périmètre Windows x64 = Coursier/Maven/Node/scip-typescript/scip-java. Écarts déclarés dans l'ADR lui-même |
| 0041 | Indexation distante non fiable : fermeture assumée | Accepted (option b) | IMPLÉMENTÉE | `WorkerSandboxQualification.java:28` (`WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041`) ; `WorkerSandboxBackends.java:114` (`REJECTED_BY_DECISION`) ; `minos-cli/.../DoctorCommand.java:167` (section `workerSandbox`) ; `LocalRemoteIndexOperations.java:116` (refus avant matérialisation) ; test `WorkerResourceContainmentTest.java:81` | — |
| 0042 | Racine de composition (option c : `minos-bootstrap`) | Accepted | IMPLÉMENTÉE | `minos-bootstrap/.../DefaultMinosApplicationComposer.java` ; `META-INF/services/com.minos.application.MinosApplicationComposer` (bootstrap) ; `minos-application/pom.xml:23,28` (dépend de domain + engine seulement) ; `MinosApplicationComposers.java:36` | — |
| 0043 | Retrait des artefacts de jalon | Accepted | IMPLÉMENTÉE | `scripts/quality/check-milestone-artifact-references.py` → « SUCCESS (scripts checked=117) » ; `scripts/history/{m0,m15,m16,m17,m18,m19,m20,m21,m29}` ; gates sans `mNN` dans `scripts/quality/` | — |
| 0044 | Un package, un module | Accepted | IMPLÉMENTÉE | `check-module-boundaries.py` : « A3 package ownership: packages=45, each owned by exactly one module » ; recalcul indépendant des packages `src/main/java` : 0 package partagé entre modules | Ne couvre que `src/main` (tests non examinés) |
| 0045 | Constructeur unique, point d'entrée nommé | Accepted | IMPLÉMENTÉE | `MinosCli.java:98,103` (`builder`, constructeur privé) ; `LocalProjectArchitectureQuery.java:33,36` (fabrique + constructeur privé) ; `MinosApplication.java:88,100,115,129` (records `Stores`/`Indexing`/`Queries`/`Semantic`) ; 35 accesseurs publics (comptage regex) ; `ProviderPlatformService.java:29` (`defaults`) | — |
| 0046 | Snapshot V3 (UTF-8), repli V2, lecture des anciens | Accepted | IMPLÉMENTÉE | `minos-storage-local/.../KnowledgeSnapshotCodecs.java:36-42,45-50` (`select` V3 sinon V2 ; `forVersion` 1/2/3) ; `SnapshotCodecV3.java` ; `SnapshotBinaryCodecSupport.java:72` | — |
| 0047 | Snapshot mémoire : dédoublonnage, table de chaînes, pagination | **Proposed** | NON IMPLÉMENTÉE | Aucun codec V4, aucun `intern(`/table de chaînes dans `minos-storage-local` ; `KnowledgeSnapshotCodecs.java:49` s'arrête à la version 3 | Conforme : reste proposé |
| 0048 | Évaluation comparative reproductible | **Proposed** | NON IMPLÉMENTÉE | Seul `SemanticSearchEvaluator.java` (Recall/MRR M23, ADR-0031) ; aucun protocole commun / harnais NOT_RUN/NOT_SUPPORTED ; `benchmarks/` = `m0`, `scalability` | Conforme |
| 0049 | Retrieval hybride explicable (BM25, RRF) | **Proposed** | NON IMPLÉMENTÉE | Grep `bm25\|reciprocal rank fusion\|RRF` : aucun code de ranking (seule `reciprocalRank` du MRR) | Conforme |
| 0050 | Unités de recherche et contexte budgété | **Proposed** | NON IMPLÉMENTÉE | Contexte actuel = `HybridContextBuilder` / `CodeSearchService` (ADR-0011/0029) ; aucune notion d'unités/générations versionnées | Conforme |
| 0051 | Provider d'embeddings CPU local | **Proposed** | NON IMPLÉMENTÉE | Providers présents : `LocalHashEmbeddingProvider`, `OllamaEmbeddingProvider` seulement ; aucun runtime ONNX/DJL/sidecar | Conforme. Texte « Spiker implémentation native… » probablement coquille de « Spike » (non vérifiable) |
| 0052 | Fraîcheur hors MCP | **Proposed** | NON IMPLÉMENTÉE | Aucun `WatchService`/mode watch dans le code de prod | Conforme |
| 0053 | Profils MCP progressifs | **Proposed** | NON IMPLÉMENTÉE | `MinosMcpTools.java:83-144` : catalogue unique de 31 tools, pas de profil | Conforme |
| 0054 | Bridge IntelliJ symbolique | **Proposed** | NON IMPLÉMENTÉE | Aucune occurrence de « bridge » dans `minos-intellij/src` | Conforme |
| 0055 | Un module `minos-storage` | Accepted — à implémenter | NON IMPLÉMENTÉE | `pom.xml:27,31` : toujours `minos-storage-local` et `minos-storage-postgresql` ; pas de répertoire `minos-storage/` ; `minos-storage-postgresql/pom.xml:31` dépend de `minos-storage-local` ; `PostgresCodeKnowledgeSnapshotStore.java:8-9` importe `com.minos.storage.local.store.*` | SH-02/SH-03 « À faire » |
| 0056 | Port de lecture neutre (`CodeKnowledgeReader`) | Accepted — à implémenter | NON IMPLÉMENTÉE | Grep `CodeKnowledgeReader` : 0 résultat ; `SnapshotQueryView.java:14,26` expose `InMemoryCodeKnowledgeStore` et son `IndexMetrics` | SH-04 « À faire » |
| 0057 | Finaliser les frontières hexagonales | Accepted — travaux résiduels | PARTIELLEMENT IMPLÉMENTÉE | Préservation tenue (bootstrap existant, `check-module-boundaries.py` OK) ; résidus toujours présents : `minos-cli/.../LocalAutonomousIndexOperations.java`, `MinosApplicationRuntimeConfiguration.java:34-44` instancie `OllamaEmbeddingProvider`, `engine/io/DurableAtomicFile.java` (I/O concret dans engine), `ScipProjectSnapshotLifecycle.java:50` (`staged-snapshots` local) | SH-05 à SH-10 « À faire » |

---

## 2. Incohérences documentaires

Aucune incohérence de priorité P0 ou P1 n'a été trouvée : aucune ne masque une garantie de sécurité fausse (les affirmations sandbox/`remote index` de README, STATUS et ROADMAP concordent avec le code, voir section « affirmations vérifiées » ci-dessous).

### G-01 — En-têtes de date de STATUS/ROADMAP/architecture inconsistants avec leur contenu
- Qualification : incohérence interne de documentation.
- Priorité : P2.
- Preuves : `docs/STATUS.md:3` « Dernière mise à jour : 31 août 2026 » ; `docs/STATUS.md:7` section « Planification additionnelle — 2026-10-05 » ; `docs/ROADMAP.md:3` « Statut au 31 août 2026 » ; `docs/ROADMAP.md` contient « Programme proposé … (4 octobre 2026) » ; `docs/architecture/README.md:5` « Dernière mise à jour : 2026-08-31 » alors que les ADR 0042-0046 (sept. 2026) ont modifié l'architecture.
- Comportement actuel : l'en-tête date l'état au 31 août alors que des éléments d'octobre (ADR 0055-0057, étude 0048-0054) y figurent ; STATUS ne dit pas à quelle date l'état « produit » a été revérifié.
- Attendu : une date de mise à jour cohérente (ou deux dates explicites : état produit / planification). Source : le propre contrat de STATUS (« synthèse autoritative de l'état produit courant », `STATUS.md:5`).
- Correction proposée : mettre à jour l'en-tête à la date réelle de dernière réconciliation ou séparer explicitement « état produit au 31 août » et « planification au 5 octobre ».

### G-02 — ADR-0036 : « Proposed » dans l'index et arc42, « Accepted » dans son fichier, et implémenté
- Qualification : statut incohérent (Proposed mais implémenté).
- Priorité : P2.
- Preuves : `docs/adr/README.md` ligne ADR 0036 « Proposed » ; `docs/architecture/arc42/09-decisions.md` (ligne ADR-0036 « Proposed », date 2026-07-30) et `arc42/11-risques-dette.md:3` « ADR-0036 (Proposed) » ; `docs/adr/0036-…md` « Status: Accepted », « Date: 2026-07-31 » et section Validation « This ADR is Accepted » ; `git log -- docs/adr/0036*` : commit `cd87f402` (2026-08-09) « docs: accept ADR-0036 after OS sandbox qualification » ; code : `FingerprintConstrainedJavaProgramGraphProvider.java:36`, `WorkerSandboxQualification.java:119`, `HostedProductionBoundary.java:47`.
- Comportement actuel : trois sources disent « Proposed », le fichier source et son historique git disent « Accepted ».
- Attendu : un seul statut, celui du fichier ADR (source de vérité, `docs/adr/README.md` « Règle de rédaction »).
- Correction proposée : passer l'index et arc42/09 et 11 à « Accepted ».

### G-03 — ADR-0021 : en-tête « Accepted » alors que l'index, arc42 et ADR-0037 le disent partiellement supersédé
- Qualification : statut non propagé dans le fichier.
- Priorité : P2.
- Preuves : `docs/adr/0021-…md` en-tête « Statut : Accepted » et aucun lien vers 0037 (Grep `0037` dans le fichier : 0 occurrence) ; `docs/adr/README.md` « Partially superseded by ADR-0037 » ; `docs/adr/0037-…md` § « Relation avec ADR-0021 » (« supersède partiellement ») ; `docs/architecture/arc42/11-risques-dette.md:4` cite encore « ADR-0021 (Docker autonomy) » comme preuve de risque alors que 0021 affirme l'inverse (Docker non autonome).
- Comportement actuel : un lecteur de 0021 ne voit pas qu'il est partiellement supersédé ; arc42/11 cite 0021 pour la « Docker autonomy ».
- Attendu : bandeau « Partiellement remplacé par ADR-0037 » en tête de 0021 (comme 0004 le fait pour 0005).
- Correction proposée : ajouter le bandeau dans 0021 ; corriger la citation d'arc42/11 (citer 0037).

### G-04 — ADR-0037 : « parité Docker non acquise » alors que M29 est clos
- Qualification : statut potentiellement obsolète (à confirmer par le propriétaire).
- Priorité : P3.
- Preuves : `docs/adr/0037-…md` statut « parité Docker non acquise » et lignes 99-100 ; index « Accepted — parity pending » ; `docs/STATUS.md` « M29 issue #107 CLOSED / PR #108 intégrée » ; `docs/architecture/risks/register.md` R-01 « Résolu par M29 / PR #108 » ; `arc42/11-risques-dette.md` R-01 CLOSED ; `README.md` « backend Docker autonome M29 ». Le code du contrat S1 est bien implémenté (voir tableau).
- Comportement actuel : la parité est déclarée « pending » dans l'ADR et l'index, mais « résolue » dans STATUS/registre des risques.
- Attendu : cohérence. Source : `docs/STATUS.md` (autoritatif). Je ne peux pas établir, depuis le code seul, que la parité fonctionnelle est complète (c'est le sujet de M29-S2…S8), seulement que les documents divergent.
- Correction proposée : le propriétaire tranche ; si la parité est acquise, annoter 0037 (« parité acquise par M29, voir STATUS ») sans réécrire sa décision.

### G-05 — ADR-0022 : liste de modules périmée, amendements 0042/0044/0055 non annotés
- Qualification : ADR amendé sans bandeau.
- Priorité : P3.
- Preuves : `docs/adr/0022-…md:17-37` « 12 modules enfants + le parent = 13 projets » ; `pom.xml:24-37` : 14 modules (ajoute `minos-bootstrap`, `minos-storage-postgresql`) ; `docs/adr/0044-…md` § « Amendement de l'ADR 0022 » ; `0055` « Amende uniquement la partition des artefacts de stockage de l'ADR 0022 » ; Grep `0042\|0044\|0055\|amend` dans 0022 : 0 résultat.
- Attendu : 0022 renvoie à ses amendements. Source : `docs/adr/0044-…md` (amendement explicite).
- Correction proposée : ajouter un bandeau « amendé par 0042, 0044 (et 0055 à venir) » dans 0022 et l'index.

### G-06 — ADR-0033 et ADR-0038 : amendement par ADR-0041 non reflété
- Qualification : ADR partiellement vidé de sa portée sans renvoi.
- Priorité : P3.
- Preuves : `docs/adr/0033-…md` (« M25 doit permettre l'indexation distante ») sans renvoi vers 0041 ; `LocalRemoteIndexOperations.java:116` refuse avant matérialisation ; `docs/adr/0038-…md` ligne Status « Amendé par 0041 sur la §4 » mais l'index n'en fait pas état (« Accepted »).
- Attendu : 0033 mentionne que l'exécution distante non fiable est fermée par 0041 ; l'index signale l'amendement de 0038.
- Correction proposée : bandeaux de renvoi (sans modifier les décisions).

### G-07 — ADR-0031 §2 « loopback only » contredit par l'endpoint Docker managé
- Qualification : ADR contredit par le code sur un point.
- Priorité : P2.
- Preuves : `OllamaEmbeddingProvider.java:23-27` (Javadoc : « loopback … and the fixed minos-ollama service name ») ; `:31` `MANAGED_DOCKER_ENDPOINT = http://minos-ollama:11434/api/embed` ; `:112` message d'erreur ; ADR-0031 point 2 « accepts loopback endpoints only ».
- Comportement actuel : un hôte non-loopback fixe (`minos-ollama`) est accepté (jalon M30, cf. `docs/roadmap/M30_EXECUTION.md`).
- Attendu : l'ADR décrit le comportement ou est amendé. Je n'infère pas la raison historique ; la seule source disponible est le Javadoc ci-dessus.
- Correction proposée : amender 0031 (ou nouvel ADR) pour acter l'exception `minos-ollama`.

### G-08 — Backend PostgreSQL/pgvector livré sans ADR, en tension avec 0025 et 0031 §8
- Qualification : décision structurante sans ADR.
- Priorité : P2.
- Preuves : `docs/adr/0025-…md` § Décision (« le backend de production reste fichiers… nouvelle décision ADR avant intégration runtime ») ; `docs/adr/0031-…md` point 8 (« no … vector database ») ; code : `minos-storage-postgresql/…/PostgresStorageBackend.java`, `PostgresSemanticReadQueries.java:21,90` ; `minos-bootstrap/.../StorageBackendSelection.java` (backend sélectionnable par SPI) ; Grep `postgres` dans `docs/adr/` : seules mentions incidentes (0036 ligne 169, 0042-0047, 0055). Les raisons et la portée sont dans `docs/roadmap/M30_EXECUTION.md`, pas dans un ADR.
- Comportement actuel : PostgreSQL/pgvector est optionnel et fail-closed ; le défaut reste `local`. Aucun `hnsw`/`ivfflat` dans le code : le balayage reste exact.
- Attendu : un ADR (ou un amendement de 0025) ratifiant le backend optionnel. Source : règle de 0025.
- Correction proposée : rédiger un ADR « backend PostgreSQL/pgvector optionnel » (ou renvoi explicite dans 0025/0055).

### G-09 — `arc42/09-decisions.md` arrêté à ADR-0037 ; dates divergentes ; SYNTHESE à « 37 ADR »
- Qualification : index dupliqué non maintenu.
- Priorité : P2.
- Preuves : `docs/architecture/arc42/09-decisions.md` : 48 lignes, dernière ligne ADR-0037, donc 20 ADR (0038-0057) absents ; `docs/architecture/SYNTHESE.md:22` « 37 ADR … jusqu'à M29 » ; `docs/architecture/adr/README.md` étape 3 impose d'ajouter chaque ADR à `arc42/09-decisions.md` ; dates : arc42/09 donne 0008 → 2026-07-23, 0009-0012 → 2026-07-23, 0020/0021 → 2026-07-25, 0025 → 2026-07-27, 0036 → 2026-07-30 alors que les fichiers ADR portent respectivement 22 juillet, 24 juillet, 24 juillet, 2026-07-26, 2026-07-31.
- Attendu : arc42/09 aligné sur `docs/adr/README.md`. Source : `docs/architecture/adr/README.md`.
- Correction proposée : régénérer arc42/09 depuis l'index ADR (ou le réduire à un renvoi, puisqu'il dit « Ne pas reproduire les ADR ici »).

### G-10 — Note « PR #333 non fusionnée » périmée
- Qualification : affirmation datée.
- Priorité : P3.
- Preuves : `docs/adr/README.md:75` « PR #333, non fusionnée lors de cette rédaction » ; `docs/roadmap/storage-hexagonal-2026-10/README.md` (« La PR #333 propose les ADR 0048–0054… leur absence dans develop ») ; `git log` : `bc1d3421 Merge pull request #333 from FTurleque/docs/etude-evolution-semble-serena`.
- Attendu : formulation à l'état actuel (fusionnée).
- Correction proposée : reformuler les deux phrases.

### G-11 — Maven Wrapper annoncé 3.9.16 ; le dépôt est en 3.10.0
- Qualification : affirmation contredite par la config.
- Priorité : P2.
- Preuves : `docs/STATUS.md:121` « Maven Wrapper : Maven 3.9.16 avec checksum SHA-256 » ; `docs/ROADMAP.md:114` « wrapper Maven 3.9.16 avec checksum » ; `docs/TOOLCHAIN_POLICY.md` « Maven : 3.9.x » ; ADR-0004/0005 « 3.9.x » ; `.mvn/wrapper/maven-wrapper.properties` (au HEAD, `git show HEAD:`) : `distributionUrl=…/apache-maven-3.10.0-bin.zip` ; `git log` : `84cf6d3f build(deps): bump org.apache.maven:apache-maven` ; `pom.xml:125` `requireMavenVersion [3.9,4.0)`.
- Comportement actuel : le build s'exécute en 3.10.0 (accepté par la plage `[3.9,4.0)`).
- Attendu : docs alignés sur le wrapper. Source : `.mvn/wrapper/maven-wrapper.properties`.
- Correction proposée : corriger STATUS/ROADMAP (« wrapper 3.10.0 ») et reformuler « 3.9.x » en « 3.x dans [3.9,4.0) » dans TOOLCHAIN_POLICY ; décider si 3.10 est une évolution de baseline qualifiée (la politique exige une qualification explicite des changements de baseline).

### G-12 — ROADMAP : « ligne de développement courante 1.1.0-SNAPSHOT »
- Qualification : contradiction interne.
- Priorité : P2.
- Preuves : `docs/ROADMAP.md:131` « La ligne de développement courante est 1.1.0-SNAPSHOT. Aucune release 1.1.0 n'est publiée » ; `docs/ROADMAP.md:3` (1.0.1, 1.1.0, 1.2.0 publiées, 1.3.0-SNAPSHOT ouverte) ; `pom.xml:42` `revision=1.3.0-SNAPSHOT` ; `git ls-remote --tags origin` : `v1.1.0 → b2ba3ac9…`, `v1.2.0 → 730b7600…` ; `docs/STATUS.md:21`.
- Attendu : 1.3.0-SNAPSHOT. Source : `pom.xml:42`.
- Correction proposée : supprimer ou corriger le paragraphe (section « Release 1.0.1 — publiée »).

### G-13 — ROADMAP : ADR-0039 listé en « conception proposée »
- Qualification : affirmation périmée.
- Priorité : P2.
- Preuves : `docs/ROADMAP.md:139` (« conception proposée ») ; `docs/adr/0039-…md` Status « Accepted — implémenté au sprint 2 » ; code : `LocalAutonomousIndexOperations.java:187`, `IndexingResumePlanner.java:35`, `MinosApi.java:249`.
- Attendu : « implémentée ». Source : ADR-0039 et code.
- Correction proposée : déplacer la ligne ADR 0039 hors de « Travaux ouverts en conception » (la ligne ADR 0040 dit déjà « implémentée »).

### G-14 — ROADMAP : « Post-228 Hardening Invariants … exécute » alors que ce workflow n'existe plus
- Qualification : affirmation contredite par la CI.
- Priorité : P2.
- Preuves : `docs/ROADMAP.md:82` ; `ls .github/workflows` : aucun `post-228-hardening.yml` (ni `mnd`/`mne`/`post-mne`) ; `.github/workflows/pr-ci.yml:37` (mention du workflow supprimé) ; `docs/STATUS.md` (« ont été retirés ») ; `docs/audit/archive/2026-09/AUDIT-2026-09.md:234` (C1 : supprimés, fusionnés dans le job `invariants`).
- Attendu : le paragraphe décrit le job `invariants` de `pr-ci.yml`. Source : STATUS.
- Correction proposée : réécrire la sous-section « Post-228 » de ROADMAP comme dans STATUS.

### G-15 — STATUS : « deux jobs » alors que `pr-ci.yml` en compte trois
- Qualification : affirmation inexacte mineure.
- Priorité : P3.
- Preuves : `docs/STATUS.md:77` « avec deux jobs qui tournent en parallèle » ; `.github/workflows/pr-ci.yml:19` (`vulnerability-scan`, OSV), `:39` (`invariants`), `:154` (`verify`).
- Attendu : trois jobs (OSV, invariants, verify). Source : `pr-ci.yml`.
- Correction proposée : ajouter `vulnerability-scan`.

### G-16 — README racine : état arrêté à 1.0.1 ; `minos-app` « composition root »
- Qualification : état périmé et affirmation contredite par ADR-0042.
- Priorité : P2.
- Preuves : `README.md:7-17` (bloc « État courant », dernière release 1.0.1) vs `docs/STATUS.md` (1.1.0 le 27 août, 1.2.0 le 31 août) et tags distants ; `README.md:54` « avec `minos-app` comme composition root » vs `docs/adr/0042-…md` § Recommandation (racine dans `minos-bootstrap`) et `minos-bootstrap/.../DefaultMinosApplicationComposer.java` (`minos-app` ne contient que 7 fichiers : routeur MCP, transport Docker, bridge NEXUS).
- Attendu : mention de 1.1.0/1.2.0 ou renvoi à STATUS ; composition root = `minos-bootstrap`.
- Correction proposée : adapter le bloc d'état et la phrase d'architecture.

### G-17 — Docs d'architecture/ADR avec références de modules périmées
- Qualification : affirmations périmées (modules, composition root, emplacement de classes).
- Priorité : P2.
- Preuves : `docs/architecture/arc42/02-contraintes.md:14` « CT-3 : 12 modules enfants + parent » (pom : 14) ; `arc42/04-strategie-solution.md:41` « minos-app ← composition root » et liste de 12 modules sans `minos-bootstrap` ni `minos-storage-postgresql` ; `docs/architecture/SYNTHESE.md:11,19` « 13 projets (12 enfants + parent) », « minos-app est le seul composition root » ; `arc42/05-vue-blocs.md` : 0 occurrence de `minos-bootstrap` ; ADR-0039 « Écarts (f) » : `IndexingResumePlanner` « (`minos-application`) » alors qu'il est dans `minos-engine` (`minos-engine/…/orchestration/IndexingResumePlanner.java:35`) ; ADR-0023 ne mentionne pas V3 (ADR-0046).
- Attendu : 14 modules enfants + parent ; racine = `minos-bootstrap`. Source : `pom.xml:24-37`, ADR-0042.
- Correction proposée : mettre à jour arc42/02, 04, 05 et SYNTHESE ; corriger la mention de module dans ADR-0039.

### G-18 — Liens morts
- Qualification : liens documentaires cassés.
- Priorité : P3.
- Preuves (script de contrôle des liens `.md`, hors `history/`) : `docs/audit/archive/2026-09/AUDIT-2026-09.md:249` → `../adr/0045-surfaces-d-objet.md` (le fichier réel est `0045-constructeur-unique-et-point-d-entree-nomme.md`) ; `docs/audit/archive/2026-09/PROMPT-FIABILITE.md` et `PROMPT-SPRINT-2-3-RESTANT.md` → liens `docs/adr/…` relatifs invalides depuis leur répertoire ; `docs/architecture/diagrams/README.md` : 7 fichiers référencés absents (`c4-context.md`, `c4-container.md`, `c4-component-application.md`, `seq-indexation-nominale.md`, `seq-erreur-provider.md`, `seq-mcp-startup.md`, `deployment-native.md`). Les liens de `README.md`, `docs/README.md` et de l'index ADR vers des fichiers existent tous.
- Correction proposée : corriger les liens ; l'ADR 0045 a manifestement changé de nom (titre original « surfaces d'objet » : déduction du nom de fichier du lien, pas de la décision).

### G-19 — STATUS/ROADMAP : « snapshots v1/v2 plafond 256 MiB » sans V3
- Qualification : omission après ADR-0046.
- Priorité : P3.
- Preuves : `docs/STATUS.md:114` et `docs/ROADMAP.md:58` ; `SnapshotBinaryCodecSupport.java:84` (`MAX_PERSISTED_SNAPSHOT_BYTES = 256 MiB`, commun) ; `KnowledgeSnapshotCodecs.java:36-42` (nouveaux snapshots écrits en V3).
- Correction proposée : « snapshots v1/v2/v3 ».

### G-20 — Index ADR : titres divergents et vocabulaire de statut
- Qualification : dérive cosmétique de l'index.
- Priorité : P3.
- Preuves : script de comparaison : 29 titres de l'index sur 57 diffèrent du H1 du fichier (par exemple 0001 et 0002 en anglais dans l'index, en français dans le fichier ; 0055 « Un module minos-storage… » vs « Réunir les adaptateurs dans minos-storage ») ; `docs/adr/README.md` « Statuts » ne définit que Proposed/Accepted/Superseded/Rejected alors que l'index emploie « Partially superseded », « Accepted — parity pending », « Accepted — à implémenter » ; en-têtes ADR en FR (« Acceptée »/« Accepté ») ou EN selon le fichier.
- Correction proposée : normaliser au besoin ; définir « Partially superseded » dans le vocabulaire.

### G-21 — Tag local `v1.0.1` différent du tag distant
- Qualification : état de l'environnement local, pas un défaut de la doc.
- Priorité : P3.
- Preuves : `git rev-parse v1.0.1^{commit}` = `2de847bd…` (commit du 4 août « feat(m30) … (v1.0.1) ») ; `git ls-remote --tags origin v1.0.1` = `f762025d…` (valeur documentée dans README, STATUS, ROADMAP, `docs/releases/1.0.1.md`).
- Comportement actuel : les docs sont exactes vis-à-vis du distant ; le tag local est obsolète.
- Correction proposée : aucune dans le dépôt ; un `git fetch --tags --force` local alignerait (non exécuté : écriture).

### G-22 — `docs/audit/archive/2026-10-06/CAPACITES.md` (non suivi) : « 50 ADR »
- Qualification : fichier non versionné, brouillon d'audit.
- Priorité : P3.
- Preuves : `docs/audit/archive/2026-10-06/CAPACITES.md` « les 50 ADR de docs/adr/ » ; le dépôt en compte 57 (`docs/adr/0001…0057`).
- Correction proposée : corriger avant commit.

---

## 3. Vérification d'un échantillon de constats clos

Source : `docs/audit/archive/2026-09/AUDIT-2026-09.md` (tableau §2 et sections de suivi) et ADR liés. 16 constats vérifiés au HEAD.

| Constat | Déclaré | Vérification au HEAD | Verdict |
|---|---|---|---|
| S1 (ADMIN → OWNER) | fermé (`canGovern`) | `engine/hosted/HostedMembershipService.java:50-51,85` : `canGovern` sur le rôle cible et le rôle préexistant | Clôture vérifiée |
| S4 (mot de passe PostgreSQL dans `toString`) | fermé | `engine/storage/StorageBackendConfiguration.java:38,124,135` (`REDACTED_POSTGRES_URL`, `safePostgresUrl`) | Vérifiée |
| S9 (variable de credential libre) | fermé (liste blanche par hôte) | `engine/remote/RemoteRepositoryRequest.java:189-190` : GITHUB → `{MINOS_GITHUB_TOKEN, GITHUB_TOKEN, GH_TOKEN}`, GITLAB → `{MINOS_GITLAB_TOKEN, GITLAB_TOKEN}` | Vérifiée |
| S3 (`reclaimStaleJobs` tue un autre MINOS) | fermé avec réserve | `minos-runtime-local/.../CgroupJobOwnership.java:42,60` (marque `.own-<pid>-…`) | Vérifiée (la réserve n'a pas été réexaminée) |
| S5 (I/O privé inégal) | fermé (gate `check-private-io.py`, liste blanche à cliquet) | `scripts/architecture/private-io-allowlist.json` : 12 entrées ; exécution : « PRIVATE I/O PRIMITIVES GATE SUCCESS (forbidden=8, allowlisted-occurrences=37) » | Vérifiée |
| Q1 (`team` mute avant rejet d'options inconnues) | fermé (« 17 `rejectUnknown` ») | Grep `rejectUnknown` dans `minos-cli/src/main` : 0 résultat. Le mécanisme a été remplacé (Q19/Q11) : `TeamCommand.java:63-68,120-125` + `CliOptions.java` analysent toutes les options avant le premier appel de service ; tests `TeamCommandDispatchGuardTest`, `TeamOperationGuardTest` présents | Clôture vérifiée sur le fond ; la phrase de preuve (§5 ligne 203, « 17 `rejectUnknown` ») est périmée (voir ligne suivante) |
| Q19 / Q11 | fermé (plus de 17 répétitions ; `CliOptions` unique) | `minos-cli/.../CliOptions.java:39` | Vérifiée |
| Q10 (`LazyAutonomousIndexOperations` supprimé) | fermé | `find . -name 'LazyAutonomousIndexOperations*'` : 0 fichier ; `LazyApplication.java:27` (Q21) | Vérifiée |
| Q13 (`Preconditions` dans domain) | fermé | `minos-domain/.../Preconditions.java:10` | Vérifiée |
| A1 (indexation distante) | fermé par décision (0041) | voir ADR-0041 ci-dessus (`WorkerSandboxQualification.java:28`, `LocalRemoteIndexOperations.java:116`, `DoctorCommand.java:167`) | Vérifiée |
| A2 (application couplée aux adaptateurs) | fermé (0042) | `minos-application/pom.xml:23,28` : seulement domain + engine ; `minos-storage-postgresql/pom.xml` : pas de dépendance à `minos-application` | Vérifiée |
| A3 (packages éclatés) | fermé (0044) | `check-module-boundaries.py` : « packages=45, each owned by exactly one module » ; recalcul indépendant | Vérifiée (main seulement) |
| A4 (constructeurs télescopiques) | fermé (« ADR 0045 », lien `0045-surfaces-d-objet.md`) | `LocalProjectArchitectureQuery.java:33,36` ; `MinosCli.java:98,103` ; mais le lien ADR est mort | Clôture vérifiée ; lien cassé (G-18) |
| A6 (snapshot V3) | fermé avec réserve | `KnowledgeSnapshotCodecs.java:36-50` | Vérifiée |
| A7 (liste de modules du gate vs POM) | fermé | `check-module-boundaries.py` : « reactor=root-pom-modules … modules=14 » | Vérifiée |
| C1 (4 workflows supprimés) / C2 (M19, M20) | fermé | `ls .github/workflows` : ni `mnd/mne/post-mne/post-228` ni workflows M19/M20 ; `scripts/quality/check-single-execution.py` présent | Vérifiée (voir G-14 pour la doc ROADMAP) |
| R1 (reprise après interruption) | fermé (0039) | `IndexingRun.Status.INTERRUPTED` (`LocalAutonomousIndexOperations.java:187`), `IndexingResumePlanner.java` | Vérifiée |
| G3 (artefacts de jalon) | fermé (0043) | `check-milestone-artifact-references.py` : SUCCESS (117 scripts) ; `scripts/history/` | Vérifiée |
| D1 (indexeurs embarqués) | fermé avec réserve | `embedded-tools.json`, `EmbeddedToolsPayload.java:28`, `check-tools-manifest.py` | Vérifiée ; la réserve (§3 non réalisé) est dans l'ADR-0040 |
| Q4 / Q9 | « non reproduit » | non vérifiés (pas des clôtures) | Hors périmètre |
| T1 | ouvert | non vérifié | Hors périmètre |

Aucun constat de l'échantillon n'est déclaré clos et contredit par le code. Un seul a une phrase de preuve périmée (Q1).

### Affirmations de STATUS/ROADMAP/README vérifiées exactes (extraits)
- Tags publiés : `git ls-remote --tags origin` : `v1.0.1 → f762025d…`, `v1.1.0 → b2ba3ac9…`, `v1.2.0 → 730b7600…`, identiques à `README.md`, `STATUS.md`, `ROADMAP.md`.
- Version de développement 1.3.0-SNAPSHOT : `pom.xml:42` (STATUS.md:21, ROADMAP.md:3).
- Backends sandbox et refus : `LinuxBubblewrapWorkerSandboxBackend.java:38`, `WindowsAppContainerWorkerSandboxBackend.java:54`, `WorkerSandboxQualification.java:28`, test `WorkerResourceContainmentTest.java:81` (README « Sandbox worker OS »), `supportsManagedLocalProvider` présent dans le code.
- Toolchains : Gradle 9.6.1 (`.github/workflows/intellij-plugin.yml:54`), IntelliJ Platform 2026.1 (`minos-intellij/build.gradle.kts:25`), Java 21 plugin, Dependabot Maven + Gradle `/minos-intellij` + GitHub Actions (+ docker) (`.github/dependabot.yml`).
- Docker MCP local : base `eclipse-temurin:24.0.2_12-jre@sha256:…` (`docker/Dockerfile.mcp:4`).
- Hosted : AES-256-GCM avec AAD (`FileHostedControlPlaneStore.java:247,249`).
- SonarCloud : scaffold derrière `vars.SONAR_CI_ANALYSIS_ENABLED` (`pr-ci.yml:252`).
- Embarquement du JRE par jpackage/jdeps (README « Runtime Windows corrigé ») : `scripts/release/build-windows-distribution.ps1:156,239`.
- Sélecteurs d'embeddings `disabled | local-hash | ollama` : `MinosApplicationRuntimeConfiguration.java:28-34`.
- Liens du README, de `docs/README.md` et de l'index ADR : tous existent.

### (b) ADR dont l'implémentation reste à faire d'après `docs/roadmap/storage-hexagonal-2026-10/`
Trois ADR : 0055 (module `minos-storage`), 0056 (port de lecture neutre), 0057 (frontières hexagonales résiduelles). `TASKS.md` : 12 tâches SH-01 à SH-12, toutes « À faire » (compte `État : À faire` = 12) ; `git log | grep SH-` : aucun commit. Les états « résiduels » cités par le backlog sont confirmés au HEAD : deux modules de stockage distincts (`pom.xml:27,31`), PostgreSQL → `storage-local.store.*` (`PostgresCodeKnowledgeSnapshotStore.java:8-9`), `SnapshotQueryView.queryStore` concret (`SnapshotQueryView.java:14`), `LocalAutonomousIndexOperations` dans la CLI, `OllamaEmbeddingProvider` instancié par la configuration applicative, staging local dans `ScipProjectSnapshotLifecycle.java:50`. Hors de ce dossier, les ADR 0047-0054 (Proposed) restent également à implémenter (programme U0-U8, 35 tâches, `docs/research/minos-evolution-2026-10/ROADMAP.md`).

---

## 4. Ce que je n'ai PAS examiné

- Le contenu intégral de chaque ADR (j'ai lu les en-têtes et la section Décision, sauf 0055-0057 lus en entier) ; les sections Conséquences/Alternatives/Validation n'ont pas été confrontées au code, sauf 0036, 0037, 0039, 0040, 0041.
- Les affirmations d'ADR sur des mesures, SHA, numéros de PR/issue, résultats CI Windows/Linux, et l'état réel des PR/issues GitHub (`gh` non utilisé) : non vérifiables depuis le code.
- Les tests (src/test) : ADR-0044 (« en production comme en test ») n'est vérifié que sur `src/main` ; la couverture des garanties par des tests n'est pas auditée.
- `docs/audit/*-SUIVI.md` (≈ 900 Ko) et `docs/roadmap/M*_EXECUTION.md` : seul l'échantillon de la section 3 a été confronté au code ; les autres constats « clos » ne sont pas examinés (surtout FIAB, SEC, SPRINT-2, S23).
- `docs/user/**`, `docs/developer/**`, `docs/history/**`, `docs/architecture/risks`, `quality/`, arc42 01/03/06/07/08/10/12 : seulement les points cités.
- Le comportement à l'exécution (aucun build, aucun test, `minos` MCP en panne) ; les gates CI ne couvrent pas les affirmations relevées ci-dessus (`check-current-docs.py` passe malgré G-11 à G-17).
- Les Javadoc : une recherche de `ADR-0004`/`ADR-0021` dans les sources Java et Kotlin ne retourne aucune citation ; les citations d'autres ADR dans les Javadoc n'ont pas été auditées.
- `minos-intellij` (Gradle) : structure et dépendances seulement.
- Je n'ai pas modifié le dépôt ; l'exécution de `product-facts.py` rewrite `docs/generated/product-facts.md` sans diff constaté (`git status` propre hors `Claude outputs/` et `docs/audit/archive/2026-10-06/CAPACITES.md`).
