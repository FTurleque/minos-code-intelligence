# Diagramme — Provider SCIP absent ou non prêt

> Relu contre le code le 2026-10-07 : le diagramme d'[arc42 § 6.2](../arc42/06-vue-execution.md) (août) montrait
> un échec dans la découverte ; ce n'est plus le chemin. Le refus se fait dans la préparation de l'indexation, à
> partir de l'état du runtime du provider. Les noms exacts sont dans les tableaux.

## 1. `minos index` refuse de démarrer

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur
    participant CLI as CLI
    participant Ops as Orchestration<br/>de l'indexation
    participant Eng as Registre<br/>d'indexeurs
    participant Prov as Provider SCIP

    Dev->>CLI: minos index projet
    CLI->>Ops: execute(projet, options)
    Ops->>Eng: négocie le provider
    alt aucun provider ne couvre les langages
        Eng-->>Ops: négociation incomplète
        Ops-->>CLI: échec « no qualified provider »
    else provider négocié
        Ops->>Prov: état du runtime ?
        Prov-->>Ops: NOT_INSTALLED ou BLOCKED + diagnostics
        Ops-->>CLI: échec « provider runtime is not ready »
    end
    CLI-->>Dev: « error: … » sur stderr, code 1
    Note over Ops: aucun run créé, aucun snapshot promu
```

| Participant | Classes (module) |
|---|---|
| CLI | `IndexCommand`, `CliCommandSupport.run` : message `error: …`, code `FindSymbolCommand.EXECUTION_ERROR` (= 1) |
| Orchestration de l'indexation | `LocalAutonomousIndexOperations.prepare` / `executeLocked` (`minos-cli`) |
| Registre d'indexeurs | `IndexerRegistry.negotiate` (`minos-engine`) |
| Provider SCIP | `ManagedScipProviderRuntimeManager.inspect` (`minos-provider-scip`) : états `READY`, `BLOCKED`, `NOT_INSTALLED` |

## 2. Diagnostiquer et réparer

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Développeur
    participant CLI as CLI
    participant Ops as Orchestration<br/>de l'indexation
    participant Prov as Provider SCIP

    Dev->>CLI: minos doctor
    CLI->>Ops: providers()
    Ops->>Prov: état de chaque provider
    Prov-->>CLI: états et diagnostics
    CLI-->>Dev: rapport, verdict READY ou ACTION_REQUIRED

    Dev->>CLI: minos tools install scip-java
    CLI->>Ops: installProvider(scip-java)
    Ops->>Prov: installe le provider géré
    Prov-->>CLI: READY, ou diagnostics restants
    CLI-->>Dev: état du provider
```

| Participant | Classes (module) |
|---|---|
| CLI | `DoctorCommand`, `ToolsCommand` (`minos-cli`) |
| Orchestration de l'indexation | `LocalAutonomousIndexOperations.providers` et `installProvider` |
| Provider SCIP | `ManagedScipProviderRuntimeManager.inspect` et `install` |

Le rapport de `doctor` contient aussi les commandes trouvées, le stockage privé et l'état du sandbox des workers
(`workerSandbox`, ADR-0041).
