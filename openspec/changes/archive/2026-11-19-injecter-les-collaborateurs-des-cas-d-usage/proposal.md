# Proposal

## Why

Constat **AUD-ARC-02** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité moyenne ; suite de la réserve résiduelle d'AUD-ARC-01 du 2026-10-10) : `MinosApplication` sert de localisateur de services. Elle expose 36 méthodes publiques d'instance, dont 15 rendent un type de `minos-engine` et une (`compositionRoot()`) rend la racine de composition. Ces accesseurs restent publics parce que trois cas d'usage de sous-packages (`ProjectIndexingUseCase`, `RemoteIndexingUseCase`, `KnowledgeExportUseCase`) reçoivent l'application entière et y puisent leurs collaborateurs. Seul un cliquet (A11) empêche une surface d'atteindre les ports de persistance. Le sprint 8 (AUD-ARC-04, AUD-ARC-07) dépend de ce constat.

## What Changes

- Trois types que les cas d'usage importent depuis le package de la façade (`ProjectIndexStateReconciler`, `PublishedSnapshotRetention`, `ProviderRuntimeSummary`) passent dans `com.minos.application.indexing` : les packages des cas d'usage ne dépendent plus de `com.minos.application`.
- La façade expose les cas d'usage eux-mêmes : indexation locale, indexation par la voie WSL2, indexation distante, export de connaissance. Ils sont construits dans `com.minos.application` par injection de leurs `Dependencies`, à la première demande (rien n'est créé sur disque à l'ouverture).
- **RUPTURE** (classes internes de `minos-application`, pas `com.minos.api`) : les fabriques statiques `of(MinosApplication)` et `viaWsl(MinosApplication, …)` des trois cas d'usage sont retirées dans le même temps que l'ajout de la façade — les garder créerait trois cycles de packages (A8). Les surfaces (`minos-cli`, `minos-nexus`) et les tests passent par la façade.
- **RUPTURE** (classe interne `com.minos.application.MinosApplication`) : `compositionRoot()` et les 15 accesseurs typés moteur cessent d'être publics. Décision écrite exigée : ADR du domaine `ARCH` (prochain numéro libre à l'écriture), commun avec le changement `cabler-les-adaptateurs-embarques-par-la-racine-de-composition`.
- Les tests des autres modules qui lisent un port de l'application passent par un accès de test publié dans le `test-jar` de `minos-application`.
- Le cliquet A11 : la table `MINOS_APPLICATION_ENGINE_ACCESSORS` est vidée ; le garde refuse tout accesseur public typé moteur, tout accesseur public rendant la racine de composition, et le `test-jar` de l'application hors portée `test`.

Aucune sortie de la CLI, de l'API Java `com.minos.api`, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : exigences ajoutées sur l'injection des cas d'usage, la façade sans accesseur typé moteur ni racine de composition, et l'accès de test ; exigence « Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet » modifiée (son scénario « Table à jour au HEAD » annonce 0 accesseur au lieu de 15).

## Hors périmètre

- Les adaptateurs embarqués (AUD-ARC-01) : changement `cabler-les-adaptateurs-embarques-par-la-racine-de-composition`.
- Les accesseurs qui rendent un service applicatif (`projectQueryService()`, `semanticIndexService()`…) ou un type du domaine (`semanticVectorStore()`) : inchangés.
- Le `Builder` public et ses surcharges : inchangés.
- La source unique des tables du garde et de `ModuleArchitectureTest` (AUD-ARC-04) et la découpe d'`IndexingRunExecutor` (AUD-ARC-09) : sprint 8.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| 36 méthodes publiques d'instance, 15 typées moteur, `compositionRoot()` publique | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/MinosApplication.java:256-352` ; `scripts/architecture/check-module-boundaries.py:599-616` |
| Les trois cas d'usage tirent leurs collaborateurs de la façade | [Existant vérifié] | `ProjectIndexingUseCase.java:207-250`, `RemoteIndexingUseCase.java:99-116`, `KnowledgeExportUseCase.java:60-63` |
| Les packages des cas d'usage importent `com.minos.application` par six lignes | [Existant vérifié] | `ProjectIndexingUseCase.java:3-5`, `IndexPlanSummary.java:3`, `RemoteIndexingUseCase.java:3-4`, `KnowledgeExportUseCase.java:3` |
| Aucun cycle de packages n'est toléré | [Existant vérifié] | `check-module-boundaries.py:1453` (`KNOWN_PACKAGE_CYCLES` vide) |
| Aucune source de production d'une surface n'appelle un accesseur typé moteur | [Existant vérifié] | recherche au HEAD ; règle A11 |
| Les surfaces publiques sont additives ; `MinosApplication` ne perd pas de méthode sans décision écrite | [Exigence] | `AGENTS.md`, « Architecture » ; consigne du sprint |
| Les accesseurs deviennent non publics, avec accès de test par `test-jar` | [Proposition] | design, D1 et D2 (attendent le propriétaire) |
| Les cas d'usage sont construits à la première demande | [Proposition] | design, D3 |

## Impact

- **Modules du reactor** : `minos-application` (façade, trois cas d'usage, trois types déplacés, `test-jar`), `minos-cli` (`LocalAutonomousIndexOperations`, `LocalRemoteIndexOperations`), `minos-nexus` (`NexusExportService`) ; tests de `minos-api`, `minos-app`, `minos-bootstrap`, `minos-cli`, `minos-explorer`, `minos-mcp`, `minos-nexus`.
- **Surfaces publiques** (CLI, API Java, MCP, IntelliJ, NEXUS) : sorties inchangées. **RUPTURE** de source et binaire pour un code tiers qui appelait les fabriques `of(MinosApplication)`, les accesseurs retirés de `MinosApplication`, ou qui importait l'un des trois types déplacés : documentée dans `docs/user/java-api.md`, avec le remplacement.
- **ADR** : à écrire (domaine `ARCH`, prochain numéro libre à l'écriture), commun avec AUD-ARC-01 ; ARCH-0057 point 6 reçoit une entrée « Mise en œuvre ».
- **Gates à rejouer** : `check-module-boundaries.py` (A3, A8, A11), `test_check_module_boundaries.py`, `check-remote-distributed-consistency.py` (`:141-143` exige deux appels de `compositionRoot` dans `RemoteIndexingUseCase.java`), `check-polyglot-provider-consistency.py`, `check-post-mne.py`, `check-mnd.py`, `check-mne.py`, `check-hosted-control-plane-consistency.py`, `check-runtime-dynamic-consistency.py` ; `ModuleArchitectureTest` ; scopes JaCoCo qui citent `RemoteIndexingUseCase` et `LocalRemoteIndexOperations` (`check-jacoco.py:152`).
- **Plateformes** : Windows et Linux — la voie WSL2 reste propre à Windows, sans changement de comportement.
- **Documentation durable et diagrammes** : `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, `docs/user/java-api.md`, index des ADR.

## Questions ouvertes

- Bloquante pour le groupe 4 des tâches : D1 (retrait des accesseurs publics) et D2 (accès de test). Les groupes 1 à 3 sont fusionnables sans cette décision ; le groupe 2 retire déjà les fabriques `of(MinosApplication)` (rupture de classes internes, imposée par A8).
