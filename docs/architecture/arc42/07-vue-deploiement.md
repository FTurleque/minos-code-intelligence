# Section 7 — Vue de déploiement

> Preuves : ADR-0021, ADR-0037, `minos-app/pom.xml` (shade, jar, failsafe),
> `minos-storage-postgresql/pom.xml` (DOCKER_HOST, Testcontainers),
> ADR-0035 (Windows + Linux), ADR-0027 (IntelliJ Java 21).

---

## 7.1 Environnements

| Environnement | Description |
|--------------|-------------|
| **Poste développeur Windows** | Installation principale : Advanced Installer / JPackage, `minos.exe` |
| **Poste développeur Linux / macOS** | Shaded JAR (`minos-code-intelligence-<version>-all.jar`) + wrapper script |
| **CI (GitHub Actions / pipeline local)** | Build Maven `./mvnw clean verify`, Testcontainers PostgreSQL via Docker Desktop |
| **Container Docker (opt-in)** | Backend MCP durable, indexation autonome (parité en cours — ADR-0037) |

---

## 7.2 Diagramme de déploiement — Backend natif (mode principal)

```mermaid
flowchart TB
    agent(["Agent IA<br/>client MCP"])
    ide["Plugin IntelliJ<br/>(Java 21)"]

    subgraph poste["Poste développeur (Windows ou Linux)"]
        direction TB
        subgraph jvm["Processus JVM minos"]
            direction TB
            launcher["MinosLauncher"]
            router["McpBackendRouter"]
            mcp["Serveur MCP STDIO<br/>lecture seule"]
            cli["Commandes CLI"]
            app["MinosApplication<br/>moteur, stockage local"]
        end
        home[("MINOS_HOME<br/>backend.properties, stores, tools")]
        indexers["Indexeurs scip-java,<br/>scip-typescript<br/>(sous-processus)"]
        sources[("Sources du projet")]
    end

    agent -->|"MCP STDIO"| launcher
    ide -->|"CLI JSON versionné"| launcher
    launcher -->|"minos mcp"| router
    launcher -->|"autres commandes"| cli
    router --> mcp
    mcp --> app
    cli --> app
    router -.->|"lit"| home
    app --> home
    app -->|"lance"| indexers
    indexers -->|"lisent"| sources
```

| Élément | Réalisation |
|---|---|
| Processus JVM minos | `minos-app` (JAR ombré ou `minos.exe`) ; `MinosLauncher` vient de `minos-cli`, `McpBackendRouter` de `minos-app`, le serveur MCP de `minos-mcp` |
| MinosApplication | composée par `minos-bootstrap` (ServiceLoader) ; moteur de `minos-engine`, stockage de `minos-storage-local` |
| MINOS_HOME | répertoires privés : `runtime/backend.properties`, `projects`, `indexing`, `runs`, `locks`, `workspaces`, `hosted-control-plane`, `tools` |
| Indexeurs | providers gérés sous `MINOS_HOME/tools`, lancés par `minos-runtime-local` et `minos-provider-scip` |

Éléments optionnels non dessinés : backend de stockage PostgreSQL / pgvector (`minos-storage-postgresql`, câblé par
`minos-bootstrap`), couche sémantique (`disabled | local-hash | ollama`), plan de contrôle tenant.

---

## 7.3 Diagramme de déploiement — Backend Docker (opt-in)

```mermaid
graph TB
    subgraph node_host["«node» Poste hôte (Windows / Linux)"]
        subgraph proc_minos_host["«node» Processus minos.exe (hôte)"]
            router_host["«Component»\nMcpBackendRouter\nbackend=docker"]
        end
        backend_cfg_host["backend.properties\nformat=1, backend=docker\ncontainer=minos"]
        router_host --> backend_cfg_host
    end

    subgraph node_docker["«node» Docker Daemon"]
        subgraph ctr_minos["«Container» minos (conteneur Docker)"]
            mcp_docker["«Container»\nminos-mcp\nServeur MCP STDIO"]
            app_docker["«Container»\nminos-application\nServices applicatifs"]
            storage_docker["«Container»\nminos-storage-local\nPersistance locale"]
        end
        subgraph vol_minos["«node» Volume Docker MINOS_HOME"]
            snap_docker["«database»\nSnapshots"]
        end
    end

    subgraph node_ai_d["«node» Client IA"]
        ai_client_d["Agent IA"]
    end

    ai_client_d -->|"MCP STDIO\n(via minos.exe relay)"| router_host
    router_host -->|"docker exec -i\nSTDIO relay"| mcp_docker
    mcp_docker --> app_docker
    app_docker --> storage_docker
    storage_docker --> snap_docker

    note_parité["⚠ Hypothèse à valider :\nParité fonctionnelle Docker complète\nnon encore acquise (ADR-0037 S1)"]
```

---

## 7.4 Protocoles

| Protocole | Usage | Participants |
|-----------|-------|-------------|
| STDIO / JSON-RPC 2.0 | Transport MCP | Agent IA ↔ MinosMcpServer |
| ProcessBuilder / STDIO | Lancement indexeurs | minos-runtime-local → scip-java, scip-typescript |
| JDBC | Stockage PostgreSQL (opt-in) | minos-storage-postgresql → PostgreSQL |
| JGit (in-process) | Lecture Git | minos-integration-git → dépôt Git local |
| docker exec -i | Relay STDIO Docker | McpBackendRouter → conteneur MINOS |
| Fichier local JSON | Export NEXUS | minos-nexus → orchestrateur NEXUS |
| Fichier binaire v2 | Snapshots MINOS | minos-storage-local → système de fichiers |
