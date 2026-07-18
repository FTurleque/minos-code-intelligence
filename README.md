# MINOS

**MINOS** is a local-first, language-agnostic **Code Intelligence Engine** designed to build a structured, persistent and queryable understanding of software repositories.

MINOS is not a chatbot and is not tied to any LLM, IDE, cloud provider, language or indexing backend.

Its role is to answer questions such as:

- Where is a symbol defined?
- Who uses, calls, extends or implements it?
- What does it depend on?
- What depends on it?
- Which tests are related to it?
- What code may be impacted by a change?
- What is the architecture and topology of the repository?

## Position in the ecosystem

```text
Repository / Workspace
        │
        ▼
      MINOS
  Code Intelligence
"I understand the code"
        │
        ▼
      NEXUS
 Context Intelligence
"I select the right context"
        │
        ▼
 Agent / LLM / IDE
```

MINOS and NEXUS have deliberately separate responsibilities:

- **MINOS** models code, symbols, relationships, dependencies and evidence.
- **NEXUS** selects and ranks the information that should be injected into an AI context for a specific task.

MINOS must work independently of NEXUS.

## Architectural direction

MINOS is designed around pluggable indexing and knowledge backends.

```text
Repositories / Workspaces
          │
          ▼
Project & Language Discovery
          │
          ▼
Indexer Registry
          │
    ┌─────┼────────────────┐
    ▼     ▼                ▼
   SCIP  Native Glean   Other Providers
    │     │                │
    └─────┼────────────────┘
          ▼
Normalized MINOS Code Model
          │
          ▼
CodeKnowledgeStore
          │
    ┌─────┴─────┐
    ▼           ▼
 Glean        Future
preferred     backend
 backend
          │
          ▼
MINOS Intelligence Layer
          │
 ┌────────┼─────────┐
 ▼        ▼         ▼
Graph   Analysis   Search
 │        │         │
 └────────┼─────────┘
          ▼
Compact Code Intelligence
          │
  ┌───────┼───────┐
  ▼       ▼       ▼
 CLI     MCP     API
          │
          ▼
        NEXUS
```

### Core principles

1. **Language-agnostic** — Java, TypeScript and Python are initial validation targets, not architectural limits.
2. **Indexer-agnostic** — SCIP is the preferred interoperability protocol where appropriate, but MINOS must support alternative providers.
3. **Glean-first, not Glean-locked** — Glean is the preferred code-fact and query backend, hidden behind a MINOS-owned `CodeKnowledgeStore` contract.
4. **Evidence-driven** — derived and heuristic relationships expose provenance, confidence and evidence.
5. **Local-first** — no repository content is sent to an external service by default.
6. **Token-efficient** — AI consumers receive compact symbol- and relationship-level results by default, not entire files.
7. **Extensible** — new languages, indexers, analysis engines and storage backends must be addable without rewriting the MINOS domain.

## Current status

**Phase: M0 — Architecture & Feasibility**

The repository is being initialized around architecture decisions, proof-of-concept spikes and measurable validation criteria before significant product implementation.

See:

- [`docs/architecture/overview.md`](docs/architecture/overview.md)
- [`docs/PLAN.md`](docs/PLAN.md)
- [`docs/ROADMAP.md`](docs/ROADMAP.md)
- [`docs/MVP.md`](docs/MVP.md)
- [`docs/adr/`](docs/adr/)

## License

No project license has been selected yet. This repository is currently private. Open-source licensing will be decided explicitly before public release.
