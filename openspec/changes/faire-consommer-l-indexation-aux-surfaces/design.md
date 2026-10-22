# Design

## Context

Constat **AUD-ARC-01** du sprint 10 de l'audit du 10 octobre 2026 (suite de SH-05, puis SH-09 du chantier `docs/project/roadmap/storage-hexagonal-2026-10/`). HEAD analysé : `6b60790e` (`develop`, après la PR #534). Les ADR applicables sont 0057 § 6 (« faire consommer [les] résultats [des cas d'usage] par API/CLI/MCP/NEXUS ; conserver les contrats publics ») et 0058 § 3 (les usages du moteur par les surfaces sont une dette à résorber). La règle A11 tient cette dette ; chaque lot livré en retire des lignes.

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
| `minos-api/.../ProviderPlatformApi.java` | `ProviderConformanceKit` (`:3`, `:36` : seul appel, `sortedCopy`) |
| `minos-api/.../LocalMinosTeamApi.java` | 9 types de `com.minos.hosted` |
| `minos-api/.../LocalMinosMultiRepositoryApi.java`, `MinosApiSupport.java` | `GitIntelligence`, `PublicErrorMessages` |
| `minos-mcp/.../MinosApplicationMcpBackend.java` | `ResumableRunSummary` (`:122`), `HostedControlPlaneService` |
| `minos-mcp/.../MinosMcpTools.java`, `MinosMcpServer.java` | `PublicErrorMessages`, `UnreadableRegistryException`, `MinosVersion` |
| `minos-nexus/.../NexusExportService.java` | `ProjectRegistry`, `RegisteredProject`, `UnreadableRegistryException`, `CodeKnowledgeSnapshot`, `CodeKnowledgeSnapshotStore`, `Sha256` |

Faits relus :

- `LocalAutonomousIndexOperations.java:51-52` prend `application.indexStateStore()` et `application.providerRuntimeManager()` ; `:58-63` construit `ResumableRunSummary.of(stateStore, …)` ; `:76-91` projette `ProjectIndexingUseCase.Outcome` et `IndexingRun` ; `:93-97` liste et installe les runtimes de providers ; `:99-113` projette `Preparation` (projet enregistré, découverte, `ProviderRuntimeStatus`).
- `LocalRemoteIndexOperations.java` (254 lignes) **compose un cas d'usage** dans la surface : refus anticipé de l'ADR 0041 (`:102`, `:111-120`), bail inter-JVM (`:146`), enregistrement (`:148`), épinglage, décoration des exécuteurs par `RemoteIndexingRuntime.distribute`, exécution par `new LocalAutonomousIndexOperations(application, decorator)` (`:165`), preuves d'artefacts vérifiées, retour arrière (`:191`). `RemoteIndexLease.java` (52 lignes) est son bail.
- `LocalMinosApi.java:270-272` et `MinosApplicationMcpBackend.java:122` lisent l'état de reprise par `ResumableRunSummary.of(application.indexStateStore(), …)` et le passent à `ProjectJson` (`minos-application/.../output/ProjectJson.java:55-71`), qui nomme lui-même `ResumableRunSummary`.
- `ProviderConformanceKit.sortedCopy` (`minos-engine/.../ProviderConformanceKit.java:73-79`) : copie dans un `TreeMap`, clés et valeurs non nulles, vue non modifiable.
- `IndexingResumePolicy` et `IndexingMode` sont utilisés par 8 classes de `minos-engine` (planificateur de reprise, cycle d'indexation) : ils ne peuvent pas monter dans l'application.
- Les signatures de `com.minos.api` ne nomment que `com.minos.api.*` et `MinosApplication` (`docs/user/java-api.md:334-335`) : les types du moteur ci-dessus sont des détails d'implémentation des surfaces, pas des contrats publics.

### Usages sans nom (angle mort du cliquet)

A11 compte des **noms** (import ou nom qualifié). Une surface peut utiliser un type du moteur sans le nommer, en chaînant un accesseur de `MinosApplication`. Relevé au HEAD : `indexStateStore()` (`LocalMinosApi`, `MinosApplicationMcpBackend`, `LocalAutonomousIndexOperations`), `providerRuntimeManager()`, `indexerDescriptors()`, `compositionRoot()` (`LocalRemoteIndexOperations`), `projectRegistry()` et `snapshotStore()` (`LocalMinosApi.java:97`, `MinosCliRunner.java:116`), `gitIntelligence()` et `hostedControlPlaneService()` (CLI, API, MCP). Ce lot retire les quatre premiers des surfaces ; les autres relèvent des lots suivants. Remplacer un type nommé par `var` ou par un chaînage n'est **pas** un retrait (exigence « Les familles de l'indexation ne sont plus nommées par les surfaces »).

