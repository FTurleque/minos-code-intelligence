# Design

## Context

Constat **AUD-ARC-01**, réserves de la revérification du 28 octobre 2026 après la fusion des lots 1 à 6. ADR applicables : 0057 (point 6, les surfaces consomment des cas d'usage ; seuls les modèles de lecture publiés leur sont admis), 0058 § 3 (usages du moteur par les surfaces = dette tenue par A11), 0042 (racine de composition), 0068 (direction des dépendances, un package par module). HEAD analysé : `810dcfb2` (`develop`).

## État vérifié au HEAD

### Le port `ProjectPathMappings` nommé par la CLI

- `minos-engine/src/main/java/com/minos/registry/ProjectPathMappings.java:13-18` : port `loadOptional()` / `save(ProjectPathMapping)` ; `ProjectPathMapping` est un record de `minos-domain` (`com.minos.domain.registry`).
- `minos-application/src/main/java/com/minos/application/MinosApplicationComposer.java:91-92` : `ProjectPathMappings projectPathMappings(Path home)`, implémenté par `minos-bootstrap/src/main/java/com/minos/bootstrap/DefaultMinosApplicationComposer.java:149` (`ProjectPathMappingStore` de `minos-storage`).
- `minos-cli/src/main/java/com/minos/cli/DockerRuntimeBootstrap.java:8` importe le port ; `:59-75` : `MinosApplicationComposers.resolve().projectPathMappings(home)`, puis la règle (correspondance différente → refus code 1 ; absente → création ; identique → message « already matches »). Les arguments sont validés avant (`:44-57`, code 2).
- Tables A11 : `scripts/architecture/check-module-boundaries.py:573-575` (`"registry.ProjectPathMappings": 1` pour `minos-cli`, raison « persisted I/O port ») ; `minos-app/src/test/java/com/minos/app/architecture/ModuleArchitectureTest.java:97-100` (même entrée, table bytecode). Total au HEAD : **9** types dans le garde (API 3, CLI 5, MCP 1, NEXUS 0), **6** dans la table bytecode (API 3, CLI 2, MCP 1).
- Point d'entrée cité par `docker/compose-mcp.connected.yaml:156` et `docker/compose-mcp.prod.yaml:121` ; tests `minos-cli/src/test/java/com/minos/cli/DockerRuntimeBootstrapTest.java` (création idempotente, refus de remplacement, invocation invalide) ; portée JaCoCo `m29-docker-runtime-bootstrap` (`scripts/quality/check-jacoco.py:233-236`).

### Les accesseurs publics de `MinosApplication` typés moteur

`minos_application_accessors()` (`check-module-boundaries.py:677-717`) lit **19** accesseurs publics d'instance dont le type rendu nomme un type de `minos-engine` (`MinosApplication.java:260-316`). Aucun n'est appelé par le code de production d'une surface (A11 vert, aucune entrée d'accesseur dans la table). Leurs appelants de production :

| Accesseurs | Appelants de production | Package |
|---|---|---|
| `projectRegistry`, `snapshotStore`, `indexStateStore`, `fingerprintStore`, `retentionService`, `providerRuntimeManager`, `snapshotStager`, `snapshotPromoter`, `discoveryService`, `fingerprintService`, `invalidationService`, `incrementalIndexingPlanner`, `indexerRegistry` | `ProjectIndexingUseCase.of` (`indexing/ProjectIndexingUseCase.java:203-221`) | `com.minos.application.indexing` |
| `projectRegistry`, `indexerDescriptors` | `RemoteIndexingUseCase.of` (`remote/RemoteIndexingUseCase.java:113-114`) | `com.minos.application.remote` |
| `projectRegistry`, `snapshotStore` | `KnowledgeExportUseCase` | `com.minos.application.export` |
| `gitIntelligence` | `LocalProjectGitQuery.java:17` | `com.minos.application` |
| `scipArtifactImporter` | `LocalProjectOperations.java:60` | `com.minos.application` |
| `providerCatalog` | `ProviderPlatformService.java:35` | `com.minos.application` |
| `hostedControlPlaneService` | aucun (`teamControlPlane` est construit dans le constructeur, `MinosApplication.java:208`) | — |
| `runtimeObservationStore` | aucun en production | — |

