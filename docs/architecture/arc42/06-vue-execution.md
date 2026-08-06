# Section 6 — Vue d'exécution

> Preuves : `MinosCli.java` USAGE, `ScipJavaProcessPlanFactory.java`,
> `IncrementalIndexingCoordinator.java`, `MinosMcpServer.java`, ADR-0006, ADR-0014,
> ADR-0037, `SnapshotIntegrityService.java`.

Les noms des participants sont strictement cohérents avec la section 5.

---

## 6.1 Scénario nominal — Indexation et requête de symbole

Ce scénario décrit le flux typique : un développeur demande d'indexer un projet Java,
puis interroge la définition d'un symbole.

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur «Person»
    participant Launcher as MinosLauncher «Container»
    participant CLI as minos-cli «Container»
    participant App as minos-application «Container»
    participant Engine as minos-engine «Container»
    participant ProviderSCIP as minos-provider-scip «Container»
    participant Runtime as minos-runtime-local «Container»
    participant Storage as minos-storage-local «Container»

    Dev->>Launcher: minos index --project /repo
    Launcher->>CLI: IndexCommand.run()
    CLI->>App: ProjectDiscoveryService.discover()
    App->>Engine: IndexerRegistry.negotiate(requirements)
    Engine-->>App: IndexerNegotiationResult [SCIP_JAVA]
    App->>ProviderSCIP: ScipIndexerCatalog.startIndexing()
    ProviderSCIP->>Runtime: ProcessIndexerExecutor.execute(ScipJavaProcessPlanFactory)
    Runtime-->>ProviderSCIP: artefact .scip produit
    ProviderSCIP->>ProviderSCIP: ScipIngestionAdapter.ingest()
    ProviderSCIP->>Storage: SnapshotRepository.promoteAtomically(snapshot)
    Storage-->>ProviderSCIP: snapshot promu (atomique)
    ProviderSCIP-->>App: IndexingResult [SUCCESS]
    App-->>CLI: JSON result
    CLI-->>Dev: { "status": "indexed", "symbols": N }

    Dev->>Launcher: minos find-symbol --name "MyClass"
    Launcher->>CLI: FindSymbolCommand.run()
    CLI->>App: CodeSearchService.search()
    App->>Engine: SymbolQueryService.find()
    Engine->>Storage: SnapshotQueryView.query()
    Storage-->>Engine: [Symbol + Evidence]
    Engine-->>App: SymbolResult
    App-->>CLI: JSON
    CLI-->>Dev: { "symbols": [...] }
```

---

## 6.2 Scénario d'erreur — Provider SCIP indisponible

Ce scénario illustre le comportement lorsque l'outil `scip-java` est absent du PATH.

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur «Person»
    participant Launcher as MinosLauncher «Container»
    participant CLI as minos-cli «Container»
    participant App as minos-application «Container»
    participant Runtime as minos-runtime-local «Container»

    Dev->>Launcher: minos index --project /repo
    Launcher->>CLI: IndexCommand.run()
    CLI->>App: ProjectDiscoveryService.discover()
    App->>Runtime: CommandLocator.locate("scip-java")
    Runtime-->>App: CommandNotFound
    App-->>CLI: IndexingResult [ERROR, limitation="scip-java not found in PATH"]
    CLI-->>Dev: exit 1\n{ "error": "scip-java not found", "action": "run minos tools install" }

    Dev->>Launcher: minos doctor
    Launcher->>CLI: DoctorCommand.run()
    CLI->>Runtime: CommandLocator.locateAll(providers)
    Runtime-->>CLI: [scip-java: MISSING, scip-typescript: OK]
    CLI-->>Dev: MINOS READY=false\nAction: install scip-java
```

---

## 6.3 Scénario d'exploitation — Démarrage du serveur MCP STDIO

Ce scénario décrit le démarrage de `minos mcp` en backend natif et la première requête MCP.

```mermaid
sequenceDiagram
    autonumber
    actor Agent as Agent IA «Person»
    participant Launcher as MinosLauncher «Container»
    participant Router as BackendRouter «Component»
    participant MCP as minos-mcp «Container»
    participant App as minos-application «Container»
    participant Storage as minos-storage-local «Container»

    Launcher->>Router: loadBackendConfig(MINOS_HOME/runtime/backend.properties)
    Router-->>Router: backend=native (format=1)
    Router->>App: MinosApplication.open(home)
    App->>Storage: SnapshotRepository.loadActive()
    Storage-->>App: snapshot actif chargé
    App-->>Router: MinosApplication ready
    Router->>MCP: MinosMcpServer.start(application)
    MCP-->>Launcher: MCP STDIO ready

    Agent->>MCP: MCP initialize request (JSON-RPC 2.0 / STDIO)
    MCP-->>Agent: capabilities list (tools read-only)
    Agent->>MCP: tools/call minos_find_symbol { name: "UserService" }
    MCP->>App: MinosMcpApplicationTools.findSymbol()
    App->>Storage: SnapshotQueryView.query()
    Storage-->>App: [Symbol + Evidence]
    App-->>MCP: SymbolResult bounded JSON
    MCP-->>Agent: { "symbols": [...] }
```

---

## 6.4 Scénario d'exploitation — Bascule vers backend Docker

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur «Person»
    participant Launcher as MinosLauncher «Container»
    participant Router as BackendRouter «Component»
    participant Docker as Docker Daemon «Software System»
    participant Container as minos-mcp (in Docker) «Container»

    Dev->>Launcher: minos mcp
    Launcher->>Router: loadBackendConfig(backend.properties)
    Router-->>Router: backend=docker
    Router->>Docker: docker version (probe)
    Docker-->>Router: version OK
    Router->>Docker: inspect container "minos" (Running?)
    Docker-->>Router: Running=true
    Router->>Docker: docker exec -i minos com.minos.mcp.MinosMcpServer
    Docker->>Container: spawn MinosMcpServer
    Container-->>Router: STDIO prêt
    Router-->>Launcher: session STDIO relayée
    Note over Router: Aucun MinosApplication ouvert côté hôte
```
