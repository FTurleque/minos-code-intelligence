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
| [0003](0003-glean-behind-code-knowledge-store.md) | Keep Glean optional behind a MINOS-owned CodeKnowledgeStore port | Accepted; confirmed by M0 C1/E2 |
| [0004](0004-stack-java-maven-core-sans-framework.md) | Implement the MINOS core with Maven and no server framework | Partially superseded by ADR-0005 |
| [0005](0005-aligner-java-24-environnement-developpement.md) | Align MINOS on Java 24 from the development environment | Accepted |
| [0006](0006-promouvoir-les-index-de-maniere-atomique.md) | Promote provider indexes atomically | Accepted |
| [0007](0007-attribuer-identites-projet-workspace-dans-registre-local.md) | Attribuer les identités projet/workspace dans un registre local | Accepted; confirmed by M1.2 |
| [0008](0008-negocier-indexeurs-par-capacites-explicites.md) | Négocier les indexeurs par capacités explicites | Proposed |

Future ADR candidates:

- Java runtime and framework choice;
- Glean deployment and process topology;
- MINOS normalized symbol identity model;
- index invalidation and incremental refresh strategy;
- CLI framework;
- MCP exposure contract;
- incremental indexing strategy;
- specialized analysis engines such as CPG/data-flow providers.
