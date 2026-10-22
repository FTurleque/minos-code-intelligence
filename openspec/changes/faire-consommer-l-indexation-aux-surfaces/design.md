# Design

## Context

Constat **AUD-ARC-01** du sprint 10 de l'audit du 10 octobre 2026 (suite de SH-05, puis SH-09 du chantier `docs/project/roadmap/storage-hexagonal-2026-10/`). HEAD analysé : `6b60790e` (`develop`, après la PR #534), contre-vérifié au HEAD `5ce7b79b` (`develop` du 2026-10-22, PR #538) : `git diff --stat 6b60790e 5ce7b79b -- '*.java' scripts/` est vide, les numéros de ligne ci-dessous valent pour les deux. Les ADR applicables sont 0057 § 6 (« faire consommer [les] résultats [des cas d'usage] par API/CLI/MCP/NEXUS ; conserver les contrats publics ») et 0058 § 3 (les usages du moteur par les surfaces sont une dette à résorber). La règle A11 tient cette dette ; chaque lot livré en retire des lignes.

## État vérifié au HEAD

### La table A11

`scripts/architecture/check-module-boundaries.py:509-578` : `ENGINE_TYPES_NAMED_BY_SURFACE`, **60 entrées** (CLI 33, API 16, NEXUS 6, MCP 5), noms relatifs à `com.minos`. L'auto-test compare les décomptes à la table elle-même (`scripts/architecture/test_check_module_boundaries.py:1024-1027`) : aucun nombre n'y est écrit en dur.

### Usages, fichier par fichier (relevé par script sur les sources de production)

| Fichier | Types du moteur nommés |
|---|---|
| `minos-cli/.../operations/LocalAutonomousIndexOperations.java` | `IndexStateStore`, `IndexingResumePolicy`, `IndexingRun`, `IndexingRuntimePorts`, `ResumableRunSummary`, `ProviderRuntimeManager`, `ProviderRuntimeStatus` |
| `minos-cli/.../operations/LocalRemoteIndexOperations.java` | `IndexerDescriptor`, `IndexingRuntimePorts`, `ProjectRegistry`, `RegisteredProject`, `DistributedIndexing`, `IdempotentRemoteRepositoryMaterializer`, `RemoteIndexingRuntime`, `RemoteRepositoryMaterializer`, `RemoteRepositoryRequest`, `WorkerSandboxProbe` |
| `minos-cli/.../operations/RemoteIndexLease.java` | `BoundedFileLease`, `PrivateLocalStorage`, `Sha256` |
| `minos-cli/.../operations/AutonomousIndexOperations.java` | `IndexingMode`, `IndexingResumePolicy` |
| `minos-cli/.../operations/RemoteIndexOperations.java`, `command/RemoteIndexCommand.java` | `DistributedIndexing`, `RemoteRepositoryRequest` |
| `minos-cli/.../operations/IndexResumeStatusSource.java`, `command/ProjectCommand.java` | `ResumableRunSummary` (+ `UnreadableRegistryException` dans `ProjectCommand`) |
| `minos-cli/.../command/IndexCommand.java` | `IndexingResumePolicy` (`:269-270`) |
| `minos-cli/.../command/DoctorCommand.java` | `WorkerSandboxProbe` (`:101-103`), `PrivateLocalStorage` (`:250`) |
| `minos-cli/.../command/TeamCommand.java`, `MinosCli.java` | `HostedControlPlaneService`, `HostedRetentionPolicy`, `HostedRole` |
| `minos-cli/.../command/GitActivityCommand.java`, `MinosCli.java`, `MinosCliRunner.java` | `GitIntelligence` |
| `minos-cli/.../command/FindSymbolCommand.java`, `FindUsagesCommand.java`, `RelationshipCommand.java` | `SymbolResult`, `UsageResult`, `RelationshipResult` |
| `minos-cli/.../MinosLauncher.java`, `DockerRuntimeBootstrap.java`, `support/CliCommandSupport.java`, `command/RetrievalStatusCommand.java` | `MinosVersion`, `PublicErrorMessages`, `ProjectPathMapping`, `ProjectPathMappings`, `DegradedEntry`, `UnreadableRegistryException` |
| `minos-api/.../LocalMinosApi.java` | `ResumableRunSummary` (`:255-272`), `SymbolResult`, `UsageResult`, `RelationshipResult` (`:313-330`) |
| `minos-api/.../ProviderPlatformApi.java` | `ProviderConformanceKit` (`:3`, `:35` : seul appel, `sortedCopy`) |
| `minos-api/.../LocalMinosTeamApi.java` | 9 types de `com.minos.hosted` |
| `minos-api/.../LocalMinosMultiRepositoryApi.java`, `MinosApiSupport.java` | `GitIntelligence`, `PublicErrorMessages` |
| `minos-mcp/.../MinosApplicationMcpBackend.java` | `ResumableRunSummary` (`:122`), `HostedControlPlaneService` |
| `minos-mcp/.../MinosMcpTools.java`, `MinosMcpServer.java` | `PublicErrorMessages`, `UnreadableRegistryException`, `MinosVersion` |
| `minos-nexus/.../NexusExportService.java` | `ProjectRegistry`, `RegisteredProject`, `UnreadableRegistryException`, `CodeKnowledgeSnapshot`, `CodeKnowledgeSnapshotStore`, `Sha256` |

