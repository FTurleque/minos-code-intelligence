# Diagramme — Modules du reactor Maven, par couche

> Vue de niveau 2, volontairement simplifiée : une flèche relie des couches, pas chaque paire de modules. Les
> arêtes Maven exactes sont dans [module-dependencies.md](module-dependencies.md) (fichier généré, qui fait foi).
> Détail de chaque module : [arc42 § 5.2](../arc42/05-vue-blocs.md). Preuves : `pom.xml` (14 modules),
> ADR-0042 (racine de composition dans `minos-bootstrap`), ADR-0044.

```mermaid
flowchart TB
    clients(["Développeur, IDE, agent IA"])
    app["<b>minos-app</b><br/>assemblage distribué : lanceur, JAR ombré, route MCP"]
    surfaces["<b>Surfaces</b><br/>minos-cli · minos-mcp · minos-api · minos-nexus"]
    bootstrap["<b>minos-bootstrap</b><br/>racine de composition (ADR-0042)"]
    application["<b>minos-application</b><br/>services applicatifs"]
    adapters["<b>Adaptateurs</b><br/>minos-storage-local · minos-storage-postgresql (optionnel)<br/>minos-provider-scip · minos-runtime-local · minos-integration-git"]
    engine["<b>minos-engine</b><br/>ports, indexation, requêtes"]
    domain["<b>minos-domain</b><br/>modèle de domaine pur"]

    clients --> app
    app --> surfaces
    app --> bootstrap
    surfaces --> application
    surfaces -.->|"ouvrent l'application (ServiceLoader)"| bootstrap
    bootstrap --> application
    bootstrap -->|"câble"| adapters
    application --> engine
    adapters --> engine
    engine --> domain
```

Lecture : les flèches descendent vers le cœur ; `minos-bootstrap` est le seul module non adaptateur qui connaît
des classes d'adaptateur (hors `minos-app`, assemblage final). Les surfaces ne dépendent pas des adaptateurs.
