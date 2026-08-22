# Diagramme — Dépendances Maven entre modules MINOS

> **Fichier généré.** Ne pas modifier ce diagramme manuellement.
> La vérité exécutable provient des POMs du reactor et de
> `scripts/architecture/check-module-boundaries.py`.
> Régénération : `python scripts/architecture/check-module-boundaries.py --write-doc`.

```mermaid
flowchart LR
    minos_domain["minos-domain"]
    minos_engine["minos-engine"]
    minos_runtime_local["minos-runtime-local"]
    minos_storage_local["minos-storage-local"]
    minos_storage_postgresql["minos-storage-postgresql"]
    minos_provider_scip["minos-provider-scip"]
    minos_integration_git["minos-integration-git"]
    minos_application["minos-application"]
    minos_nexus["minos-nexus"]
    minos_cli["minos-cli"]
    minos_api["minos-api"]
    minos_mcp["minos-mcp"]
    minos_app["minos-app"]
    minos_engine --> minos_domain
    minos_runtime_local --> minos_engine
    minos_storage_local --> minos_engine
    minos_storage_postgresql --> minos_application
    minos_storage_postgresql --> minos_domain
    minos_storage_postgresql --> minos_engine
    minos_storage_postgresql --> minos_storage_local
    minos_provider_scip --> minos_domain
    minos_provider_scip --> minos_engine
    minos_provider_scip --> minos_runtime_local
    minos_provider_scip --> minos_storage_local
    minos_integration_git --> minos_engine
    minos_application --> minos_domain
    minos_application --> minos_engine
    minos_application --> minos_integration_git
    minos_application --> minos_provider_scip
    minos_application --> minos_runtime_local
    minos_application --> minos_storage_local
    minos_nexus --> minos_application
    minos_nexus --> minos_domain
    minos_nexus --> minos_storage_local
    minos_cli --> minos_application
    minos_cli --> minos_domain
    minos_cli --> minos_engine
    minos_cli --> minos_integration_git
    minos_cli --> minos_nexus
    minos_cli --> minos_provider_scip
    minos_cli --> minos_runtime_local
    minos_cli --> minos_storage_local
    minos_api --> minos_application
    minos_api --> minos_domain
    minos_api --> minos_engine
    minos_api --> minos_integration_git
    minos_api --> minos_storage_local
    minos_mcp --> minos_application
    minos_app --> minos_api
    minos_app --> minos_application
    minos_app --> minos_cli
    minos_app --> minos_domain
    minos_app --> minos_engine
    minos_app --> minos_integration_git
    minos_app --> minos_mcp
    minos_app --> minos_nexus
    minos_app --> minos_provider_scip
    minos_app --> minos_runtime_local
    minos_app --> minos_storage_local
    minos_app --> minos_storage_postgresql
```

## Dépendances MINOS directes

| Module | Dépendances directes |
|---|---|
| `minos-domain` | — |
| `minos-engine` | `minos-domain` |
| `minos-runtime-local` | `minos-engine` |
| `minos-storage-local` | `minos-engine` |
| `minos-storage-postgresql` | `minos-application`, `minos-domain`, `minos-engine`, `minos-storage-local` |
| `minos-provider-scip` | `minos-domain`, `minos-engine`, `minos-runtime-local`, `minos-storage-local` |
| `minos-integration-git` | `minos-engine` |
| `minos-application` | `minos-domain`, `minos-engine`, `minos-integration-git`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage-local` |
| `minos-nexus` | `minos-application`, `minos-domain`, `minos-storage-local` |
| `minos-cli` | `minos-application`, `minos-domain`, `minos-engine`, `minos-integration-git`, `minos-nexus`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage-local` |
| `minos-api` | `minos-application`, `minos-domain`, `minos-engine`, `minos-integration-git`, `minos-storage-local` |
| `minos-mcp` | `minos-application` |
| `minos-app` | `minos-api`, `minos-application`, `minos-cli`, `minos-domain`, `minos-engine`, `minos-integration-git`, `minos-mcp`, `minos-nexus`, `minos-provider-scip`, `minos-runtime-local`, `minos-storage-local`, `minos-storage-postgresql` |

Le sens d'une flèche est **module → dépendance directe**. Les dépendances transitives ne sont pas répétées.
Le mode normal du checker échoue si ce fichier n'est plus exactement aligné avec les POMs courants.