Faits relus :

- `LocalAutonomousIndexOperations.java:51-52` prend `application.indexStateStore()` et `application.providerRuntimeManager()` ; `:58-63` construit `ResumableRunSummary.of(stateStore, …)` ; `:76-91` projette `ProjectIndexingUseCase.Outcome` et `IndexingRun` ; `:93-97` liste et installe les runtimes de providers ; `:99-113` projette `Preparation` (projet enregistré, découverte, `ProviderRuntimeStatus`).
- `LocalRemoteIndexOperations.java` (254 lignes) **compose un cas d'usage** dans la surface : refus anticipé de l'ADR 0041 (`:102`, `:111-120`), bail inter-JVM (`:146`), enregistrement (`:148`), épinglage, décoration des exécuteurs par `RemoteIndexingRuntime.distribute`, exécution par `new LocalAutonomousIndexOperations(application, decorator)` (`:165`), preuves d'artefacts vérifiées, retour arrière (`:191`). `RemoteIndexLease.java` (52 lignes) est son bail.
- `LocalMinosApi.java:270-276` (appel `:272`) et `MinosApplicationMcpBackend.java:122` lisent l'état de reprise par `ResumableRunSummary.of(application.indexStateStore(), …)` ; le MCP le passe à `ProjectJson.indexStatus` (`minos-application/.../output/ProjectJson.java:54-72`), qui nomme lui-même `ResumableRunSummary`. Le MCP appelle `UUID.fromString(view.id())` sans rattraper l'exception, l'API et la CLI rendent une absence (`LocalMinosApi.java:273-275`, `LocalAutonomousIndexOperations.java:61-63`) ; l'identifiant vient du registre, toujours un UUID, aucune sortie ne diffère.
- `ProjectCommand.java:209` lit le statut par `IndexResumeStatusSource` et nomme `ResumableRunSummary` (`:8`) ; `ProjectJson.indexStatus` y est appelé (`:211`). `MinosCliRunner.java:128` branche ce statut sur `autonomousIndex.get().resumableRun(…)`, ce qui ouvre les opérations d'indexation.
- `ResumableRunSummary.of` (`minos-engine/.../ResumableRunSummary.java:40-54`) n'appelle que `findProjectState` et `findRun`. Avec le magasin de fichiers, ces lectures n'écrivent rien sauf la migration d'un run au format antérieur trouvé à l'ancien emplacement (`FileIndexStateStore.java:226-229`), que le constructeur a déjà migré à l'ouverture (`:78-88` : création des dossiers, `migrateLegacyRuns`, `migrateRunLocators`). La lecture est donc sans écriture **une fois l'application ouverte**, pas l'ouverture elle-même.
- `RemoteIndexCommand.java:69-71` construit et valide `RemoteRepositoryRequest.of(…)` **avant** d'appeler les opérations, que `MinosCliRunner.java:123-124` fournit par une poignée paresseuse (`LazyApplication`) : une requête invalide est refusée sans ouvrir l'application.
- `ProviderPlatformService` est déjà utilisé par le MCP (`MinosApplicationMcpBackend.java:84`) ; `MinosMcpReadOnlyHomeTest.java:55` vérifie qu'aucun outil de statut ou de structure n'interroge, ne liste ni n'installe un runtime (sous Windows, inspecter un runtime écrit un script de lancement, `:83-86`).
- `minos-application` ne dépend que de `minos-domain` et `minos-engine` (`minos-application/pom.xml`) ; `MinosApplication.open` y échoue faute de racine de composition (`MinosApplicationComposersTest.java:40`) : ses tests utilisent `MinosApplication.builder` et `IndexingFixture`, jamais le magasin de fichiers. `minos-cli`, `minos-api` et `minos-mcp` ont `minos-bootstrap` en test.
- Dépendances entre packages de l'application : `com.minos.application.indexing` importe `com.minos.application` (`ProjectIndexingUseCase` → `MinosApplication`, `ProjectIndexStateReconciler`) ; `com.minos.output` importe `com.minos.application` (`ProjectJson` → `ProjectSummary`). Un type rendu par `ProjectInspectionService` ou `ProviderPlatformService` (package `com.minos.application`) et rangé dans `application.indexing` fermerait un cycle, refusé par A8.
- `ProviderConformanceKit.sortedCopy` (`minos-engine/.../ProviderConformanceKit.java:73-79`) : copie dans un `TreeMap`, clés et valeurs non nulles, vue non modifiable.
- `IndexingResumePolicy` et `IndexingMode` sont utilisés par 8 classes de `minos-engine` (planificateur de reprise, cycle d'indexation) : ils ne peuvent pas monter dans l'application.
- Les signatures de `com.minos.api` ne nomment que `com.minos.api.*` et `MinosApplication` (`docs/user/java-api.md:334-335`) : les types du moteur ci-dessus sont des détails d'implémentation des surfaces, pas des contrats publics.

### Usages sans nom (angle mort du cliquet)

A11 compte des **noms** (import ou nom qualifié). Une surface peut utiliser un type du moteur sans le nommer, en chaînant un accesseur de `MinosApplication`. Relevé au HEAD : `indexStateStore()` (`LocalMinosApi`, `MinosApplicationMcpBackend`, `LocalAutonomousIndexOperations`), `providerRuntimeManager()`, `indexerDescriptors()`, `compositionRoot()` (`LocalRemoteIndexOperations`), `projectRegistry()` et `snapshotStore()` (`LocalMinosApi.java:97`, `MinosCliRunner.java:116`), `gitIntelligence()` et `hostedControlPlaneService()` (CLI, API, MCP). Ce lot retire les quatre premiers des surfaces ; les autres relèvent des lots suivants. Remplacer un type nommé par `var` ou par un chaînage n'est **pas** un retrait (exigence « Les familles de l'indexation ne sont plus nommées par les surfaces »).

