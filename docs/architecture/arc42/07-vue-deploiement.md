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
graph TB
    subgraph node_dev["«node» Poste développeur (Windows / Linux)"]
        subgraph proc_minos["«node» Processus minos.exe / minos.jar"]
            launcher["«Component»\nMinosLauncher\nPoint d'entrée"]
            router["«Component»\nBackendRouter\nCharge backend.properties"]
            mcp_srv["«Container»\nminos-mcp\nServeur MCP STDIO"]
            cli_surf["«Container»\nminos-cli\nSurface CLI"]
            app_svc["«Container»\nminos-application\nServices applicatifs"]
            engine["«Container»\nminos-engine\nMoteur de requête"]
            domain["«Container»\nminos-domain\nModèle de domaine"]
            storage["«Container»\nminos-storage-local\nPersistance locale"]
        end

        subgraph fs_minos["«node» Système de fichiers ($MINOS_HOME)"]
            snap_store["«database»\nSnapshots (.minos/)\n(fichiers binaires v2)"]
            vec_store["«database»\nVector Store\n(fichiers .minos/)"]
            rt_obs["«database»\nRuntime Observations\n(fichiers .minos/)"]
            cp_store["«database»\nControl Plane tenant\n(chiffré AES-256-GCM)"]
            backend_cfg["backend.properties\nformat=1, backend=native"]
        end

        subgraph proc_scip_java["«node» Processus scip-java (subprocess)"]
            scip_java_bin["«node»\nscip-java binary\nProduit index.scip"]
        end

        subgraph proc_scip_ts["«node» Processus scip-typescript (subprocess)"]
            scip_ts_bin["«node»\nscip-typescript binary\nProduit index.scip"]
        end
    end

    subgraph node_git["«node» Dépôt Git local"]
        git_repo["«database»\nSources Java / TS / …"]
    end

    subgraph node_ai["«node» Client IA (Claude Code, Copilot…)"]
        ai_client["«node»\nAgent IA"]
    end

    launcher --> router
    router --> mcp_srv
    router --> cli_surf
    mcp_srv --> app_svc
    cli_surf --> app_svc
    app_svc --> engine
    engine --> domain
    engine --> storage
    storage --> snap_store
    storage --> vec_store
    storage --> rt_obs
    storage --> cp_store
    router --> backend_cfg

    app_svc --> proc_scip_java
    app_svc --> proc_scip_ts
    proc_scip_java --> git_repo
    proc_scip_ts --> git_repo

    ai_client -->|"MCP STDIO\nJSON-RPC 2.0"| mcp_srv
```

---

## 7.3 Diagramme de déploiement — Backend Docker (opt-in)

```mermaid
graph TB
    subgraph node_host["«node» Poste hôte (Windows / Linux)"]
        subgraph proc_minos_host["«node» Processus minos.exe (hôte)"]
            router_host["«Component»\nBackendRouter\nbackend=docker"]
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
| docker exec -i | Relay STDIO Docker | BackendRouter → conteneur MINOS |
| Fichier local JSON | Export NEXUS | minos-nexus → orchestrateur NEXUS |
| Fichier binaire v2 | Snapshots MINOS | minos-storage-local → système de fichiers |
