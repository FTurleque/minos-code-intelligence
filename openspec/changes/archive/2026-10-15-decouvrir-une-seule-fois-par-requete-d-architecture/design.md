# Design

## Context

Sprint 4 de l'audit du 10 octobre 2026 : **AUD-PERF-05**. HEAD analysé : `2d1cc214` (`develop`).

## État vérifié au HEAD

### AUD-PERF-05 — confirmé, plus large que le constat

- `minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java:176-178` : `getArchitectureIntelligence(request.project())` puis `getModuleContext(request.project(), request.module()).module().id()`.
- `minos-application/src/main/java/com/minos/architecture/LocalProjectArchitectureQuery.java:59-64` : les deux méthodes appellent `loadContext` ; `:81-86` : `snapshotStore.loadActiveKnowledge` et `discoveryService.discover(project.rootPath())`.
- **Site non cité par l'audit** : `minos-cli/src/main/java/com/minos/cli/command/ArchitectureCommand.java:40-43`, même enchaînement.

**Non mesuré pendant cette analyse** : les durées de découverte (12,7 à 36,3 s) viennent de l'audit d'architecture de septembre, § A6.3.6.

### Écarts avec l'action proposée par l'audit

1. **Deux surfaces, pas une.** Corriger le seul backend MCP laisserait `minos architecture --format mermaid --module …` au double coût.
2. **`ProjectArchitectureQuery` est un port.** L'action propose d'« exposer `moduleContext(view, module)` » ou d'ajouter `getArchitectureGraph(project, module)`. Une méthode abstraite casserait les autres implémentations et les faux de test : elle est ajoutée en `default`, redéfinie par l'implémentation locale.
3. **L'erreur de module inconnu doit rester la même.** Aujourd'hui c'est `getModuleContext` qui la lève ; la nouvelle résolution rend la même erreur publique (classe et message), prouvée par un test par surface.

## Goals / Non-Goals

**Goals** : une découverte et un chargement de snapshot par requête de graphe avec module, sur MCP et CLI ; sorties et erreurs inchangées.

**Non-Goals** : cacher ; changer le rendu Mermaid ou DOT.

## Decisions

### D1. Une opération du port qui rend la vue et le module résolu

Le port gagne une opération qui charge le contexte **une fois** et rend la vue d'architecture avec l'identifiant du module demandé. Implémentation `default` : l'enchaînement actuel (correct, deux chargements) ; `LocalProjectArchitectureQuery` la redéfinit avec un seul `loadContext`. Les deux surfaces appellent cette opération.

Alternative écartée : faire résoudre le module par la surface en parcourant la vue. Cela dupliquerait dans deux surfaces une règle de résolution (identifiant, nom, ambiguïté) qui appartient au service.

### D2. La preuve est un décompte

`ProjectDiscoveryService` espion : un appel de graphe Mermaid avec module → 1 découverte, 1 chargement de snapshot, côté MCP et côté CLI. Comparaison de la sortie avec les goldens existants.

## Direction des dépendances

`surfaces → application` (inchangé) ; aucune surface n'atteint un adaptateur.

## Qualification des capacités

Aucune capacité nouvelle ; comportement observable inchangé.

## Windows et Linux

Aucune différence.

## Risks / Trade-offs

- Divergence entre la résolution de module de la nouvelle opération et celle de `getModuleContext` : les deux partagent le même code de résolution dans le service, et un test compare les deux chemins.
- Un adaptateur de port hors dépôt garderait le double coût sans le savoir : acceptable, il reste correct.

## Relevé de la tâche 1.1 (2026-10-13)

Relu dans l'arbre de la branche `fix/decouvrir-une-seule-fois-par-requete-d-architecture`, avant toute modification.

