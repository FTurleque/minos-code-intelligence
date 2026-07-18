# ADR-0003 — Reuse Glean strongly behind a MINOS-owned CodeKnowledgeStore port

- Status: **Accepted**
- Date: 2026-07-19

## Context

Glean already provides a mature system for collecting, storing, deriving and querying typed facts about source code. It includes a schema system, a declarative query language and integrations for multiple language indexers, including SCIP-based ingestion.

Reimplementing an equivalent fact store and query engine inside MINOS would duplicate substantial existing work.

However, coupling the MINOS domain directly to Glean APIs, Angle queries, storage details or deployment topology would make Glean an irreversible architectural dependency.

MINOS must be able to evolve independently, use lightweight test backends, and potentially support other stores or specialized analysis engines later.

## Decision

MINOS will reuse Glean **strongly** as the preferred initial code knowledge backend.

Glean will be accessed through a MINOS-owned abstraction named conceptually:

```text
CodeKnowledgeStore
```

The dependency direction is:

```text
MINOS Domain / Query Services
            │
            ▼
    CodeKnowledgeStore
            │
            ▼
 GleanCodeKnowledgeStore
            │
            ▼
          Glean
```

The `CodeKnowledgeStore` contract must be defined from MINOS use cases, not by mirroring the Glean API.

The Glean adapter may use Angle, Thrift and Glean-specific schemas internally, but those details must not leak into:

- the MINOS domain model;
- CLI contracts;
- MCP tools;
- REST API contracts;
- NEXUS integration contracts.

## Initial responsibilities expected from the Glean adapter

- ingest or expose indexed code facts;
- resolve symbols and locations;
- query references and implementations;
- query call and type relationships when available;
- execute graph-oriented relationship queries;
- persist MINOS-specific derived facts where beneficial;
- return normalized MINOS results.

## MINOS-specific facts

MINOS may define additional facts or derived relationships, for example:

```text
RELATED_TEST
DEPENDS_ON
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
CONFIDENCE
EVIDENCE
```

These concepts belong to the MINOS intelligence model even when physically persisted through Glean.

## Consequences

### Positive

- Reuses a specialized code-fact database and query engine.
- Avoids rebuilding a complex graph/fact storage platform prematurely.
- Benefits from existing SCIP-to-Glean ingestion paths.
- Enables sophisticated code queries early.
- Keeps MINOS public contracts independent of Glean.
- Allows an in-memory implementation for unit tests.

### Negative

- Glean introduces operational and integration complexity beyond a pure Java embedded database.
- The Glean ecosystem uses technologies outside the primary Java stack.
- Adapter design requires careful mapping between Glean facts and MINOS concepts.
- Some MINOS queries may require custom Glean schemas or derivations.
- Maintaining a replaceable boundary has an upfront design cost.

## Rejected alternatives

### Reimplement the full fact store in SQLite from the beginning

Rejected as the default direction because it would duplicate graph/fact query capabilities that Glean already provides and could slow development of MINOS-specific value.

SQLite may still be useful for project registry metadata, lightweight local metadata, fixtures or a future alternative implementation.

### Expose Glean directly to MINOS consumers

Rejected because CLI, MCP, API and NEXUS contracts would become coupled to Glean-specific concepts and query language.

### Make Glean optional from day one with feature parity across multiple backends

Rejected for M0 because maintaining several production backends before the domain is stable would create unnecessary complexity.

The initial strategy is therefore:

> Glean-first, not Glean-locked.

## M0 exit criteria

Before this ADR is considered technically validated, MINOS must demonstrate that:

1. a representative repository can be indexed into Glean;
2. SCIP-derived facts can be queried successfully;
3. MINOS can expose at least `find_symbol` and `find_usages` through its own normalized contracts;
4. no Glean type is present in the MINOS domain interface;
5. a minimal in-memory `CodeKnowledgeStore` test double can implement the same contract;
6. measured operational complexity remains acceptable for a local-first developer tool.