### Écarts avec l'action proposée par l'audit

- L'audit comptait 103 références et 52 types pour la CLI ; AUD-ARC-02 en a retiré 19 (CLI 33 au HEAD). Le compte « 102 imports dans 30 fichiers » du suivi date d'avant SH-05 ; la mesure courante est la table A11.
- L'audit proposait de faire consommer des cas d'usage par **toutes** les surfaces d'un coup. Les familles n'ont pas les mêmes prérequis (SH-04 pour NEXUS, SH-08 pour le diagnostic et `hosted`) ni le même statut (résultats de requête, noyau transverse) : le travail est découpé en lots, ce changement livre le premier.
- `ProjectJson` (application) nomme `ResumableRunSummary` : ce n'est pas une surface, A11 ne le compte pas ; il reçoit une surcharge applicative pour que les surfaces n'aient plus à produire ce type.

**Non vérifié** : aucun build ni test Java exécuté pour cette spécification ; les tailles de lignes sont des `wc -l`, pas des lignes couvrables JaCoCo ; les noms des futurs types (`RemoteIndexingUseCase`, résumés) sont des propositions que l'implémentation peut ajuster en mettant à jour les tâches.

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
| CLI, API, MCP : `orchestration.ResumableRunSummary` ; CLI : `orchestration.IndexStateStore` | `ProjectInspectionService.resumableRun(String projectId)` → `Optional<ResumableRunStatus>`, lecture seule ; surcharge de `ProjectJson` acceptant `ResumableRunStatus` |
| CLI : `runtime.ProviderRuntimeManager`, `runtime.ProviderRuntimeStatus` | `ProviderPlatformService.runtimes()` et `installRuntime(String)` → `ProviderRuntimeSummary` |
| API : `orchestration.ProviderConformanceKit` | copie triée en JDK dans `ProviderPlatformApi.ProviderDto`, comportement identique (ordre, refus des nulls, non modifiable) |

Après le lot : CLI 15, API 14, MCP 4, NEXUS 6 (39 entrées). `WorkerSandboxProbe` et `PrivateLocalStorage` restent dans la liste de la CLI (`DoctorCommand`).

### Lots suivants (non implémentés ici)

| Lot | Entrées | Remplacement envisagé | Prérequis |
|---|---|---|---|
| 2 — diagnostic de l'hôte | 2 (CLI) | service applicatif de diagnostic (sandbox, confidentialité des dossiers) rendant un rapport JDK | SH-08 (port d'ACL de plateforme, AUD-ARC-05) : `privacyOf` y change de forme |
| 3 — contrôle hébergé | 13 (API 9, CLI 3, MCP 1) | façade applicative de l'équipe rendant des résumés, au-dessus de `HostedControlPlaneService` qui reste dans le moteur (ADR 0062 § 1) ; `HostedControlPlaneRenderer` (application) suit | aucun ; à coordonner avec la cartographie de SH-08, qui peut déplacer `hosted` par un nouvel ADR |
| 4 — Git et résultats de requête | 8 (API 4, CLI 4) | requête applicative d'activité Git ; sort des résultats de requête selon D4 | décision D4 |
| 5 — export NEXUS | 6 | cas d'usage applicatif produisant les données d'export, traduction JSON dans l'adaptateur (SH-09) | SH-04 (port de lecture des snapshots, AUD-ARC-07), spécifié en parallèle |
| 6 — noyau transverse | 10 (`PublicErrorMessages` ×3, `MinosVersion` ×2, `UnreadableRegistryException` ×2, `DegradedEntry`, `ProjectPathMapping`, `ProjectPathMappings`) | selon D5 | aucun |

## Goals / Non-Goals

**Goals** : retirer les familles `orchestration` et `remote` de toutes les surfaces ; faire de l'indexation distante un cas d'usage invocable sans la CLI ; lire l'état de reprise sans écrire ; chiffrer et ordonner les lots restants.

**Non-Goals** : modifier un contrat public, une sortie, un code de sortie ou un format disque ; rouvrir `remote index` ; retirer un accesseur de `MinosApplication` ; toucher `minos-engine`, `minos-bootstrap` ou un adaptateur.

## Decisions

### D1 — La CLI garde ses ports et ses vues ; la logique qui nomme le moteur monte dans l'application

