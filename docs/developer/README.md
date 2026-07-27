# Guide développeur MINOS

Ce guide décrit l’architecture interne, les invariants de domaine, les flux d’indexation, les surfaces publiques et les règles de validation du code MINOS.

## Principes structurants

MINOS suit plusieurs règles fortes :

- le domaine ne dépend pas des fournisseurs d’indexation ;
- les adaptateurs externes restent confinés à leurs packages ;
- faits, dérivations et heuristiques restent distingués ;
- provenance, preuves et confiance accompagnent les informations qui en ont besoin ;
- les couches CLI/MCP/API exposent le cœur mais ne réimplémentent pas la logique métier ;
- un nouveau snapshot n’est visible qu’après promotion ;
- une relation cross-repository n’est promue que sur une identité exacte et unique ;
- l’activité Git ne devient jamais automatiquement une mesure d’importance architecturale ;
- un score sémantique reste un signal de ranking heuristique, jamais une relation de code ;
- l’index vectoriel reste reconstruisible depuis les snapshots structurés ;
- MINOS ne sélectionne pas le contexte final de NEXUS.

## Carte des sous-systèmes

```mermaid
flowchart TB
    DISC[discovery / registry] --> ORCH[orchestration]
    INC[incremental] --> ORCH
    SCIP[adapter.scip] --> ORCH
    ORCH --> STORE[store]
    STORE --> DOMAIN[domain]
    DOMAIN --> QUERY[query / context]
    QUERY --> ARCH[architecture]
    QUERY --> IMPACT[impact]
    STORE --> SEM[semantic / hybrid]
    QUERY --> SEM
    STORE --> API[api]
    QUERY --> CLI[cli]
    QUERY --> MCP[mcp]
    SEM --> API
    SEM --> MCP
    STORE --> WORK[workspace]
    WORK --> GIT[git]
    STORE --> NEXUS[integration.nexus]
    SEM --> NEXUS
```

## Packages principaux

| Package | Responsabilité |
|---|---|
| `com.minos.discovery` | découverte de projet, langages, builds, modules |
| `com.minos.registry` | projets et workspaces persistés |
| `com.minos.orchestration` | négociation, lifecycle et promotion |
| `com.minos.incremental` | fingerprints, invalidation, plans NONE/FULL/INCREMENTAL |
| `com.minos.adapter.scip` | lecture et normalisation SCIP |
| `com.minos.domain` | symboles, relations, origine, preuves et critères |
| `com.minos.program` | modèle provider-independent des graphes de programme M19 |
| `com.minos.semantic` | documents, embeddings, recherche sémantique/hybride et budgets M20 |
| `com.minos.store` | snapshots, persistance locale et index reconstruisibles |
| `com.minos.query` | requêtes symboles/relations/tests |
| `com.minos.context` | recherche compacte, extraits et budgets |
| `com.minos.architecture` | topologie, dépendances, centralité, technologies |
| `com.minos.impact` | propagation d’impact potentielle |
| `com.minos.api` | contrats Java publics versionnés |
| `com.minos.cli` | exposition CLI stable |
| `com.minos.mcp` | exposition MCP STDIO read-only |
| `com.minos.git` | faits Git via JGit |
| `com.minos.workspace` | intelligence cross-repository |
| `com.minos.integration.nexus` | projections versionnées vers NEXUS |
| `com.minos.output` | rendus texte/JSON |

## Parcours de lecture conseillé

1. [Architecture interne](architecture.md)
2. [Modèle de domaine](domain-model.md)
3. [Indexation, lifecycle et stockage](indexing-and-storage.md)
4. [Surfaces publiques](public-surfaces.md)
5. [Intelligence sémantique et hybride M20](semantic-hybrid-intelligence.md)
6. [Multi-dépôts et Git](multi-repo-git.md)
7. [Tests et contribution](testing.md)

## Build développeur

```powershell
.\mvnw.cmd clean verify
```

La toolchain est contractualisée par Maven Enforcer : Java 24 et Maven 3.9.x.

## Règle de modification

Une évolution doit être placée au niveau architectural le plus bas qui porte réellement la responsabilité. Par exemple :

- un nouveau fournisseur d’index → adaptateur + orchestration, pas CLI ;
- une nouvelle relation métier → domaine/query, puis exposition ;
- un nouveau provider d'embeddings → `EmbeddingProvider`, sans dépendance cloud dans les services ;
- un nouveau backend vectoriel → `SemanticVectorStore`, en restant reconstruisible ;
- un nouveau transport → couche d’exposition, sans dupliquer les services ;
- une nouvelle dérivation → preuve + nature + confiance explicites.

## Documentation historique

Les ADR et documents `mX/` expliquent pourquoi les frontières existent. Ils doivent être consultés avant de modifier une décision structurante.