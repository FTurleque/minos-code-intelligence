# Diagramme — Indexation nominale puis requête de symbole

> Relu contre le code le 2026-10-07 : le diagramme d'[arc42 § 6.1](../arc42/06-vue-execution.md) date d'août et
> cite des classes qui n'existent plus. Les noms exacts des classes et des appels sont dans le tableau sous chaque
> diagramme, pour garder les diagrammes lisibles. Le bail de projet, la reprise d'un run interrompu (ADR-0039) et la
> synchronisation sémantique ne sont pas détaillés ; si aucun fichier n'a changé, la commande répond `NO_CHANGES`
> sans lancer de provider.

## 1. Indexer un projet

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur
    participant CLI as CLI
    participant Ops as Orchestration<br/>de l'indexation
    participant Eng as Moteur
    participant Prov as Provider SCIP
    participant Sto as Stockage

    Dev->>CLI: minos index projet
    CLI->>Ops: execute(projet, options)
    Ops->>Sto: prend le bail du projet
    Ops->>Eng: empreinte, puis découverte
    Ops->>Eng: négocie le provider
    Ops->>Eng: planifie (complet ou incrémental)
    Ops->>Prov: le runtime est-il READY ?
    Ops->>Eng: lance le run
    Eng->>Prov: exécute l'indexeur
    Prov-->>Eng: artefact .scip
    Eng->>Sto: prépare puis promeut le snapshot
    Eng-->>Ops: run SUCCEEDED
    Ops->>Eng: empreinte après le run
    alt espace de travail inchangé
        Ops->>Sto: promeut la nouvelle baseline
    else modifié pendant le run
        Note over Ops: baseline non promue
    end
    Ops-->>CLI: résumé du run
    CLI-->>Dev: résultat, code de sortie 0
```

| Participant | Classes (module) |
|---|---|
| CLI | `MinosLauncher`, `MinosCliRunner`, `IndexCommand` (`minos-cli`) ; l'application est ouverte par `MinosApplication.open(home)` |
| Orchestration de l'indexation | `LocalAutonomousIndexOperations` (`minos-cli`) |
| Moteur | `ProjectFingerprintService`, `ProjectDiscoveryService`, `ProjectInvalidationService`, `IndexerRegistry`, `IncrementalIndexingPlanner`, `IndexingLifecycleService` / `IndexingRunExecutor` (`minos-engine`) |
| Provider SCIP | `ProviderRuntimeManager` (port) et `IndexerExecutor` (`IndexingRuntimePorts`), réalisés par `minos-provider-scip` |
| Stockage | `IndexStateStore`, `SnapshotStager`, `SnapshotPromoter`, `ProjectFingerprintSnapshotStore` (ports d'`minos-engine`, réalisés par `minos-storage-local`) |

Points à retenir : l'empreinte est prise **avant** la découverte (MINOS-AUD-D06) ; la promotion du snapshot est
atomique (ADR-0006) ; la baseline d'empreinte n'est promue que si l'espace de travail n'a pas changé pendant le run
(ADR-0014).

## 2. Interroger un symbole

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur
    participant CLI as CLI
    participant Q as Service de requête
    participant Sto as Stockage

    Dev->>CLI: minos find-symbol projet --name MyClass
    CLI->>Q: findSymbols(projet, critères)
    Q->>Sto: charge la vue du snapshot actif
    Sto-->>Q: vue de requête
    Q-->>CLI: symboles avec preuves
    CLI-->>Dev: texte ou JSON
```

| Participant | Classes (module) |
|---|---|
| CLI | `FindSymbolCommand` (`minos-cli`) |
| Service de requête | `ProjectQueryService.findSymbols` (`minos-application`), qui construit un `SymbolQueryService` |
| Stockage | `CodeKnowledgeSnapshotStore.loadActiveQueryView` ; sans snapshot actif, la requête échoue (« project has no active symbol snapshot ») |
