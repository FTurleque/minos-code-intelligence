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