### Écarts avec l'action proposée par l'audit

- L'audit comptait 103 références et 52 types pour la CLI ; AUD-ARC-02 en a retiré 19 (CLI 33 au HEAD). Le compte « 102 imports dans 30 fichiers » du suivi date d'avant SH-05 ; la mesure courante est la table A11.
- L'audit proposait de faire consommer des cas d'usage par **toutes** les surfaces d'un coup. Les familles n'ont pas les mêmes prérequis (SH-04 pour NEXUS, SH-08 pour le diagnostic et `hosted`) ni le même statut (résultats de requête, noyau transverse) : le travail est découpé en lots, ce changement livre le premier.
- `ProjectJson` (application) nomme `ResumableRunSummary` : ce n'est pas une surface, A11 ne le compte pas ; `indexStatus` prend désormais le résultat applicatif (D8) pour que les surfaces n'aient plus à produire ce type.

**Vérifié par le garde** : `types_named_by_module` (`check-module-boundaries.py:648-674`) exécuté sur les quatre surfaces rend exactement les 60 entrées, avec leurs fichiers. Chacune des 21 lignes promises n'est nommée que par des fichiers que ce lot réécrit : `io.BoundedFileLease` et `io.Sha256` (CLI) par `RemoteIndexLease` seul ; `orchestration.IndexStateStore`, `IndexingRun`, `runtime.ProviderRuntimeManager`, `ProviderRuntimeStatus` par `LocalAutonomousIndexOperations` seul ; `IndexingRuntimePorts` par `LocalAutonomousIndexOperations` et `LocalRemoteIndexOperations` ; `IndexingMode` par `AutonomousIndexOperations` ; `IndexingResumePolicy` par `IndexCommand`, `AutonomousIndexOperations`, `LocalAutonomousIndexOperations` ; `ResumableRunSummary` par `ProjectCommand`, `IndexResumeStatusSource`, `LocalAutonomousIndexOperations` (CLI), `LocalMinosApi` (API), `MinosApplicationMcpBackend` (MCP) ; les cinq `remote.*`, `IndexerDescriptor`, `ProjectRegistry`, `RegisteredProject` par `LocalRemoteIndexOperations`, `RemoteIndexOperations`, `RemoteIndexCommand` ; `ProviderConformanceKit` par `ProviderPlatformApi`. Les deux lignes qui **restent** malgré l'indexation distante sont tenues par `DoctorCommand` : `io.PrivateLocalStorage` (aussi nommé par `RemoteIndexLease`) et `runtime.WorkerSandboxProbe` (aussi nommé par `LocalRemoteIndexOperations`).

**Non vérifié** : aucun build ni test Java exécuté pour cette spécification ; les tailles de lignes sont des `wc -l`, pas des lignes couvrables JaCoCo ; le passage de SpotBugs sur la copie JDK de `ProviderDto` n'est pas mesuré ; les noms des futurs types (`RemoteIndexingUseCase`, résumés) sont des propositions que l'implémentation peut ajuster en mettant à jour les tâches.

## Inventaire des 60 entrées par famille et lots

