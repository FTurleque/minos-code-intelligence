# Architecture Decision Records

MINOS uses Architecture Decision Records (ADRs) to document consequential technical choices before large implementation commitments are made.

## Status values

- **Proposed** — under evaluation.
- **Accepted** — current architectural direction.
- **Superseded** — replaced by a newer ADR.
- **Rejected** — evaluated and not retained.

## ADR index

| ADR | Title | Status |
|---|---|---|
| [0001](0001-language-and-indexer-agnostic-core.md) | Keep the MINOS core language- and indexer-agnostic | Accepted |
| [0002](0002-scip-preferred-interoperability-protocol.md) | Use SCIP as the preferred semantic indexing interoperability protocol | Accepted |
| [0003](0003-glean-behind-code-knowledge-store.md) | Reuse Glean strongly behind a MINOS-owned CodeKnowledgeStore port | Accepted |

Future ADR candidates:

- Java runtime and framework choice;
- Glean deployment and process topology;
- MINOS normalized symbol identity model;
- index snapshot and invalidation strategy;
- CLI framework;
- MCP exposure contract;
- incremental indexing strategy;
- specialized analysis engines such as CPG/data-flow providers.
