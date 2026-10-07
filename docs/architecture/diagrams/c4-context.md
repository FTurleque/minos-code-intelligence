# Diagramme — Contexte du système

> Vue de niveau 1 : MINOS et ce qui l'entoure. Détail des acteurs et des interfaces : [arc42 § 3.2 et 3.3](../arc42/03-contexte-perimetre.md).
> Preuves : ADR-0001, ADR-0017, ADR-0020, ADR-0027, ADR-0033, ADR-0037, `MinosCli.java` (USAGE).

```mermaid
flowchart LR
    dev(["Développeur"])
    agent(["Agent IA<br/>Claude Code, Copilot, Codex"])
    ide["Plugin IntelliJ<br/>client Java 21 externe"]

    minos[["MINOS<br/>moteur local-first<br/>de code intelligence"]]

    nexus["Orchestrateur NEXUS"]
    git["GitHub / GitLab"]
    scip["Indexeurs SCIP<br/>scip-java, scip-typescript"]
    docker["Docker Daemon<br/>backend MCP"]
    pg[("PostgreSQL / pgvector")]

    dev -->|"CLI"| minos
    ide -->|"CLI JSON versionné"| minos
    agent -->|"MCP STDIO"| minos

    minos -->|"export JSON local"| nexus
    minos -->|"révisions immuables"| git
    minos -->|"lance les indexeurs"| scip
    minos -.->|"optionnel"| docker
    minos -.->|"optionnel"| pg
```

Traits pleins : usage courant. Traits pointillés : optionnel.