| Famille (package du moteur) | Entrées | CLI | API | MCP | NEXUS | Lot |
|---|---|---|---|---|---|---|
| `orchestration` | 10 | 7 | 2 | 1 | 0 | **1** (toutes) |
| `remote` | 5 | 5 | 0 | 0 | 0 | **1** (toutes) |
| `runtime` | 5 | 4 | 0 | 1 | 0 | **1** : `ProviderRuntimeManager`, `ProviderRuntimeStatus` ; 2 : `WorkerSandboxProbe` ; 6 : `MinosVersion` ×2 |
| `registry` | 10 | 6 | 0 | 1 | 3 | **1** : `ProjectRegistry`, `RegisteredProject` (CLI) ; 5 : NEXUS ; 6 : le reste |
| `io` | 4 | 3 | 0 | 0 | 1 | **1** : `BoundedFileLease`, `Sha256` (CLI) ; 2 : `PrivateLocalStorage` ; 5 : `Sha256` (NEXUS) |
| `hosted` | 13 | 3 | 9 | 1 | 0 | 3 |
| `query` | 6 | 3 | 3 | 0 | 0 | 4 |
| `git` | 2 | 1 | 1 | 0 | 0 | 4 |
| `store` | 2 | 0 | 0 | 0 | 2 | 5 |
| `diagnostics` | 3 | 1 | 1 | 1 | 0 | 6 |
| **Total** | **60** | **33** | **16** | **5** | **6** | lot 1 : **21** |

### Lot 1 — indexation locale et distante (ce changement) : 21 lignes

| Lignes retirées de `ENGINE_TYPES_NAMED_BY_SURFACE` | Remplacées par |
|---|---|
| CLI : `remote.DistributedIndexing`, `remote.IdempotentRemoteRepositoryMaterializer`, `remote.RemoteIndexingRuntime`, `remote.RemoteRepositoryMaterializer`, `remote.RemoteRepositoryRequest`, `orchestration.IndexerDescriptor`, `registry.ProjectRegistry`, `registry.RegisteredProject`, `io.BoundedFileLease`, `io.Sha256` | `RemoteIndexingUseCase` (`com.minos.application.remote`) : `materialize(RemoteSourceRequest)` et `index(RemoteSourceRequest, nom, provider, worker, WorkerNetworkChoice)`, résultats `RemoteMaterializationSummary`, `RemoteIndexSummary`, `ArtifactEvidenceSummary` ; `RemoteIndexLease` déplacé dans le même package |
| CLI : `orchestration.IndexingRun`, `orchestration.IndexingMode`, `orchestration.IndexingResumePolicy` (et `orchestration.IndexingRuntimePorts`, dont le dernier usage part avec l'indexation distante) | `ProjectIndexingUseCase` : `planSummary(…)` et `executeSummary(…, IndexingResumeChoice)` rendant `IndexPlanSummary`, `IndexExecutionSummary`, `ResumeTraceSummary` (mode en chaîne, valeur `name()` inchangée) ; `IndexingResumeChoice` pendant de `IndexingResumePolicy` |
| CLI, API, MCP : `orchestration.ResumableRunSummary` ; CLI : `orchestration.IndexStateStore` | `ProjectInspectionService.resumableRun(String projectId)` → `Optional<ResumableRunStatus>` (`com.minos.application`, règle A8), sans écriture ni bail ; `ProjectJson.indexStatus` prend `Optional<ResumableRunStatus>` à la place de `ResumableRunSummary` (D8) ; `ProjectCommand`, `IndexResumeStatusSource` et `MinosCliRunner` passent au type applicatif |
| CLI : `runtime.ProviderRuntimeManager`, `runtime.ProviderRuntimeStatus` | `ProviderPlatformService.runtimes()` et `installRuntime(String)` → `ProviderRuntimeSummary` (`com.minos.application`, règle A8) ; jamais appelés par le MCP (D7) |
| API : `orchestration.ProviderConformanceKit` | copie triée en JDK dans `ProviderPlatformApi.ProviderDto`, comportement identique (ordre, refus des nulls, non modifiable) |

Après le lot : CLI 15, API 14, MCP 4, NEXUS 6 (39 entrées). `WorkerSandboxProbe` et `PrivateLocalStorage` restent dans la liste de la CLI (`DoctorCommand`).

### Lots suivants (non implémentés ici)

| Lot | Entrées | Remplacement envisagé | Prérequis |
|---|---|---|---|
| 2 — diagnostic de l'hôte | 2 (CLI) | service applicatif de diagnostic (sandbox, confidentialité des dossiers) rendant un rapport JDK | SH-08 (port d'ACL de plateforme, AUD-ARC-05) : `privacyOf` y change de forme |
| 3 — contrôle hébergé | 13 (API 9, CLI 3, MCP 1) | façade applicative de l'équipe rendant des résumés, au-dessus de `HostedControlPlaneService` qui reste dans le moteur (ADR 0062 § 1) ; `HostedControlPlaneRenderer` (application) suit | aucun ; à coordonner avec la cartographie de SH-08, qui peut déplacer `hosted` par un nouvel ADR |
| 4 — Git et résultats de requête | 8 (API 4, CLI 4) | requête applicative d'activité Git (2 entrées retirées) ; résultats de requête gardés comme modèles de lecture publiés (D4 = a, 6 entrées justifiées) | amendement de l'ADR 0057 § 6 dans le même changement |
| 5 — export NEXUS | 6 | cas d'usage applicatif produisant les données d'export, traduction JSON dans l'adaptateur (SH-09) | SH-04 (port de lecture des snapshots, AUD-ARC-07), spécifié en parallèle |
| 6 — noyau transverse | 10 (`PublicErrorMessages` ×3, `MinosVersion` ×2, `UnreadableRegistryException` ×2, `DegradedEntry`, `ProjectPathMapping`, `ProjectPathMappings`) | descente dans `minos-domain` des types sans E/S ni dépendance au moteur, justification des autres (D5) | aucun |

