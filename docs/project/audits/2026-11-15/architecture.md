# Architecture observée

Découpage reconstruit au commit `89be3fb4` à partir des POM et des imports `com.minos.*` des sources principales, comparé à la documentation (arc42, C4, ARCH-0068) et au garde `check-module-boundaries.py` (vert, 252 auto-tests).

## Couches réelles

Couches observées (sens des dépendances de compilation, aucune arête d'import sans <dependency>, aucun cycle) :
1. Cœur : minos-domain (modèle + 2 ports sémantiques) <- minos-engine. Le moteur n'est pas une couche de « ports » : c'est un noyau partagé de 14 racines qui porte aussi la boîte à outils d'E/S privées (com.minos.io, 3 270 l., importée par 7 modules), le plan de contrôle d'équipe (hosted, 2 431 l.) et l'orchestration de l'indexation (5 031 l.). Tous les adaptateurs en dépendent d'abord pour io et orchestration. Décision assumée (ADR 0068).
2. Adaptateurs : runtime-local, storage, provider-scip, integration-git ; un seul couplage latéral (provider-scip -> runtime-local, justifié). minos-storage fusionné est bien un seul module dont les deux backends ne se nomment pas.
3. Application : minos-application, plus gros module (18,5 k l.). Différence avec la doc (« ports seulement ») : elle contient aussi des adaptateurs concrets non modulaires (client HTTP Ollama, analyseur Java javac/taint/CFG, lecteur de sidecar, fichier d'historique) qu'elle instancie elle-même (AUD-ARC-01, -03). Sa façade MinosApplication est un localisateur de services (AUD-ARC-02).
4. Composition : minos-bootstrap (câblage, y compris le pont SCIP <-> WSL2) découvert par ServiceLoader depuis l'application, et minos-app, qui n'est pas qu'un assemblage (transport MCP Docker, choix de backend, politique de lancement de l'explorateur).
5. Surfaces : cli, api, mcp, explorer, nexus. Écarts : cli dépend de nexus (surface -> surface), cli/api/mcp gardent une arête compile vers le moteur (4 types figés par A11) ; minos-explorer est la surface la plus propre (application + domain seulement, moteur en test), contrairement à ce qu'affichent arc42/05 et module-dependencies.md.
Arêtes d'exécution par ServiceLoader (non visibles dans les POM, documentées) : application -> bootstrap, engine -> runtime-local, engine -> storage, cli -> app.
La documentation (arc42/05, C4) décrit fidèlement le graphe de modules (règle A9), mais pas le contenu des couches : interfaces du domaine, racines du moteur, adaptateurs logés dans l'application, logique de minos-app.

## Graphe de compilation

Les flèches vont du module qui dépend vers celui dont il dépend ; seules les arêtes avec imports sont tracées (les arêtes d'assemblage de `minos-app` n'importent rien).

```mermaid
flowchart TB
  subgraph Surfaces["Surfaces"]
    minos_nexus["minos-nexus<br/>4 classes · 347 l.]
    minos_cli["minos-cli<br/>42 classes · 6 412 l.]
    minos_api["minos-api<br/>15 classes · 3 247 l.]
    minos_mcp["minos-mcp<br/>9 classes · 1 655 l.]
    minos_explorer["minos-explorer<br/>9 classes · 1 291 l.]
  end
  subgraph Composition["Composition"]
    minos_bootstrap["minos-bootstrap<br/>9 classes · 812 l.]
    minos_app["minos-app<br/>8 classes · 635 l.]
  end
  subgraph Application["Application"]
    minos_application["minos-application<br/>152 classes · 18 546 l.]
  end
  subgraph Adaptateurs["Adaptateurs"]
    minos_runtime_local["minos-runtime-local<br/>63 classes · 12 123 l.]
    minos_storage["minos-storage<br/>57 classes · 12 989 l.]
    minos_provider_scip["minos-provider-scip<br/>47 classes · 7 915 l.]
    minos_integration_git["minos-integration-git<br/>7 classes · 2 010 l.]
  end
  subgraph Coeur["Cœur"]
    minos_domain["minos-domain<br/>51 classes · 2 439 l.]
    minos_engine["minos-engine<br/>175 classes · 17 861 l.]
  end
  minos_engine -->|112 imports| minos_domain
  minos_runtime_local -->|11 imports| minos_domain
  minos_runtime_local -->|144 imports| minos_engine
  minos_storage -->|84 imports| minos_domain
  minos_storage -->|182 imports| minos_engine
  minos_provider_scip -->|68 imports| minos_domain
  minos_provider_scip -->|117 imports| minos_engine
  minos_provider_scip -->|26 imports| minos_runtime_local
  minos_integration_git -->|7 imports| minos_domain
  minos_integration_git -->|15 imports| minos_engine
  minos_application -->|304 imports| minos_domain
  minos_application -->|334 imports| minos_engine
  minos_bootstrap -->|1 imports| minos_domain
  minos_bootstrap -->|35 imports| minos_engine
  minos_bootstrap -->|7 imports| minos_application
  minos_bootstrap -->|32 imports| minos_runtime_local
  minos_bootstrap -->|7 imports| minos_storage
  minos_bootstrap -->|11 imports| minos_provider_scip
  minos_bootstrap -->|2 imports| minos_integration_git
  minos_nexus -->|5 imports| minos_domain
  minos_nexus -->|7 imports| minos_application
  minos_cli -->|29 imports| minos_domain
  minos_cli -->|4 imports| minos_engine
  minos_cli -->|129 imports| minos_application
  minos_cli -->|3 imports| minos_nexus
  minos_api -->|29 imports| minos_domain
  minos_api -->|3 imports| minos_engine
  minos_api -->|47 imports| minos_application
  minos_mcp -->|10 imports| minos_domain
  minos_mcp -->|1 imports| minos_engine
  minos_mcp -->|32 imports| minos_application
  minos_explorer -->|1 imports| minos_domain
  minos_explorer -->|7 imports| minos_application
  minos_app -->|2 imports| minos_domain
  minos_app -->|7 imports| minos_engine
  minos_app -->|3 imports| minos_application
  minos_app -->|6 imports| minos_cli
  minos_app -->|1 imports| minos_mcp
  minos_app -->|1 imports| minos_explorer
```

## Volumes par module

| Module | Famille | Classes | Lignes | Repère |
|---|---|---:|---:|---|
| minos-domain | Cœur | 51 | 2 439 | 2 ports (SemanticVectorStore  SemanticProjectionStore) malgré « aucune interface » dans l'arc42 ; 0 dépendance |
| minos-engine | Cœur | 175 | 17 861 | 14 racines de packages ; com.minos.io 22 fichiers / 3 270 l. ; 72 2 % de code concret (ADR 0068) |
| minos-runtime-local | Adaptateurs | 63 | 12 123 | 7 sous-packages dont wsl (19 classes  1 899 l.) ; 144 imports vers le moteur |
| minos-storage | Adaptateurs | 57 | 12 989 | backends local + PostgreSQL séparés par règles de packages ; SnapshotBinaryCodecSupport 1 106 l. |
| minos-provider-scip | Adaptateurs | 47 | 7 915 | seul couplage adaptateur → adaptateur (runtime-local  9 types  A13) |
| minos-integration-git | Adaptateurs | 7 | 2 010 | 10 de ses 15 imports moteur vont à com.minos.io |
| minos-application | Application | 152 | 18 546 | plus gros module ; MinosApplication 36 méthodes publiques ; contient Ollama (HTTP) et l'analyseur Java javac |
| minos-bootstrap | Composition | 9 | 812 | racine de composition ; 18 imports de runtime-local.wsl |
| minos-nexus | Surfaces | 4 | 347 | consommé par minos-cli ; 1 classe sans appelant (A15) |
| minos-cli | Surfaces | 42 | 6 412 | 129 imports vers minos-application ; 4 vers le moteur (A11) |
| minos-api | Surfaces | 15 | 3 247 | 3 types moteur publiés (query.*Result) ; MinosApi 626 l. |
| minos-mcp | Surfaces | 9 | 1 655 | 1 type moteur (MinosVersion) |
| minos-explorer | Surfaces | 9 | 1 291 | 8 imports MINOS seulement (application 7  domain 1) ; moteur en test seulement |
| minos-app | Composition | 8 | 635 | assemblage + transport MCP Docker + route explorateur ; ProcessBuilder x2 |

## Arêtes qui portent une décision ou un écart

| De | Vers | Type | Imports | Note |
|---|---|---|---:|---|
| minos-provider-scip | minos-runtime-local | compile | 26 | adaptateur → adaptateur autorisé : A13, ADAPTER_COUPLING_REASONS (ADR 0068, ADR 0070) |
| minos-provider-scip | minos-storage | test | 0 | portée test seulement (TEST_ONLY_DEPENDENCIES, ADR 0068 § 4) |
| minos-application | minos-engine | compile | 334 | inclut 35 imports de com.minos.io ; adaptateurs embarqués : AUD-ARC-01 |
| minos-application | minos-bootstrap | runtime | 0 | arête d'exécution par ServiceLoader (MinosApplicationComposer), documentée ADR 0042 |
| minos-engine | minos-runtime-local | runtime | 0 | arête d'exécution par ServiceLoader (PlatformAccessControl, ExecutionPathIdentityProvider), ADR 0068 § 2 ; réserve AUD-ARC-04 (10/10) |
| minos-engine | minos-storage | runtime | 0 | arête d'exécution par ServiceLoader (StorageBackendProvider, déclaré par minos-app), ADR 0068 § 4 |
| minos-bootstrap | minos-runtime-local | compile | 32 | dont 18 imports de runtime.local.wsl |
| minos-cli | minos-engine | compile | 4 | dette déclarée A11 (query.*Result, runtime.MinosVersion), ADR 0057/0068 ; réserve AUD-ARC-01 (10/10) |
| minos-cli | minos-nexus | compile | 3 | surface → surface : AUD-ARC-08 |
| minos-cli | minos-bootstrap | runtime | 0 | ADR 0042 : racine de composition à l'exécution seulement |
| minos-cli | minos-app | runtime | 0 | arête d'exécution par ServiceLoader (McpLaunchRoute, ExplorerLaunchRoute), documentée (AUD-ARC-11 10/10) |
| minos-api | minos-engine | compile | 3 | dette déclarée A11 (modèles de lecture publiés), ADR 0057 point 6 |
| minos-api | minos-bootstrap | runtime | 0 | ADR 0042 |
| minos-mcp | minos-engine | compile | 1 | dette déclarée A11 (MinosVersion), ADR 0068 § 3 |
| minos-mcp | minos-bootstrap | runtime | 0 | ADR 0042 |
| minos-explorer | minos-bootstrap | runtime | 0 | ADR 0042 |
| minos-explorer | minos-engine | test | 0 | test seulement (test-jar et jar) ; la doc la présente comme dépendance : AUD-ARC-07 |
| minos-app | minos-engine | compile | 7 | com.minos.io (6), MinosRuntimeSettings (1) |
| minos-app | minos-cli | compile | 6 | constantes de sortie de FindSymbolCommand : AUD-ARC-06 |
| minos-app | minos-explorer | compile | 1 | seule arête vers l'explorateur (ADR 0082 point 1) |
| minos-app | minos-api | compile | 0 | assemblage (JAR ombré), aucun import |
| minos-app | minos-nexus | compile | 0 | assemblage, aucun import |
| minos-app | minos-bootstrap | compile | 0 | assemblage, aucun import |
| minos-app | minos-runtime-local | compile | 0 | assemblage, aucun import |
| minos-app | minos-storage | compile | 0 | assemblage ; déclare le service PostgresStorageBackendProvider de minos-storage |
| minos-app | minos-provider-scip | compile | 0 | assemblage, aucun import |
| minos-app | minos-integration-git | compile | 0 | assemblage, aucun import |

## Ce que le graphe ne montre pas

Découpage réellement observé au commit 89be3fb4, reconstruit depuis les POM et les imports com.minos.* des sources principales ; les flèches vont du module qui dépend vers celui dont il dépend. Il n'y a aucun cycle ni aucun import sans dépendance déclarée, et minos-explorer est la surface la plus propre (application et domaine seulement). Ce que le dessin ne montre pas, et qui est le vrai sujet : les défauts internes à un module. minos-application loge des adaptateurs (AUD-ARC-01) et sa façade sert de localisateur (AUD-ARC-02) ; les caches hors budget (AUD-PERF-04) et les échecs sans trace (AUD-QUA-02) ne se voient sur aucun graphe.