- **A (recommandée)** : `AutonomousIndexOperations`, `RemoteIndexOperations`, `IndexResumeStatusSource` et leurs vues restent dans `com.minos.cli.operations` (contrat de présentation et point d'injection des doublures des tests de commande) ; leurs signatures ne portent plus que des types JDK, CLI ou application. Les implémentations `Local*` restent dans la CLI et ne font que convertir et projeter. L'exigence « Le module CLI est rangé en sous-packages sans cycle » reste vraie telle quelle.
- **B** : déplacer ports, vues et implémentations dans l'application (précédent : `ProjectOperations`). Moins de projection, mais les vues dépendent de `CliCommandSupport.publicDiagnostic`, l'entrée de base SpotBugs de `ProviderView` suit, le package `operations` se vide (exigence de rangement à modifier) et une `minos-application` ne peut pas implémenter une interface de la CLI.

### D2 — Les énumérations du moteur en entrée ont un pendant applicatif

`IndexingResumePolicy` et `DistributedIndexing.WorkerNetworkPolicy` sont lus par le moteur : ils ne montent pas. L'application définit `IndexingResumeChoice` et `WorkerNetworkChoice` à valeurs identiques, convertis à l'entrée du cas d'usage. Un test d'exhaustivité échoue si une valeur du moteur n'a pas de pendant (ou l'inverse). Alternative écartée : des booléens (`noResume`, `resumeOnly`), qui admettent une combinaison invalide que la commande refuse aujourd'hui.

### D3 — Tests : unitaires dans l'application, intégration de bout en bout dans la CLI

SonarCloud juge la couverture du nouveau code **module par module** : le code monté dans `minos-application` doit être couvert par les tests de `minos-application`, qui ne peut dépendre ni de `minos-bootstrap` ni de `minos-runtime-local`. Les tests unitaires s'écrivent avec des doublures des ports du moteur (`RemoteIndexingRuntime`, `RemoteRepositoryMaterializer`, `IndexingFixture` existant). Les tests qui ont besoin du runtime réel (`LocalRemoteIndexingRuntimeFixtures`, `DistributedArtifactBundleStore`) restent dans `minos-cli` et traversent le cas d'usage ; ils comptent pour le rapport agrégé de `check-jacoco.py`, pas pour Sonar.

### D4 — Résultats de requête et port Git (décision du propriétaire, lot 4)

`SymbolResult`, `UsageResult`, `RelationshipResult` sont les résultats que rend déjà le port applicatif `ProjectSymbolQuery`. (a) **Recommandée** : les déclarer modèles de lecture publiés aux surfaces — amendement de l'ADR 0057 § 6, entrées gardées dans A11 avec cette justification ; l'appel direct du port `GitIntelligence` par une surface passe en revanche par une requête applicative. (b) Dupliquer ces records dans l'application (une dizaine de types, conversions sans valeur ajoutée).

### D5 — Noyau transverse (lot 6)

`PublicErrorMessages`, `MinosVersion` et `DegradedEntry.printable` sont des politiques sans état partagées par toutes les surfaces. À évaluer type par type : descendre dans `minos-domain` s'il n'a ni E/S ni dépendance au moteur, sinon garder l'entrée avec justification. Aucune décision n'est demandée pour ce lot.

### D6 — Les lignes de la table sont retirées dans la même PR que le code

Le cliquet échoue sur une entrée devenue inutile : les 21 lignes disparaissent avec le dernier usage, jamais avant.

## Direction des dépendances (ADR 0022)

Inchangée : `minos-cli`, `minos-api`, `minos-mcp` → `minos-application` → `minos-engine`. `com.minos.application.remote` est un sous-package de la racine `application` déjà listée pour `minos-application` (règle A14) ; il n'importe aucun adaptateur (règle A2) ; le matérialiseur et le runtime distant restent fournis par `MinosApplicationComposer` (`minos-bootstrap`, ADR 0042). Aucune arête de module ni POM ne change. `minos-engine` n'est pas modifié.

## Capacités

| Capacité | Qualification |
|---|---|
| `remote materialize` | qualifiée, inchangée |
| `remote index` | **non supportée** pour du code non fiable (ADR 0041) : refus avant toute matérialisation, bail, enregistrement ou épinglage, conservé à l'identique dans le cas d'usage |
| Indexation locale, reprise, statut | qualifiées, inchangées |

## Windows et Linux

Le bail inter-JVM (`BoundedFileLease`, `PrivateLocalStorage.ensurePrivateDirectory`) et le refus de l'ADR 0041 se comportent selon la plateforme (rapport de refus par backend). Les tests déplacés s'exécutent dans les deux jobs `Verify (ubuntu-24.04)` et `Verify (windows-2022)` ; aucun test n'est restreint à une plateforme par ce changement.

## Coût