## Goals / Non-Goals

**Goals** : retirer les familles `orchestration` et `remote` de toutes les surfaces ; faire de l'indexation distante un cas d'usage invocable sans la CLI ; lire l'état de reprise sans écrire ; chiffrer et ordonner les lots restants.

**Non-Goals** : modifier un contrat public, une sortie, un code de sortie ou un format disque ; rouvrir `remote index` ; retirer un accesseur de `MinosApplication` ; toucher `minos-engine`, `minos-bootstrap` ou un adaptateur.

## Decisions

Décisions du propriétaire du 2026-10-22 : D1 = A, D2, D3, D4 = a, D5, D6, et l'extension d'A11 aux accesseurs avec le lot 4. Ajoutées par la contre-vérification : D7 (en attente, recommandation A), D8 et D9 (techniques, sans arbitrage).

### D1 — La CLI garde ses ports et ses vues ; la logique qui nomme le moteur monte dans l'application

- **A (retenue)** : `AutonomousIndexOperations`, `RemoteIndexOperations`, `IndexResumeStatusSource` et leurs vues restent dans `com.minos.cli.operations` (contrat de présentation et point d'injection des doublures des tests de commande) ; leurs signatures ne portent plus que des types JDK, CLI ou application. Les implémentations `Local*` restent dans la CLI et ne font que convertir et projeter. L'exigence « Le module CLI est rangé en sous-packages sans cycle » reste vraie telle quelle.
- **B** : déplacer ports, vues et implémentations dans l'application (précédent : `ProjectOperations`). Moins de projection, mais les vues dépendent de `CliCommandSupport.publicDiagnostic`, l'entrée de base SpotBugs de `ProviderView` suit, le package `operations` se vide (exigence de rangement à modifier) et une `minos-application` ne peut pas implémenter une interface de la CLI.

### D2 — Les énumérations du moteur en entrée ont un pendant applicatif

`IndexingResumePolicy` et `DistributedIndexing.WorkerNetworkPolicy` sont lus par le moteur : ils ne montent pas. L'application définit `IndexingResumeChoice` et `WorkerNetworkChoice` à valeurs identiques, convertis à l'entrée du cas d'usage. Un test d'exhaustivité échoue si une valeur du moteur n'a pas de pendant (ou l'inverse). Alternative écartée : des booléens (`noResume`, `resumeOnly`), qui admettent une combinaison invalide que la commande refuse aujourd'hui.

### D3 — Tests : unitaires dans l'application, intégration de bout en bout dans la CLI

SonarCloud juge la couverture du nouveau code **module par module** : le code monté dans `minos-application` doit être couvert par les tests de `minos-application`, qui ne peut dépendre ni de `minos-bootstrap` ni de `minos-runtime-local`. Les tests unitaires s'écrivent avec des doublures des ports du moteur (`RemoteIndexingRuntime`, `RemoteRepositoryMaterializer`, `IndexingFixture` existant). Les tests qui ont besoin du runtime réel (`LocalRemoteIndexingRuntimeFixtures`, `DistributedArtifactBundleStore`) restent dans `minos-cli` et traversent le cas d'usage ; ils comptent pour le rapport agrégé de `check-jacoco.py`, pas pour Sonar, qui importe le rapport JaCoCo **de chaque module**, Linux et Windows (`.github/workflows/pr-ci.yml:446-478`). La preuve « lecture sans écriture » sur un vrai `MINOS_HOME` vit pour la même raison dans `minos-mcp` (`MinosMcpReadOnlyHomeTest`), seul module où elle a un sens de bout en bout ; l'application la prouve par une doublure du magasin d'état qui échoue sur toute écriture ou prise de bail.

### D4 — Résultats de requête et port Git (lot 4)

`SymbolResult`, `UsageResult`, `RelationshipResult` sont les résultats que rend déjà le port applicatif `ProjectSymbolQuery`. (a) **Retenue** : ils sont des modèles de lecture publiés aux surfaces — amendement de l'ADR 0057 § 6, planifié dans le changement du lot 4 ; leurs 6 entrées restent dans A11 avec cette justification ; l'appel direct du port `GitIntelligence` par une surface passe en revanche par une requête applicative. (b) Écartée : dupliquer ces records dans l'application (une dizaine de types, conversions sans valeur ajoutée).

### D5 — Noyau transverse (lot 6)