Appelants de test hors de `minos-application` des quatre accesseurs de la dernière famille : `minos-bootstrap/.../MinosApplicationTest.java:78-79` (identité, absence du mode hébergé) ; `minos-bootstrap/.../SnapshotScalabilityBenchmark.java:258,291` (`scipArtifactImporter().importSnapshot`) ; `minos-app/.../ProviderCatalogPortTest.java:26` (ordre du catalogue) ; `minos-mcp/.../MinosApplicationMcpBackendM27Test.java:26` (amorçage d'un tenant). `runtimeObservationStore()` amorce en plus des sessions sur disque dans `minos-mcp/.../MinosMcpWriteProtectedHomeTest.java:114`.

Gate littéral : `scripts/quality/check-hosted-control-plane-consistency.py:97-99` exige la chaîne `hostedControlPlaneService` dans `MinosApplication.java` (le nom de méthode reste). Auto-test : `scripts/architecture/test_check_module_boundaries.py:1268-1276` affirme que l'application réelle expose `gitIntelligence` et `hostedControlPlaneService`.

### Dépendances `compile` vers le moteur

- `minos-cli/pom.xml:22-26`, `minos-api/pom.xml:22-26`, `minos-mcp/pom.xml:24-28` : `minos-engine` sans portée (`compile`).
- Usages directs de production au HEAD : `query.SymbolResult`, `query.UsageResult`, `query.RelationshipResult` (`minos-api/.../LocalMinosApi.java` ; `minos-cli/.../command/FindSymbolCommand.java`, `FindUsagesCommand.java`, `RelationshipCommand.java`) et `runtime.MinosVersion` (`minos-cli/.../MinosLauncher.java`, `minos-mcp/.../MinosMcpServer.java`).
- `minos-application` dépend de `minos-engine` en `compile` : le moteur est donc sur le classpath de compilation des surfaces par transitivité, quelle que soit leur déclaration.

## Écarts avec l'action proposée

- **Réserve 2** : « retirer ceux qui n'ont plus d'appelant de surface » vaut pour les 19, puisqu'aucune surface de production n'en appelle. Le retrait est borné par les appelants des sous-packages de `minos-application` (D2) : 4 accesseurs deviennent privés au package, 15 restent publics, tenus par un cliquet.
- **Réserve 3** : la portée `runtime` est impossible (D3).

## Decisions

### D1 — Une façade applicative pour la correspondance Docker

`ProjectPathMappingService` (`com.minos.application`, comme `HostDiagnosticsService`) :

- `static ProjectPathMappingService forHome(Path home)` obtient le port par `MinosApplicationComposers.resolve().projectPathMappings(home)` (ADR 0042) ; un constructeur privé au package reçoit le port (tests de `minos-application`).
- `Outcome configure(ProjectPathMapping desired) throws IOException` : `CREATED` (aucune correspondance, enregistrée), `UNCHANGED` (même correspondance), `CONFLICT` (autre correspondance, rien n'est écrit). Le type `Outcome` est une énumération imbriquée, en type applicatif.

`DockerRuntimeBootstrap` garde la validation des arguments, les messages et les codes de sortie ; il appelle `ProjectPathMappingService.forHome(home)` au même moment qu'avant (après validation), donc la composition n'est pas résolue pour une invocation invalide. Il ne nomme plus aucun type du moteur : l'entrée `registry.ProjectPathMappings` quitte les deux tables A11.

Alternative écartée : ajouter `configureProjectPaths` à `MinosApplication`. Le point d'entrée tourne **avant** que MINOS n'ouvre l'état métier (`DockerRuntimeBootstrap.java:16-18`) ; ouvrir l'application (backend de stockage, registre) changerait ce qui est créé dans `MINOS_HOME` au démarrage du conteneur.

### D2 — Quatre accesseurs privés au package, un cliquet pour les quinze autres

`gitIntelligence()`, `providerCatalog()`, `scipArtifactImporter()` et `hostedControlPlaneService()` perdent `public` : leurs seuls appelants de production sont dans `com.minos.application`. Tests adaptés, à assertion équivalente :

- `MinosApplicationTest` : l'absence de mode hébergé se lit par `teamControlPlane().isPresent()` ; l'identité de `gitIntelligence()` est déjà couverte par `LocalProjectGitQuery` (test de `minos-application`), l'assertion est retirée ;
- `SnapshotScalabilityBenchmark` : `application.compositionRoot().scipArtifactImporter()`, l'importeur par défaut que l'assembleur retient sans surcharge (`MinosApplicationAssembler.java:93-94`) ;
- `ProviderCatalogPortTest` : l'ordre du catalogue est lu sur `application.compositionRoot().providerCatalog()`, que l'application retient sans surcharge (`MinosApplicationAssembler.java:90-91`), la vue `ProviderPlatformService` restant comparée comme au HEAD ;
- `MinosApplicationMcpBackendM27Test` : l'amorçage passe par `teamControlPlane().orElseThrow()` (`bootstrap`, `createWorkspace`), la façade que consomme le MCP.

Les 15 autres restent publics : 13 servent `ProjectIndexingUseCase.of` (dont `projectRegistry` et `snapshotStore`, partagés avec `RemoteIndexingUseCase` et `KnowledgeExportUseCase`), `indexerDescriptors` sert `RemoteIndexingUseCase.of`, et `runtimeObservationStore()` amorce sur disque un test MCP (`LazyApplicationTest` de la CLI, qui différait `MinosApplication::gitIntelligence`, diffère `architectureQuery`). Déplacer le câblage de production (`of`) dans `com.minos.application` est écarté : `com.minos.application.indexing` importe déjà `com.minos.application` (`ProviderRuntimeSummary`, `ProjectIndexStateReconciler`), l'inverse créerait un cycle de packages refusé par A8.

Cliquet, dans la règle A11 : `MINOS_APPLICATION_ENGINE_ACCESSORS` (`check-module-boundaries.py`) liste les 15 accesseurs avec leur type rendu. `check_minos_application_accessors` compare cette table à `minos_application_accessors()` : un accesseur hors table, un type rendu différent ou une entrée sans accesseur échouent, avec le conseil de passer par une façade applicative. La fonction est appelée par `main` (test `test_main_runs_the_ratchet_rules_a11_to_a14`) et imprime son compte.

La racine de composition rend des ports du moteur sous un type applicatif (`MinosApplicationComposer`) : `app.compositionRoot().gitIntelligence()` contournerait la table. Aucune surface de production n'y accède une fois `DockerRuntimeBootstrap` passé par la façade ; la même fonction refuse donc tout fichier de production d'une surface qui appelle `compositionRoot()` ou nomme `MinosApplicationComposer(s)` (commentaires et littéraux ignorés).

Alternative écartée : une règle ArchUnit. A11 lit déjà ces accesseurs dans la source ; une seconde lecture en bytecode doublerait la table sans voir davantage.

### D3 — La dépendance `compile` vers le moteur reste

Elle ne peut passer en `runtime` ni disparaître pour aucune des trois surfaces :

1. le code de production de chacune nomme encore des types du moteur, justifiés par une décision : les modèles de lecture `query.*` (ADR 0057, point 6 ; API et CLI) et `runtime.MinosVersion` (ADR 0068 § 3 ; CLI et MCP). La compilation les exige ;
2. l'ADR 0058 impose de déclarer une dépendance utilisée directement (règle A2 : POM et sources concordent) ;
3. même sans usage direct, `minos-application` exporte le moteur en `compile` : une déclaration `runtime` ne retirerait pas le moteur du classpath de compilation, elle masquerait seulement l'usage.

Le retrait passerait par la publication de ces types hors du moteur (`query.*` dans un module de contrats, `MinosVersion` dans le domaine), donc par la révision de l'ADR 0068 § 3 ou de l'ADR 0057, hors de ce changement.

### Direction des dépendances (ADR 0068)

Aucune arête de module ajoutée ni retirée : `minos-cli` → `minos-application` existe, la façade dépend de `minos-engine` (port) et `minos-domain` (record), comme `MinosApplicationComposer`. Aucun nouveau package, aucune nouvelle racine (A14).

## Capacités

Aucune capacité produit nouvelle. Le plan Docker garde sa capacité qualifiée (création idempotente, refus explicite) ; ses messages et codes sont inchangés.

## Plateformes

Windows et Linux : la normalisation des racines (`N:\workspace-dev` égale `N:/workspace-dev`) est faite par `ProjectPathMapping` (domaine) et n'est pas touchée ; `DockerRuntimeBootstrapTest` l'exerce sur les deux jobs `Verify`.

## Décisions en attente du propriétaire

Aucune : la portée `compile` (D3) est une conséquence des ADR en vigueur ; la publier hors du moteur serait un autre changement, à ouvrir si le propriétaire le souhaite.

## Risks / Trade-offs

- Rupture de source et binaire pour un code externe qui appelait l'un des quatre accesseurs : `MinosApplication` est une classe interne, la rupture est inscrite dans `docs/user/java-api.md` § Ruptures avec la façade de remplacement.
- Couverture SonarCloud du nouveau code de `minos-application` (80 %) : `ProjectPathMappingServiceTest` couvre les trois résultats, les refus de `null` et `forHome` sans racine de composition (échec explicite).