| Poste | Estimation au HEAD |
|---|---|
| Code de production monté dans `minos-application` | ≈ 430 lignes : logique de `LocalRemoteIndexOperations` (≈ 200 sur 254), `RemoteIndexLease` (52), projections de `LocalAutonomousIndexOperations` (≈ 45), résumés et pendants (≈ 110), requête de reprise et runtimes (≈ 25). Tout est **code neuf** pour SonarCloud : ≥ 80 % de lignes couvertes par les tests de `minos-application` |
| Code restant dans la CLI | `LocalRemoteIndexOperations` et `LocalAutonomousIndexOperations` réduits à la conversion et à la projection ; lignes modifiées comptées comme nouvelles dans `minos-cli` (couvertes par les tests de commande existants) |
| Tests de `minos-cli/src/test/java/com/minos/cli/operations` (7 classes, 848 lignes) | vers l'application : `RemoteIndexLeaseTest` (53), `IndexRunFailureMessageTest` (82, il teste `ProjectIndexingUseCase.failureMessage`), `LocalAutonomousIndexOperationsStateTest` (52), `LocalAutonomousIndexOperationsRunFormatTest` (74), `IndexFailurePathsTest` (108), réécrits sur `IndexingFixture` ; `LocalRemoteIndexOperationsRefusalTest` (231) : cas unitaires réécrits avec une doublure de `RemoteIndexingRuntime`, un cas de bout en bout gardé dans la CLI ; `LocalRemoteIndexOperationsIntegrationTest` (248) : reste dans la CLI (runtime réel), reciblé sur le cas d'usage |
| Tests de commande de la CLI | signatures des doublures adaptées (`IndexCommandResumeFlagsTest`, `NoResumeIndexingTest`, `RemoteIndexCommandTest`, `MinosCliRunnerResumeStatusTest`…) ; aucune attente de sortie modifiée |
| `scripts/quality/check-jacoco.py` | portée `m25-remote-distributed-indexing` (`:123-143`) : ajouter le préfixe `com/minos/application/remote/` et transférer le plancher par classe `{line 0.65, branch 0.35}` de `com/minos/cli/operations/LocalRemoteIndexOperations` vers `com/minos/application/remote/RemoteIndexingUseCase` ; aucun plancher abaissé sans mesure du rapport agrégé sur les deux systèmes |
| Gates littéraux | `check-remote-distributed-consistency.py:128-146` (chemin et faits `RemoteIndexingRuntime`, `compositionRoot.remoteRepositoryMaterializer(`, `compositionRoot.remoteIndexingRuntime(`, `force`, `verified artifact evidence`, imports interdits) et `:180`, `:209` (test d'intégration) ; `scripts/remediation/check-mnd.py:39-40`, `:64-71` (`RemoteIndexLease.acquire(application.home(), source.cacheKey())`, `indexUnderSourceLease`, faits du bail) ; `minos-app/.../ApplicationOwnershipGuardTest.java:63-66` (fragment du constructeur de `LocalAutonomousIndexOperations`) ; `check-module-boundaries.py:509-578` (table A11) |
| SpotBugs (`quality/spotbugs-baseline/`, workflow `code-audit` bloquant) | l'entrée `AutonomousIndexOperations$ProviderView` de `minos-cli.xml` reste valable (D1 = A) ; une alerte nouvelle sur un résumé applicatif (records à listes) n'est pas ajoutée à `minos-application.xml` : elle se corrige dans le code |
| Documentation courante | `docs/developer/remote-distributed-indexing.md:42,78`, `docs/architecture/arc42/06-vue-execution.md:3,52,150`, `docs/architecture/diagrams/seq-indexation-nominale.md:46`, `seq-erreur-provider.md:66`, ADR 0041 et 0042 (mentions de `LocalRemoteIndexOperations`), `docs/project/governance/STATUS.md` |

## Risks / Trade-offs

- **Le retour arrière de `remote index` change de module** → le test d'intégration de la CLI garde ses faits (`WorkerTransportStagingAndAtomicLocalSnapshot`, `loadActiveKnowledge`, `projectRelativeRoot`) et un test unitaire de l'application couvre chaque sortie du bloc de nettoyage (bail, enregistrement, épinglage, exécution, preuves).
- **Doublons d'énumérations** (D2) → test d'exhaustivité.
- **Projection en double** (application → résumé → vue CLI) → accepté (D1) ; la vue CLI reste le contrat de présentation.
- **Faux retrait par `var` ou chaînage** → exigence et scénario de recherche dédiés.
- **Chevauchement avec SH-04** (session parallèle) → aucun : ce lot ne touche ni `com.minos.store` ni `NexusExportService`.

## Migration Plan

Une PR vers `develop`, branche `refactor/`. Sections dans l'ordre des tâches : reprise, runtimes, indexation locale, indexation distante, API, cliquet. Retour arrière : revert de la PR, aucun format ni donnée touchés.

## Open Questions

- Étendre A11 aux accesseurs de `MinosApplication` appelés par les surfaces (usages sans nom) : recommandé dans un changement ultérieur, avec le lot 4, pour ne pas changer la règle pendant qu'on vide sa table.
- D4 et D5 : à trancher avant de spécifier les lots 4 et 6.
