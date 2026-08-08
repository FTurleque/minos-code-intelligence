# Section 3 — Contexte et périmètre

> Preuves : ADR-0001, ADR-0017, ADR-0020, ADR-0027, ADR-0035, `MinosCli.java` (USAGE),
> `minos-mcp/pom.xml`, `minos-nexus/pom.xml`, `minos-integration-git/pom.xml`.

---

## 3.1 Frontière du système

MINOS Code Intelligence est un **composant local** qui s'exécute sur le poste du développeur ou dans un conteneur Docker.
Il **reçoit** des requêtes de ses consommateurs (CLI, MCP, API Java, NEXUS) et **émet** des résultats JSON structurés.
Il **ne stocke pas** de code sur un serveur distant et **ne s'authentifie pas** auprès de services cloud en mode standard.

---

## 3.2 Acteurs et systèmes externes

| Acteur / Système | Stéréotype | Direction | Description |
|-----------------|-----------|-----------|-------------|
| Développeur | `«Person»` | → MINOS | Invoque la CLI ou le plugin IntelliJ |
| Agent IA (Claude Code, Copilot, Codex) | `«Person»` | → MINOS MCP | Consomme les outils MCP STDIO |
| Plugin IntelliJ | `«Software System»` | → CLI JSON | Client Java 21 externe négociant le protocole CLI JSON versionné (ADR-0027) |
| Orchestrateur NEXUS | `«Software System»` | ← MINOS | Reçoit l'export JSON du snapshot normalisé (ADR-0020) |
| Dépôt GitHub / GitLab | `«Software System»` | → MINOS | Source pour l'indexation de révisions distantes immutables (ADR-0033) |
| Docker Daemon | `«Software System»` | → MINOS | Backend MCP optionnel (ADR-0037) |
| Indexeur SCIP Java | `«Software System»` | → MINOS | Processus externe produisant un artefact `.scip` |
| Indexeur SCIP TypeScript | `«Software System»` | → MINOS | Processus externe produisant un artefact `.scip` |
| Indexeurs SCIP polyglot (Go, Rust, C/C++, .NET, Python…) | `«Software System»` | → MINOS | Futurs providers SCIP (ADR-0032) |
| PostgreSQL / pgvector | `«Software System»` | ↔ MINOS | Backend de stockage avancé optionnel |

---

## 3.3 Interfaces sortantes

| Interface | Protocole | Sens | Consommateur |
|-----------|----------|------|-------------|
| CLI JSON | JSON newline-delimited / STDOUT | MINOS → | IntelliJ, scripts |
| MCP STDIO | JSON-RPC 2.0 / STDIO | MINOS → | Agents IA |
| API Java (`minos-api`) | Appel de méthode JVM | MINOS → | Intégrateurs Java |
| Contrat JSON NEXUS | JSON fichier local | MINOS → | Orchestrateur NEXUS |

---

## 3.4 Diagramme C4 — Context

```mermaid
C4Context
    title MINOS Code Intelligence — Diagramme de contexte (C4 Level 1)

    Person(dev, "Développeur", "«Person»\nUtilise la CLI ou le plugin IntelliJ")
    Person(ai_agent, "Agent IA", "«Person»\nClaude Code, Copilot, Codex\nconsomme les outils MCP")

    System(minos, "MINOS Code Intelligence", "«Software System»\nMoteur local-first de Code Intelligence\nmulti-langages et multi-indexeurs")

    System_Ext(intellij, "Plugin IntelliJ", "«Software System»\nClient Java 21 externe\nnégocie le protocole CLI JSON versionné")
    System_Ext(nexus, "Orchestrateur NEXUS", "«Software System»\nConsomme l'export JSON du snapshot normalisé")
    System_Ext(github, "GitHub / GitLab", "«Software System»\nSource de révisions distantes immutables")
    System_Ext(docker, "Docker Daemon", "«Software System»\nBackend MCP optionnel")
    System_Ext(scip_java, "Indexeur SCIP Java", "«Software System»\nProduit un artefact .scip")
    System_Ext(scip_ts, "Indexeur SCIP TypeScript", "«Software System»\nProduit un artefact .scip")
    System_Ext(pg, "PostgreSQL / pgvector", "«Software System»\nBackend de stockage avancé optionnel")

    Rel(dev, minos, "invoque via CLI ou IDE")
    Rel(ai_agent, minos, "interroge via MCP STDIO (JSON-RPC 2.0)")
    Rel(intellij, minos, "négocie protocole CLI JSON versionné")
    Rel(minos, nexus, "exporte snapshot normalisé (JSON local)")
    Rel(minos, github, "matérialise révisions immutables")
    Rel(minos, docker, "route mcp vers backend Docker (optionnel)")
    Rel(minos, scip_java, "déclenche l'indexation Java")
    Rel(minos, scip_ts, "déclenche l'indexation TypeScript")
    Rel(minos, pg, "persiste / requête (optionnel)")
```