`PublicErrorMessages`, `MinosVersion` et `DegradedEntry.printable` sont des politiques sans état partagées par toutes les surfaces. Règle retenue pour le lot 6, appliquée type par type : un type sans E/S ni dépendance au moteur descend dans `minos-domain` ; sinon son entrée reste dans A11 avec sa justification.

### D6 — Les lignes de la table sont retirées dans la même PR que le code

Le cliquet échoue sur une entrée devenue inutile : les 21 lignes disparaissent avec le dernier usage, jamais avant.

### D7 — Installation de runtime sur un service que le MCP utilise (en attente du propriétaire)

`ProviderPlatformService` est construit par le MCP (`MinosApplicationMcpBackend.java:84`), en lecture seule (ADR 0017). Y ajouter `installRuntime` met une méthode qui écrit à portée du MCP ; y ajouter `runtimes()` aussi, puisque l'inspection d'un runtime écrit un script sous Windows.
- **A (recommandée)** : les deux méthodes sur `ProviderPlatformService` ; le MCP ne les appelle pas et `MinosMcpReadOnlyHomeTest` (compte des appels au gestionnaire de runtimes, `:55`) l'atteste déjà. Un seul service des providers, aucun type de plus.
- **B** : un petit service applicatif distinct (`ProviderRuntimeService`) que seule la CLI construit. Séparation plus visible, une classe et un test de plus, et deux services sur le même gestionnaire.
Recommandation : **A**, parce que la garde existe déjà et qu'elle est exécutée par le job `Verify` sur les deux systèmes.

### D8 — `ProjectJson.indexStatus` change de paramètre au lieu d'être surchargé

`ProjectJson` (`com.minos.output`) n'est pas un contrat public. Une surcharge laisserait la forme `ResumableRunSummary` sans appelant de production, à couvrir pour SonarCloud et à justifier. Décision technique, sans arbitrage : le paramètre devient `Optional<ResumableRunStatus>`, `ProjectJsonTest` est adapté avec les mêmes clés et valeurs.

### D9 — La requête distante reste validée avant l'ouverture de l'application

Aujourd'hui `RemoteRepositoryRequest.of` refuse une URL, une référence, un sous-dossier ou un nom de variable de secret invalides dans la commande, avant tout appel aux opérations paresseuses (`RemoteIndexCommand.java:69-71`). Déplacer cette construction dans le cas d'usage ouvrirait l'application (dossiers de `MINOS_HOME`, migrations) avant le refus. Décision technique, sans arbitrage : `RemoteSourceRequest.of(…)` (application) construit immédiatement la requête du moteur et propage son refus tel quel ; la commande l'appelle au même endroit.

## Direction des dépendances (ADR 0022)

Inchangée : `minos-cli`, `minos-api`, `minos-mcp` → `minos-application` → `minos-engine`. `com.minos.application.remote` est un sous-package de la racine `application` déjà listée pour `minos-application` (règle A14) ; il n'importe aucun adaptateur (règle A2) ; le matérialiseur et le runtime distant restent fournis par `MinosApplicationComposer` (`minos-bootstrap`, ADR 0042). Aucune arête de module ni POM ne change. `minos-engine` n'est pas modifié.

Packages de `minos-application` (règle A8, exigence « Les packages de `minos-application` sont séparables ») : `application.remote` → `application`, `application.indexing` ; `application.indexing` → `application` (existant) ; `output` → `application` (existant). Aucune flèche en retour : `ResumableRunStatus` et `ProviderRuntimeSummary` vivent dans `com.minos.application`, à côté des services qui les rendent ; `IndexingResumeChoice` et les résumés de plan et d'exécution dans `application.indexing` ; `MinosApplication` ne gagne aucun accesseur vers le cas d'usage distant, obtenu par `RemoteIndexingUseCase.of(MinosApplication)` comme `ProjectIndexingUseCase.of`. Les nouveaux types publics ont un appelant de production dans la CLI (règle A15).

## Capacités

| Capacité | Qualification |
|---|---|
| `remote materialize` | qualifiée, inchangée |
| `remote index` | **non supportée** pour du code non fiable (ADR 0041) : refus avant toute matérialisation, bail, enregistrement ou épinglage, conservé à l'identique dans le cas d'usage |
| Indexation locale, reprise, statut | qualifiées, inchangées |

## Windows et Linux

Le bail inter-JVM (`BoundedFileLease`, `PrivateLocalStorage.ensurePrivateDirectory`) et le refus de l'ADR 0041 se comportent selon la plateforme (rapport de refus par backend). Les tests déplacés s'exécutent dans les deux jobs `Verify (ubuntu-24.04)` et `Verify (windows-2022)` ; aucun test n'est restreint à une plateforme par ce changement. La lecture de l'état de reprise sans trace est observée sous les deux systèmes par `MinosMcpReadOnlyHomeTest`, qui relève chemin, taille et date de modification de tout l'arbre (sous Windows, une inspection de runtime y laisserait un script de lancement). Sur le poste Windows, la portée JaCoCo `m24-polyglot-provider-platform` est rouge hors régression : elle se compare à `develop`.

