# Architecture interne

## Vue générale

MINOS sépare acquisition, normalisation, persistance, intelligence et exposition.

```mermaid
classDiagram
    class MinosLauncher
    class MinosCli
    class LocalProjectRegistry
    class FileSymbolSnapshotStore
    class LocalProjectSymbolQuery
    class LocalProjectArchitectureQuery
    class LocalProjectImpactQuery
    class MinosApi
    class MinosMultiRepositoryApi
    class MinosMcpServer
    class NexusExportService

    MinosLauncher --> MinosCli
    MinosLauncher --> LocalProjectRegistry
    MinosLauncher --> FileSymbolSnapshotStore
    MinosCli --> LocalProjectSymbolQuery
    MinosCli --> LocalProjectArchitectureQuery
    MinosCli --> LocalProjectImpactQuery
    LocalProjectSymbolQuery --> FileSymbolSnapshotStore
    LocalProjectArchitectureQuery --> FileSymbolSnapshotStore
    LocalProjectImpactQuery --> FileSymbolSnapshotStore
    MinosMultiRepositoryApi --|> MinosApi
    NexusExportService --> LocalProjectRegistry
    NexusExportService --> FileSymbolSnapshotStore
```

Ce diagramme montre la direction des dépendances principales, pas chaque classe du dépôt.

## Couche acquisition

La découverte détecte les caractéristiques locales du projet : langages, systèmes de build, modules et racines source. Le registre donne ensuite une identité persistante au projet.

Les indexeurs externes restent des fournisseurs d’artefacts. Le cœur MINOS ne doit pas connaître leurs types spécifiques.

### SCIP

`com.minos.adapter.scip` transforme les données SCIP en modèles normalisés MINOS : symboles, occurrences et relations. La dépendance `scip-java-bindings` doit rester confinée à cette frontière.

## Couche orchestration

L’orchestration négocie les fournisseurs disponibles, exécute les plans lorsque des executors existent, stage un snapshot puis le promeut atomiquement.

```mermaid
sequenceDiagram
    participant D as Discovery
    participant N as Indexer negotiation
    participant L as IndexingLifecycleService
    participant E as IndexerExecutor(s)
    participant ST as SnapshotStager
    participant P as SnapshotPromoter
    participant SS as IndexStateStore

    D->>N: langages / capacités
    N-->>L: selections complètes
    L->>SS: état INDEXING ou REFRESHING
    loop sélection fournisseur
        L->>E: execute(request)
        E-->>L: artefact
    end
    L->>ST: stage(artefacts)
    ST-->>L: stagedSnapshotId
    L->>P: promote(project, run, snapshot)
    P-->>L: promotion atomique
    L->>SS: READY + snapshot actif
```

La CLI M9 actuelle ne prétend toutefois pas fournir un runner automatique universel : `minos index` importe explicitement un artefact SCIP déjà produit.

## Couche normalisée

Le package `domain` porte les objets indépendants des fournisseurs. Les invariants importants vivent dans les constructeurs de records, de façon à empêcher la création d’un état incohérent.

Voir [domain-model.md](domain-model.md).

## Couche persistance

Les stores persistent les snapshots et permettent de retrouver le snapshot actif. Une requête de haut niveau doit lire une vue cohérente et ne pas dépendre d’un artefact fournisseur brut.

## Intelligence

### Query

Les services `query` portent les recherches de symboles, usages et relations.

### Context

Les services `context` bornent les résultats, extraits source et budgets estimés. La recherche compacte est conçue pour fournir un contexte utile sans renvoyer toute la base.

### Architecture

Le package `architecture` dérive topologie, dépendances, concentration, centralité relative et technologies observées.

### Impact

Le package `impact` propage un changement potentiel sur le graphe observé. Son résultat est explicatif et borné ; il n’est pas une preuve d’exhaustivité runtime.

## Exposition

```mermaid
classDiagram
    class CoreServices {
      <<internal>>
      symbol queries
      relationships
      architecture
      impact
    }
    class CLI {
      <<adapter>>
      text/json
    }
    class MCP {
      <<adapter>>
      STDIO
      15 read-only tools
    }
    class MinosApi {
      <<public interface>>
      contractVersion=1
    }
    class MinosMultiRepositoryApi {
      <<public interface>>
      multiRepositoryContractVersion=1
    }
    class NexusExportService {
      <<integration>>
      JSON contract v1
    }

    CLI --> CoreServices
    MCP --> CoreServices
    MinosApi --> CoreServices
    MinosMultiRepositoryApi --|> MinosApi
    MinosMultiRepositoryApi --> CoreServices
    NexusExportService --> CoreServices
```

Les adapters d’exposition ne doivent pas devenir une nouvelle couche métier.

## Règles de dépendance

### À préserver

```text
provider-specific -> adapter -> domain/store/query -> exposure
```

### À éviter

```text
domain -> SCIP
query -> MCP SDK
architecture -> CLI
impact -> NEXUS
```

Les tests `ProviderBoundaryTest` et `NamespaceConventionTest` participent à la protection de ces frontières.

## Local-first

La persistance, la CLI et le serveur MCP sont locaux. MINOS n’impose aucun service HTTP de production. Cette propriété réduit les dépendances d’exploitation et permet une utilisation directe depuis un poste développeur ou un agent local.
