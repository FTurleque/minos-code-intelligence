# MINOS Execution Plan

Status: **Initial M0 plan**

## Objective

The first implementation phase must validate whether MINOS can build valuable, precise and compact Code Intelligence by orchestrating existing open-source semantic indexing and code-fact infrastructure rather than rebuilding language parsers and graph engines.

The primary hypothesis is:

> SCIP-based semantic indexers plus Glean can provide the low-level code facts, while MINOS adds normalization, orchestration, explainability, derived analysis and agent-oriented query contracts.

## M0 — Architecture & Feasibility

### Workstream 1 — Repository and project bootstrap

Deliverables:

- project identity and README;
- Maven bootstrap;
- architecture overview;
- ADR process;
- roadmap;
- MVP definition;
- initial validation fixtures.

Exit condition:

- architectural direction is documented before significant implementation.

### Workstream 2 — External technology spikes

Evaluate:

- SCIP protocol and available indexers;
- `scip-java` on a representative Maven repository;
- at least one non-JVM SCIP indexer;
- SCIP-to-Glean ingestion;
- Glean local storage and query workflow;
- Glean operational footprint;
- failure modes when builds or dependencies are incomplete.

The spike must use real repositories in addition to synthetic fixtures.

Suggested first Java target:

```text
ariane-chatbot
```

### Workstream 3 — Normalized MINOS model

Design the minimum domain model:

```text
Project
Workspace
Module
SourceFile
Symbol
SymbolLocation
Relationship
Evidence
IndexSnapshot
```

The model must support:

- stable symbol identity;
- overloaded methods;
- cross-file relationships;
- source/test distinction;
- external symbol references;
- unresolved references;
- provider provenance.

### Workstream 4 — Provider capability model

Define contracts for:

```text
IndexerProvider
IndexerRegistry
IndexerCapabilities
IndexingRequest
IndexingResult
```

Providers must advertise capabilities.

Example:

```text
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
CALL_RELATIONSHIPS
TYPE_RELATIONSHIPS
CROSS_MODULE
DATA_FLOW
```

M0 does not require dynamic plugin loading. Static registration is acceptable for the first spike if the public architecture remains extensible.

### Workstream 5 — CodeKnowledgeStore boundary

Define a MINOS-owned storage/query port from use cases.

Initial contract candidates:

```text
upsertProjectIndex
storeSymbols
storeRelationships
findSymbols
findSymbolByQualifiedName
findUsages
findImplementations
findIncomingRelationships
findOutgoingRelationships
```

Two implementations are expected during validation:

1. `InMemoryCodeKnowledgeStore` for tests;
2. `GleanCodeKnowledgeStore` for the real spike.

The goal is not feature parity across multiple production stores.

### Workstream 6 — First vertical slice

Build the smallest end-to-end path:

```text
Java repository
      │
      ▼
  scip-java
      │
      ▼
   index.scip
      │
      ▼
 SCIP ingestion
      │
      ▼
    Glean
      │
      ▼
CodeKnowledgeStore
      │
      ▼
 find_symbol
 find_usages
```

The first slice is successful only if MINOS returns its own normalized result types.

### Workstream 7 — Precision and evidence

Every result must distinguish:

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

Derived results must include:

```text
origin
confidence
evidence
```

No heuristic result may be presented as a deterministic fact.

### Workstream 8 — Compact output contract

Define machine-friendly responses before MCP implementation.

Example target:

```json
{
  "symbol": "DocumentIngestionService",
  "kind": "CLASS",
  "qualifiedName": "fr.ariane.chatbot.document.DocumentIngestionService",
  "location": {
    "file": "src/main/java/.../DocumentIngestionService.java",
    "startLine": 12,
    "endLine": 120
  },
  "relationships": {
    "dependencies": 3,
    "dependents": 2,
    "relatedTests": 1
  }
}
```

Full source content must not be returned unless explicitly requested.

## M0 validation matrix

| Area | Validation |
|---|---|
| Java indexing | Representative Maven mono- and multi-module repositories |
| Non-JVM indexing | At least one additional language |
| Symbol precision | Definitions and qualified identities |
| Usage precision | Cross-file references |
| Implementation resolution | Interfaces / inheritance where supported |
| Glean ingestion | SCIP facts successfully available in Glean |
| Abstraction | No Glean types in MINOS domain contracts |
| Local-first | No mandatory external cloud service |
| Performance | Index and query timings recorded |
| Failure handling | Missing dependencies and partial repositories tested |

## M0 deliverables

- architecture documentation;
- accepted/rejected ADRs;
- technology spike report;
- normalized model proposal;
- provider capability contract;
- `CodeKnowledgeStore` contract;
- in-memory test implementation;
- Glean proof-of-concept adapter;
- SCIP Java vertical slice;
- one non-Java indexing spike;
- benchmark results;
- recommendation to proceed, revise or replace the Glean strategy.

## Explicitly deferred

M0 must not expand into:

- production REST API;
- full MCP server;
- NEXUS integration;
- semantic embeddings;
- vector database;
- IDE plugins;
- GitHub/GitLab remote ingestion;
- perfect impact analysis;
- dynamic runtime analysis;
- support for every language at launch.
