# Diagramme de cas d'utilisation — acteurs et surfaces

> **Module** : `application` · **Type** : diagramme de cas d'utilisation · **Question** : qui utilise MINOS, pour quoi faire, et par quelle surface ?

```mermaid
flowchart LR
    dev(["Développeur"])
    agent(["Agent IA"])
    ide(["Plugin IntelliJ"])
    integrateur(["Programme Java"])
    exploitant(["Exploitant"])

    subgraph minos["MINOS Code Intelligence"]
        uc1(["Indexer un projet"])
        uc2(["Interroger symboles, appels, impact"])
        uc3(["Recherche sémantique"])
        uc4(["Exporter vers NEXUS"])
        uc5(["Installer et vérifier les outils"])
        cli["minos-cli"]
        mcp["minos-mcp, lecture seule"]
        api["minos-api"]
        nexus["minos-nexus"]
    end

    dev --> cli
    ide --> cli
    agent --> mcp
    integrateur --> api
    exploitant --> cli
    cli --> uc1
    cli --> uc2
    cli --> uc5
    cli --> uc4
    mcp --> uc2
    mcp --> uc3
    api --> uc1
    api --> uc2
    nexus --> uc4
```

## Lecture

- Approximation : le diagramme de cas d'utilisation est rendu par un `flowchart` ; les surfaces sont dessinées entre acteurs et cas pour montrer le point d'entrée.
- Le serveur MCP est en lecture seule (ADR 0017) : un agent interroge mais n'indexe pas ; l'indexation passe par le CLI ou l'API Java.
- `minos-nexus` n'est pas un point d'entrée autonome : le CLI l'utilise pour produire l'export (dépendance `minos-cli → minos-nexus`).
- Hypothèse à confirmer : la liste des cas est un regroupement des commandes et outils, non un inventaire exhaustif (les commandes d'équipe et de plan de contrôle hébergé ne sont pas dessinées).

## Sources

- `minos-cli/src/main/java/com/minos/cli/MinosLauncher.java` — aiguillage `mcp` / autres commandes
- `minos-api/src/main/java/com/minos/api/MinosApi.java` — contrat Java public
- `minos-*/pom.xml` — descriptions des modules
- outils MCP exposés (`minos_find_callers`, `minos_impact`, `minos_semantic_search`…) — catalogue du serveur
