# MINOS Roadmap

Status: **Initial proposal**

The roadmap is intentionally evidence-driven. A milestone may change when a spike disproves an architectural assumption.

## M0 — Architecture & Feasibility

Goal: validate the foundation before building product breadth.

Deliverables:

- project bootstrap;
- architecture overview;
- ADRs;
- SCIP evaluation;
- Glean evaluation;
- normalized MINOS model proposal;
- indexer capability model;
- `CodeKnowledgeStore` abstraction;
- Java vertical spike;
- one non-JVM vertical spike;
- benchmark and precision report.

Decision gate:

> Is SCIP + Glean a viable default foundation for MINOS while keeping the MINOS domain decoupled?

## M1 — Project Discovery & Indexer Orchestration

Goal: detect projects and select suitable indexers.

Scope:

- local repository registry;
- workspace concept;
- language detection;
- build-system detection;
- source/test roots;
- `.gitignore` and `.minosignore` strategy;
- `IndexerRegistry`;
- provider capability negotiation;
- index execution lifecycle;
- index status reporting.

## M2 — Normalized Symbol Intelligence

Goal: expose reliable symbol lookup independently of provider/backend.

Scope:

- normalized symbol model;
- stable symbol identity;
- files, modules and locations;
- symbol kinds;
- external and unresolved symbols;
- `find_symbol`;
- `get_file_symbols`;
- lexical symbol search;
- qualified-name search.

M2 exit target:

```text
minos find-symbol <project> <symbol>
```

returns a normalized, compact result.

## M3 — Relationship Intelligence

Goal: expose incoming and outgoing code relationships.

Scope:

- references;
- implementations;
- inheritance;
- calls when supported;
- dependency derivation;
- relationship evidence and provenance;
- `find_usages`;
- `find_implementations`;
- `find_callers`;
- `find_callees`;
- `dependencies`;
- `dependents`.

## M4 — Search & Compact Context

Goal: make MINOS directly useful to tools and agents before MCP exists.

Scope:

- unified structured search;
- compact JSON output;
- result limits;
- depth limits;
- relevant source ranges;
- explicit source retrieval;
- token-efficient response policies;
- query latency benchmarks.

This milestone defines the first **usable MINOS core**.

## M5 — Related Tests & Explainable Derivations

Goal: derive useful relationships that semantic indexers do not necessarily provide directly.

Scope:

- related-test discovery;
- test naming conventions;
- direct symbol references;
- method-call evidence;
- package proximity;
- confidence scoring;
- explainable reasons.

## M6 — Architecture Intelligence

Goal: provide a high-level topology of a repository.

Scope:

- module topology;
- package topology;
- central components;
- dependency concentration;
- technology detection;
- `get_architecture_overview`;
- `get_module_context`.

Architecture inference must remain evidence-based and distinguish detected facts from heuristics.

## M7 — Incremental Indexing

Goal: avoid full repository reindexing when unnecessary.

Scope:

- file fingerprints;
- project/build fingerprints;
- added/changed/deleted files;
- index snapshots;
- invalidation rules;
- provider-specific incremental capabilities;
- full-index fallback.

## M8 — Impact Analysis

Goal: estimate change propagation using known graph relationships.

Scope:

- direct impact;
- indirect impact;
- path explanation;
- confidence;
- depth control;
- related-test impact;
- explicit limitations for dynamic behavior.

## M9 — Stable CLI

Goal: stabilize the developer-facing command line.

Target commands:

```text
minos project add
minos project list
minos index
minos search
minos find-symbol
minos find-usages
minos find-implementations
minos find-callers
minos find-callees
minos dependencies
minos dependents
minos related-tests
minos architecture
minos impact
minos inspect
```

## M10 — MCP Server

Goal: expose compact specialized tools to AI agents.

Candidate tools:

```text
get_project_structure
search_code
find_symbol
find_usages
find_implementations
find_callers
find_callees
find_dependencies
find_dependents
get_related_tests
get_symbol_context
get_file_symbols
get_module_context
get_architecture_overview
analyze_impact
get_index_status
```

MCP remains an exposure layer. No core analysis logic belongs in MCP handlers.

## M11 — API

Goal: support external systems without coupling them to Glean or internal adapters.

Scope:

- project/index operations;
- symbol queries;
- relationship queries;
- architecture queries;
- impact queries;
- stable DTO contracts.

Framework choice remains deferred until this milestone approaches.

## M12 — Multi-repository & Git Intelligence

Goal: expand from isolated local repositories to workspaces and history-aware intelligence.

Possible scope:

- workspace-level symbol resolution;
- cross-repository relationships;
- Git change history;
- symbol churn;
- recent changes;
- change hotspots.

## M13 — NEXUS Integration

Goal: allow NEXUS to consume MINOS Code Intelligence for task-specific context selection.

Boundary:

- MINOS provides facts, relationships, evidence and compact code views;
- NEXUS ranks and selects what enters the AI context.

MINOS must remain fully usable without NEXUS.

## Future exploration

Not committed to the main roadmap:

- CPG/data-flow engines;
- security analysis;
- semantic/vector search;
- embeddings;
- IDE plugins;
- GitHub/GitLab remote indexing;
- distributed indexing;
- hosted service mode.
