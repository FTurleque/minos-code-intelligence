# Proposal

## Why

Constat **AUD-ARC-02** de l'audit `docs/project/audits/2026-11-15/` (sprint 7, sévérité moyenne, statut **partiel** à la vérification du 2026-11-20, HEAD `535a5017`). Le changement `injecter-les-collaborateurs-des-cas-d-usage` (PR #787, archivé le 2026-11-19) a fait recevoir leurs collaborateurs aux cas d'usage et les a exposés sur la façade ; son groupe 4 a été reporté par le propriétaire le 2026-11-19, « à reprendre dans un changement dédié à la rupture ». Ce changement est celui-là.

Au HEAD, `MinosApplication` (`minos-application`) garde 41 méthodes publiques d'instance, dont `compositionRoot()` et 15 accesseurs qui rendent un type de `minos-engine`. Aucune source de production hors du package `com.minos.application` ne les appelle : seuls des tests de sept modules les lisent. Seuls une table à cliquet du garde (`MINOS_APPLICATION_ENGINE_ACCESSORS`, dont le commentaire est périmé) et la règle A11 en limitent l'usage. Le report n'est porté par aucune décision acceptée : ARCH-0087, qui propose le retrait, est `Proposed` et n'est porté par aucun changement actif.

## What Changes

- **Décision du propriétaire en tête** (design, D1) : retrait en une version (recommandé), dépréciation puis retrait, ou report écrit dans ARCH-0087. Les exigences et les tâches qui suivent décrivent l'option recommandée ; une autre option réécrit le delta avant toute implémentation.
- **RUPTURE** (classe interne `com.minos.application.MinosApplication`, pas `com.minos.api`) : `compositionRoot()` et les 15 accesseurs typés moteur cessent d'être publics. Trois d'entre eux n'ont aucun appelant hors de la classe (`incrementalIndexingPlanner()`, `invalidationService()`, `indexerRegistry(String)`) : ils sont supprimés ou rendus privés (D3). Les 13 autres deviennent privés au package : `LocalProjectOperations` et `ProviderPlatformService`, du même package, les appellent toujours.
- Les tests des autres modules (54 fichiers, 216 appels recensés) lisent un port de l'application par une classe d'accès de test publiée dans le `test-jar` de `minos-application`, admise en portée `test` seulement (D2).
- Garde A11 (D4) : la table `MINOS_APPLICATION_ENGINE_ACCESSORS` est vidée et reste un cliquet à zéro ; le garde refuse une méthode publique d'instance qui rend `MinosApplicationComposer` et le `test-jar` de `minos-application` hors portée `test` ; son commentaire périmé disparaît avec la table.
- ARCH-0087 passe `Accepted` par le propriétaire (D5) ; ARCH-0057 reçoit une entrée « Mise en œuvre » (point 6) ; `docs/user/java-api.md` documente la rupture et lève l'ambiguïté sur le régime de `MinosApplication`.

Aucune sortie de la CLI, de l'API Java `com.minos.api`, du MCP, du plugin IntelliJ ni de NEXUS ne change.

## Capabilities

### New Capabilities

Aucune.

### Modified Capabilities

