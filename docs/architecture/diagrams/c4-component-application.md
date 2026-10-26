# Diagramme — Composants de `minos-application`

> Relu contre le code le 2026-10-07. `MinosApplication` expose chaque service par un accesseur
> (`projectQueryService()`, `architectureQuery()`, `impactQuery()`, `programGraphService()`,
> `workspaceIntelligence()`, `runtimeIntelligenceService()`, `semanticSearchService()`, etc.) ; les flèches de la
> façade sont ces accesseurs. Les services d'indexation et le plan de contrôle tenant vivent dans `minos-engine`
> depuis l'ADR-0044 ; les surfaces consomment le plan de contrôle par la façade `TeamControlPlane`
> (`teamControlPlane()`). Sources : `MinosApplication.java`, packages `architecture`, `impact`, `context`,
> `program/analysis`, `workspace`, `application/semantic`, `application/dynamic`, `application/team` ; ADR-0044, ADR-0045.

```mermaid
flowchart TB
    facade["<b>MinosApplication</b><br/>façade ouverte par MinosApplication.open(home)"]

    queries["<b>Requêtes</b><br/>ProjectQueryService · ProjectArchitectureQuery<br/>ProjectImpactQuery · ProgramGraphService<br/>WorkspaceIntelligenceService · RuntimeIntelligenceService"]
    semantic["<b>Sémantique</b><br/>SemanticIndexService · SemanticSearchService<br/>HybridSearchService · HybridContextBuilder"]
    engine["<b>Exposés depuis minos-engine</b><br/>ProjectDiscoveryService · HostedControlPlaneService"]
    indexing["<b>Cas d'usage d'indexation</b><br/>ProjectIndexingUseCase (com.minos.application.indexing)"]
    team["<b>Plan de contrôle de l'équipe</b><br/>TeamControlPlane (com.minos.application.team)"]

    facade --> queries
    facade --> semantic
    facade --> engine
    facade --> indexing
    facade --> team
    team --> engine
```

Dépendances internes relevées dans le code : `ProjectQueryService` → `CodeSearchService` ;
`ProjectArchitectureQuery` est réalisée par `LocalProjectArchitectureQuery` (qui utilise
`ArchitectureIntelligenceService`) ; `ProjectImpactQuery` par `LocalProjectImpactQuery` (qui utilise
`ImpactAnalysisService`) ; `ProgramGraphService` utilise `ProgramGraphComposer`. Les autres liens entre
services n'ont pas été relevés.
