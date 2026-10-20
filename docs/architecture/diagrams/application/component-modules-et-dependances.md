# Diagramme de composants — modules et dépendances

> **Module** : `application` · **Type** : diagramme de composants · **Question** : de quels modules MINOS est-il fait, et quel module dépend de quel autre ?

```mermaid
flowchart TB
    subgraph assemblage["Assemblage"]
        app["minos-app"]
    end
    subgraph surfaces["Surfaces"]
        cli["minos-cli"]
        mcp["minos-mcp"]
        api["minos-api"]
        nexus["minos-nexus"]
    end
    subgraph composition["Racine de composition"]
        bootstrap["minos-bootstrap"]
    end
    subgraph applicatif["Application"]
        application["minos-application"]
    end
    subgraph adaptateurs["Adaptateurs"]
        scip["minos-provider-scip"]
        runtime["minos-runtime-local"]
        storage["minos-storage-local"]
        pg["minos-storage-postgresql"]
        git["minos-integration-git"]
    end
    subgraph coeur["Cœur"]
        engine["minos-engine"]
        domain["minos-domain"]
    end
    plugin["minos-intellij (Gradle, hors reactor)"]

    app --> surfaces
    app --> bootstrap
    cli --> nexus
    cli --> application
    mcp --> application
    api --> application
    nexus --> application
    surfaces -.->|"runtime"| bootstrap
    bootstrap --> application
    bootstrap --> scip
    bootstrap --> runtime
    bootstrap --> storage
    bootstrap --> git
    app --> pg
    scip --> runtime
    scip --> storage
    pg --> storage
    application --> engine
    adaptateurs --> engine
    surfaces --> engine
    engine --> domain
    plugin -.->|"processus minos, JSON versionné"| cli
```

## Lecture

- Flèche pleine : dépendance Maven de portée `compile` ; pointillée `runtime` : les surfaces ne voient `minos-bootstrap` qu'à l'exécution (ServiceLoader, ADR 0042), jamais à la compilation.
- `minos-bootstrap` ne dépend de `minos-storage-postgresql` qu'en portée `test` ; le module PostgreSQL n'entre dans la distribution que par `minos-app`. Il est optionnel à l'exécution.
- Les flèches qui partent d'un cadre (« Surfaces », « Adaptateurs ») résument une arête que chaque module du cadre porte : tous dépendent de `minos-engine` (et de `minos-domain`, non redessiné).
- Le plugin IntelliJ n'a aucune dépendance `com.minos:*` : il lance le CLI comme un processus externe (`MinosCliClient`). La flèche est une relation d'usage, pas une dépendance de build.
- Graphe exhaustif généré : [module-dependencies.md](../module-dependencies.md) ; les diagrammes de détail par module restent à produire.

## Sources

- `pom.xml` — liste des 14 modules du reactor
- `minos-*/pom.xml` — dépendances `com.minos` et leur portée
- `minos-intellij/src/main/java/com/minos/intellij/protocol/MinosCliClient.java` — client CLI du plugin
- `AGENTS.md`, ADR 0022, 0042, 0044 — direction des couches et racine de composition