- `frontieres-de-modules` : deux exigences ajoutées (façade sans type du moteur ni racine de composition ; accès de test aux ports) et une troisième (aucune sortie publique ne change) ; exigences modifiées « Les accesseurs de MinosApplication typés moteur sont tenus par un cliquet » (table vide, 0 accesseur) et « Un accesseur de MinosApplication appelé par une surface compte comme un usage du type rendu » (scénarios sur un arbre de test, les accesseurs réels n'étant plus publics).

## Hors périmètre

- Les accesseurs publics qui rendent un service applicatif (`projectQueryService()`, `semanticIndexService()`…), un type du domaine (`semanticVectorStore()`), un cas d'usage (`projectIndexing()`, `remoteIndexing()`…) ou un type JDK (`home()`, `storageBackendId()`) : inchangés.
- Le `Builder` public et ses surcharges, qui nomment des types du moteur : inchangés.
- `com.minos.api` : aucune signature ne change, `LocalMinosApi(MinosApplication)` compris.
- Les adaptateurs embarqués (AUD-ARC-01, décision 1 d'ARCH-0087, déjà mise en œuvre) ; la description du garde découpé (AUD-ARC-12, changement `decrire-le-garde-de-frontieres-decoupe`) ; les constats AUD-ARC-04 et AUD-ARC-07, qui dépendent de celui-ci et sont fermés.
- Les programmes historiques de `scripts/history/` (`M16McpSustainedBenchmark.java`, `M16ScaleBenchmark.java`, `M21SemanticScaleProbe.java`), qui appellent `projectRegistry()` et `snapshotStore()` : relevés figés, hors du reactor, non recompilés.
- Le plugin IntelliJ, client du protocole CLI, sans dépendance `com.minos:*`.

## Statut des informations

| Affirmation | Statut | Source ou preuve |
|---|---|---|
| 41 méthodes publiques d'instance avant `Builder`, dont `compositionRoot()` et 15 typées moteur | [Existant vérifié] | `minos-application/src/main/java/com/minos/application/MinosApplication.java:264-424`, `:447` ; `scripts/architecture/module_boundary_ratchets.py:43-59` |
| Aucune source de production hors de `com.minos.application` n'appelle ces 16 méthodes | [Existant vérifié] | recherche au HEAD ; deux appelants de production, du même package : `LocalProjectOperations.java:53-59`, `ProviderPlatformService.java:37` |
| 54 fichiers de test hors du package appellent l'une d'elles (216 appels, sept modules) | [Existant vérifié] | recherche au HEAD (design, « État vérifié au HEAD ») ; décompte par motif textuel, non compilé |
| Le commentaire du garde est périmé (« they serve the use cases… », « Kept for a test only ») | [Existant vérifié] | `module_boundary_ratchets.py:38-42`, `:55` |
| Le report du groupe 4 n'est porté par aucune décision acceptée | [Existant vérifié] | `docs/architecture/adr/ARCH-0087-adaptateurs-embarques-et-facade-reduite.md:10`, `:41-44` ; `openspec/changes/` sans changement actif sur le sujet avant celui-ci |
| `MinosApplication` est une classe interne dont les ruptures sont documentées | [Existant vérifié], ambigu | `docs/user/java-api.md:402-413` (précédent : quatre accesseurs retirés) ; contredit en partie par `docs/user/java-api.md:441-442` |
| Les surfaces publiques sont additives ; une méthode publique de `MinosApplication` ne disparaît pas sans décision écrite | [Exigence] | `AGENTS.md`, « Architecture » ; `.claude/rules/adr.md` |
| Retrait en une version, accès de test par `test-jar` | [Proposition] | design, D1 et D2 (attendent le propriétaire) |

## Impact

- **Modules du reactor** : `minos-application` (façade, `pom.xml` pour le `test-jar`, classe d'accès de test et son test) ; tests de `minos-api`, `minos-app`, `minos-bootstrap`, `minos-cli`, `minos-explorer`, `minos-mcp`, `minos-nexus` et leurs `pom.xml` (dépendance `test-jar` en portée `test`). Aucune dépendance Maven de production ajoutée ; direction ARCH-0068 inchangée.
- **Surfaces publiques** (CLI, API Java `com.minos.api`, MCP, IntelliJ, NEXUS) : sorties inchangées. **RUPTURE** de source et binaire pour un code tiers qui appelait l'un des 16 accesseurs de `MinosApplication`, documentée dans `docs/user/java-api.md` avec le remplacement.
- **ADR** : aucun créé. ARCH-0087 complété et passé `Accepted` par le propriétaire (tâche manuelle) ; ARCH-0057 « Mise en œuvre » (point 6) ; index `docs/architecture/adr/README.md` et `docs/architecture/arc42/09-decisions.md`.
- **Gates à rejouer** : `scripts/architecture/check-module-boundaries.py` (A2, A3, A11) et ses auto-tests (`test_check_module_boundaries_ratchets.py:348` affirme 15 entrées) ; `check-runtime-dynamic-consistency.py` (`:104-105` exige `runtimeObservationStore()` dans `MinosApplication.java`) ; `check-remote-distributed-consistency.py` (`:184`) ; `check-hosted-control-plane-consistency.py` ; `check-semantic-retrieval-consistency.py` ; `check-vertical-decomposition-consistency.py` et `check-p0-p2.py` (lisent `MinosApplicationTest.java` et des tests M28 à modifier) ; `check-comment-language.py` ; `check-ci-wiring.py` ; `ModuleArchitectureTest`. JaCoCo : aucun scope de `check-jacoco.py` ne cite `MinosApplication` ; `check-jacoco.py` est rejoué sur le rapport agrégé.
- **Plateformes** : Windows et Linux, sans branche propre à un système ; la voie WSL2 reste offerte sous Windows seulement, inchangée.
- **Documentation durable et diagrammes** : `docs/user/java-api.md`, `docs/user/release-notes/unreleased.md`, `docs/developer/public-surfaces.md` (le diagramme de la composition partagée liste `providerRuntimeManager()`), `docs/architecture/diagrams/capacites/frontieres-de-modules.md`, ARCH-0087, ARCH-0057, suivi de l'audit.

## Questions ouvertes

- Bloquante pour les groupes 2 à 5 : D1 (option de retrait) et D2 (forme de l'accès de test). Le groupe 1 (correction du commentaire du garde, preuves de l'état) ne dépend d'aucune décision.