**Littéraux des gates sur `MinosApplicationMcpBackend.java`** (aucun ne porte sur le graphe d'architecture) :

- `scripts/quality/check-hosted-control-plane-consistency.py:130-132` exige `System.getenv("MINOS_TEAM_TOKEN")`, `hosted().tenant(hostedToken())`, `hosted().audit(hostedToken(), limit)` et `MINOS team mode is disabled`.
- `scripts/quality/check-runtime-dynamic-consistency.py:116-118` exige `runtimeIntelligenceService().listSessions`, `runtimeIntelligenceService().report`, `runtimeIntelligenceService().symbolReport`.
- `scripts/remediation/check-post-mne.py:252-253` exige `renderProgramGraph` et `MinosMcpTools.MAX_RESULT_BYTES`.
- `scripts/history/m15/run-s4.ps1:149-157` (historique, hors CI) exige `application.architectureQuery()`, conservé par le correctif.

**`getModuleContext` et `getArchitectureIntelligence` dans `scripts/`** : une seule occurrence, `scripts/history/m16/M16ScaleBenchmark.java:141` (`getArchitectureIntelligence`, banc historique, non compilé par le reactor). Aucun gate ne cite ces deux noms ; ils ne sont ni renommés ni retirés. `ArchitectureCommand` n'est cité par aucun script.

**Implémentations et faux de `ProjectArchitectureQuery`** :

- une seule implémentation : `minos-application/.../architecture/LocalProjectArchitectureQuery.java` ;
- un seul faux : le proxy enregistreur de `minos-cli/src/test/java/com/minos/cli/CliValidInvocationsTest.java:76`, qui note la **première** méthode du port atteinte par la CLI. Un proxy reçoit aussi les méthodes `default` : les deux lignes « graphe avec module » de ce test attendent désormais la nouvelle opération (attente mise à jour, plus une ligne « graphe sans module ») ;
- consommateurs sans implémentation : `MinosApplication`, `LocalMinosApi` (`getArchitectureGraph(project)` n'a pas de paramètre de module : **un seul** chargement, API Java hors du constat), `MinosCli`, `MinosCliRunner`, `ArchitectureCommand`, `MinosApplicationMcpBackend`, `SnapshotScalabilityBenchmark` (test de `minos-bootstrap`).

**Scope JaCoCo `mcp-mapping`** : préfixe `com/minos/mcp/` (`scripts/quality/check-jacoco.py:24`), seuils 55 % lignes / 40 % branches ; aucune classe déplacée.

## Mise en œuvre (2026-10-13)

- **Opération du port (D1)** : `ProjectArchitectureQuery.getArchitectureIntelligenceForModule(String projectIdentifier, String moduleIdentifier)`, `default`, rend un `ArchitectureModuleSelection(ArchitectureIntelligenceView view, String moduleId)` (nouveau record public de `com.minos.architecture`, module `minos-application`). `LocalProjectArchitectureQuery` la redéfinit : un `loadContext`, puis `ArchitectureIntelligenceService.moduleContext(view, module).module().id()`, c'est-à-dire le code de résolution de `getModuleContext` lui-même (mêmes refus : inconnu, ambigu, blanc).
- **Surfaces** : `MinosApplicationMcpBackend.architectureGraph` et `ArchitectureCommand` appellent l'opération quand un module est demandé ; sans module, l'appel reste `getArchitectureIntelligence` (déjà un seul chargement).
- **Décompte (D2)** : `ProjectDiscoveryService` est `final` ; l'espion est un `ProjectDetector` ajouté en tête de la liste par défaut, qui ne reconnaît aucun module et compte la visite de la racine (une par découverte). Le store espion est un proxy de `CodeKnowledgeSnapshotStore` qui compte `loadActiveKnowledge`.
- **Écart avec le design** : aucun sur le comportement. Précision : la « comparaison des deux chemins » du § Risques est faite dans `ArchitectureModuleSelectionTest` (module `minos-application`, qui porte le code ajouté) et rejouée par chaque test de surface, qui compare la sortie au rendu obtenu par l'ancien enchaînement.
