# ADR-0001 — Keep the MINOS core language- and indexer-agnostic

- Status: **Accepted**
- Date: 2026-07-19

## Context

MINOS is intended to become a Code Intelligence Engine capable of understanding repositories written in multiple languages and using different build systems.

Initial examples focused on Java, TypeScript and Python, but these languages must not become architectural boundaries.

Likewise, no single parser, compiler API, SCIP implementation, Language Server or code-analysis engine can be assumed to cover every language and every required capability equally well.

## Decision

The MINOS domain and query model will be both:

- **language-agnostic**;
- **indexer-agnostic**.

Language-specific indexing is provided through registered providers.

Providers advertise capabilities rather than being selected only by language name.

Conceptual capability examples:

```text
DEFINITIONS
REFERENCES
IMPLEMENTATIONS
TYPE_RELATIONSHIPS
CALL_RELATIONSHIPS
CROSS_FILE
CROSS_MODULE
CROSS_REPOSITORY
CONTROL_FLOW
DATA_FLOW
```

The core must not contain hard-coded branching such as:

```text
if Java -> Java pipeline
if Python -> Python pipeline
if TypeScript -> TypeScript pipeline
```

Instead, an `IndexerRegistry` and capability negotiation mechanism will select suitable providers for a detected project or requested analysis.

Provider-specific models must be normalized before crossing into the MINOS domain.

## Consequences

### Positive

- New languages can be added without rewriting core query services.
- MINOS can combine multiple engines for one language.
- The best provider may be selected according to required capability.
- Future CPG, data-flow or security analyzers can coexist with semantic indexers.
- NEXUS and MCP consumers remain independent of underlying indexing technology.

### Negative

- A normalized model must be designed carefully.
- Capability negotiation introduces orchestration complexity.
- Some provider-specific richness may not map directly to common concepts.
- Integration testing must cover provider interoperability.

## Rejected alternatives

### One parser framework for all languages

Rejected because parser quality, semantic resolution and ecosystem support differ significantly between languages.

### SCIP as the mandatory internal domain model

Rejected because SCIP is an interoperability format, not necessarily the optimal representation for every MINOS analysis and future data source.

### Glean schemas as the MINOS domain model

Rejected because this would couple the public and domain architecture directly to one storage/query backend.