## Coût

| Poste | Estimation au HEAD |
|---|---|
| Code de production monté dans `minos-application` | ≈ 430 lignes : logique de `LocalRemoteIndexOperations` (≈ 200 sur 254), `RemoteIndexLease` (52), projections de `LocalAutonomousIndexOperations` (≈ 45), résumés et pendants (≈ 110), requête de reprise et runtimes (≈ 25). Tout est **code neuf** pour SonarCloud : ≥ 80 % de lignes couvertes par les tests de `minos-application` |
| Code restant dans la CLI | `LocalRemoteIndexOperations` et `LocalAutonomousIndexOperations` réduits à la conversion et à la projection ; lignes modifiées comptées comme nouvelles dans `minos-cli` (couvertes par les tests de commande existants) |
| Tests de `minos-cli/src/test/java/com/minos/cli/operations` (7 classes, 848 lignes) | vers l'application : `RemoteIndexLeaseTest` (53), `LocalAutonomousIndexOperationsStateTest` (52), `LocalAutonomousIndexOperationsRunFormatTest` (74), `IndexFailurePathsTest` (108), réécrits sur `IndexingFixture` et `MinosApplication.builder` (les deux premiers ouvrent aujourd'hui `MinosApplication.open`, impossible dans `minos-application`) ; `LocalRemoteIndexOperationsRefusalTest` (231) : cas unitaires réécrits avec une doublure de `RemoteIndexingRuntime`, un cas de bout en bout gardé dans la CLI ; restent dans la CLI : `LocalRemoteIndexOperationsIntegrationTest` (248, runtime réel, reciblé sur le cas d'usage) et `IndexRunFailureMessageTest` (82 : il vérifie que la ligne de `ProjectIndexingUseCase.failureMessage` traverse `CliCommandSupport.failureMessage`, classe de la CLI ; il ne couvre aucun code neuf) |
| Tests de commande de la CLI | signatures des doublures adaptées (`IndexCommandResumeFlagsTest`, `NoResumeIndexingTest`, `RemoteIndexCommandTest`, `MinosCliRunnerResumeStatusTest`…) ; aucune attente de sortie modifiée |
| Golden de caractérisation | `minos-app/src/test/resources/characterization/` : `cli-json.golden` (`index-status`, `:22-40`), `api.golden` (`ProjectDto`, `ProviderDto`, `:2-24`), `mcp.golden` (`minos_index_status`, `:5-8`) ; comparés par `A2SurfaceCharacterizationTest`, qui ne tourne que dans `minos-app` : la preuve « sorties inchangées » l'exécute explicitement, aucun golden n'est régénéré |
| `scripts/quality/check-jacoco.py` | portée `m25-remote-distributed-indexing` (`:123-143`) : ajouter le préfixe `com/minos/application/remote/` et transférer le plancher par classe `{line 0.65, branch 0.35}` de `com/minos/cli/operations/LocalRemoteIndexOperations` vers `com/minos/application/remote/RemoteIndexingUseCase` (la classe CLI amincie reste dans les préfixes) ; portées `public-api` (`:62`, `com/minos/api/`) et `mcp-mapping` (`:63`, `com/minos/mcp/`) rejouées pour l'API et le MCP ; `global` ; aucun plancher abaissé sans mesure du rapport agrégé sur les deux systèmes |
| Gates littéraux | `check-remote-distributed-consistency.py:128-146` (chemin et faits `RemoteIndexingRuntime`, `compositionRoot.remoteRepositoryMaterializer(`, `compositionRoot.remoteIndexingRuntime(`, `force`, `verified artifact evidence`, imports interdits ; `:139` garde `executorDecorator` et `UnaryOperator<IndexerExecutor>` dans `ProjectIndexingUseCase`, que le cas distant continue d'utiliser) et `:179-180`, `:206-211` (`RemoteIndexCommandTest` et test d'intégration, qui restent dans la CLI) ; `scripts/remediation/check-mnd.py:39-40`, `:64-71` (`RemoteIndexLease.acquire(application.home(), source.cacheKey())`, `indexUnderSourceLease`, faits du bail) ; `minos-app/.../ApplicationOwnershipGuardTest.java:63-66` (fragment du constructeur de `LocalAutonomousIndexOperations`) ; `check-module-boundaries.py:509-578` (table A11). Lisent aussi des fichiers touchés, avec des faits que ce lot ne retire pas : `check-polyglot-provider-consistency.py:104` et `check-post-mne.py:109`, `:270-279` (`ProjectIndexingUseCase`, méthodes ajoutées seulement), `check-post-mne.py:253` et `check-runtime-dynamic-consistency.py:116` (`MinosApplicationMcpBackend`, faits sans rapport avec la reprise) |
| SpotBugs (`quality/spotbugs-baseline/`, workflow `code-audit`, déclenché à la main, bloquant par l'entrée `spotbugs_blocking`) | l'entrée `AutonomousIndexOperations$ProviderView` de `minos-cli.xml` reste valable (D1 = A) ; `minos-api.xml:1060-1066` fige `EI_EXPOSE_REP` sur `ProviderPlatformApi$ProviderDto.capabilities()` : si la copie JDK la fait disparaître, `audit-report-summary.py` la signale (`code-audit.yml:76-78`) et l'entrée est retirée de la base dans la même PR ; une alerte nouvelle sur un résumé applicatif (records à listes) n'est pas ajoutée à `minos-application.xml` : elle se corrige dans le code |
| Documentation courante | `docs/developer/remote-distributed-indexing.md:42,78`, `docs/architecture/arc42/06-vue-execution.md:3,52,150`, `docs/architecture/diagrams/seq-indexation-nominale.md:46`, `seq-erreur-provider.md:66`, ADR 0041 et 0042 (mentions de `LocalRemoteIndexOperations`), `docs/project/governance/STATUS.md` |

## Risks / Trade-offs

- **Le retour arrière de `remote index` change de module** → le test d'intégration de la CLI garde ses faits (`WorkerTransportStagingAndAtomicLocalSnapshot`, `loadActiveKnowledge`, `projectRelativeRoot`) et un test unitaire de l'application couvre chaque sortie du bloc de nettoyage (bail, enregistrement, épinglage, exécution, preuves).
- **Doublons d'énumérations** (D2) → test d'exhaustivité.
- **Projection en double** (application → résumé → vue CLI) → accepté (D1) ; la vue CLI reste le contrat de présentation.
- **Faux retrait par `var` ou chaînage** → exigence et scénario de recherche dédiés.
- **Cycle de packages par le placement d'un résumé** → placement fixé (Direction des dépendances), A8 rejoué à chaque tâche.
- **Requête distante validée trop tard** (application ouverte avant le refus) → D9, scénario dédié.
- **Méthode d'écriture à portée du MCP** → D7, `MinosMcpReadOnlyHomeTest`.
- **Décomptes écrits en dur** dans l'exigence et la tâche 6.1 → si un autre changement modifie la table A11 avant celui-ci, ses décomptes se recalculent (60 moins les entrées retirées ailleurs, moins 21) avant la PR.

## Recouvrements avec les changements actifs

| Changement | Fichiers communs | Effet |
|---|---|---|
| `exposer-un-port-de-lecture-de-snapshot` (AUD-ARC-07, SH-04) | `scripts/architecture/check-module-boundaries.py` (il ajoute une règle A17 et ses auto-tests) ; `minos-application` (`application`, `impact`, `context`, `application.dynamic`), pas les classes de ce lot | aucune exigence contradictoire ; il déclare la table A11 inchangée (son `design.md:55`), en particulier les deux lignes `store.*` de NEXUS (lot 5). Conflit textuel possible dans le garde : le second fusionné rebase et rejoue `test_check_module_boundaries.py`. Les numéros de ligne de la table (`:509-578`) dérivent alors |
| `decouper-les-classes-de-plus-de-800-lignes` | `scripts/remediation/check-mnd.py` (lui `:60`, `:118`, `:122`, ce lot `:39-40`, `:64-71`) ; `PrivateLocalStorage` (il en extrait des classes de `com.minos.io`, signatures publiques gardées, `ensurePrivateDirectory` reste) | lignes disjointes du gate ; `RemoteIndexLease` appelle `PrivateLocalStorage.ensurePrivateDirectory`, inchangé. Si une classe extraite était nommée par `DoctorCommand`, elle entrerait dans A11 : c'est hors de ce lot (lot 2) |
| `retirer-les-jalons-des-identifiants` (AUD-QUA-08) | aucun | il garde les noms de portées JaCoCo à jalon (`m25-remote-distributed-indexing` reste le nom à éditer) et les dossiers `scripts/mNN/` lus par `check-remote-distributed-consistency.py` |
| `mutualiser-les-caches-lru-et-nommer-les-additions-bornees` (AUD-QUA-05) | `scripts/remediation/check-mnd.py` (lui `:99-106`) | lignes disjointes ; aucune classe commune |

## Migration Plan

Une PR vers `develop`, branche `refactor/`. Sections dans l'ordre des tâches : reprise, runtimes, indexation locale, indexation distante, API, cliquet. Retour arrière : revert de la PR, aucun format ni donnée touchés.

## Open Questions

D7 attend la décision du propriétaire (recommandation : A) ; les tâches 2.1 et 2.2 sont écrites pour A et changent de service si B est retenue. L'extension d'A11 aux accesseurs de `MinosApplication` appelés par les surfaces (usages sans nom) est retenue et se spécifie avec le lot 4, pour ne pas changer la règle pendant qu'on vide sa table.
