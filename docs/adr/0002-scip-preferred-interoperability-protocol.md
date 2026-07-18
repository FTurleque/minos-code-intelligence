# ADR-0002 — Use SCIP as the preferred semantic indexing interoperability protocol

- Status: **Accepted**
- Date: 2026-07-19

## Context

MINOS needs precise symbol-level information such as definitions, references and implementations without reimplementing mature language frontends whenever suitable indexers already exist.

SCIP provides a language-agnostic protocol for sharing semantic code intelligence and has an ecosystem of language-specific indexers.

At the same time, some languages or analysis requirements may be better served by native Glean indexers, compiler APIs, LSIF, Language Servers, AST analyzers or future specialized engines.

## Decision

SCIP will be the **preferred semantic indexing interoperability protocol** when a suitable and sufficiently accurate indexer exists.

SCIP will not be mandatory.

The architecture will use a SCIP ingestion adapter:

```text
Language-specific SCIP indexer
          │
          ▼
       SCIP index
          │
          ▼
MINOS SCIP ingestion adapter
          │
          ▼
Normalized MINOS Code Model
```

MINOS core services will not expose SCIP protobuf types directly.

The indexer registry must allow non-SCIP providers to participate through the same MINOS capability model.

## Why SCIP is preferred

- avoids building a complete parser and semantic resolver for every supported language;
- provides a common exchange format across multiple language ecosystems;
- supports precise symbol identity and occurrence information;
- can feed Glean through SCIP-to-Glean tooling;
- preserves the option to change the downstream knowledge store;
- aligns with MINOS's multi-language goals.

## Consequences

### Positive

- Faster path to multi-language semantic indexing.
- Less duplicated language-analysis infrastructure.
- Easier adoption of new language indexers.
- Clear boundary between language indexing and MINOS-specific intelligence.

### Negative

- Quality depends on each SCIP indexer.
- Capability coverage differs by language.
- Some desired relationships may need to be derived after ingestion.
- Toolchain installation and process management must be handled by MINOS.

## Validation required during M0

The decision must be validated with real repositories, starting with Java and at least one non-JVM language.

Measurements must include:

- indexing success rate;
- symbol precision;
- reference precision;
- implementation resolution;
- multi-module behavior;
- index size;
- indexing duration;
- offline operation;
- failure behavior when dependencies are unavailable.

If a SCIP indexer does not meet the required accuracy for a language, MINOS may select another provider without changing the domain model.
