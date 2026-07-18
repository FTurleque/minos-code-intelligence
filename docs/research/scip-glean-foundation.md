# SCIP + Glean Foundation Research

Status: **M0 working document**

## Purpose

This document captures the initial technical rationale for using SCIP and Glean as major building blocks of MINOS while preserving MINOS-owned domain contracts.

It is not a substitute for hands-on benchmarks. Every assumption listed here must be validated during M0.

## 1. Why not build every language analyzer ourselves?

Accurate semantic code intelligence requires more than parsing syntax.

A production-quality analyzer may need to understand:

- symbol identity;
- scopes;
- imports;
- overload resolution;
- inheritance;
- implementations;
- type information;
- build configuration;
- dependency classpaths;
- generated sources;
- cross-file references.

Reimplementing this separately for Java, TypeScript, Python, Go, Rust, C/C++, .NET and future languages would consume most of the project effort before MINOS delivers its differentiating value.

MINOS should therefore reuse mature language-specific semantic indexers whenever possible.

## 2. SCIP role

SCIP is treated as an interoperability layer between language-specific indexers and downstream code-intelligence systems.

MINOS should use SCIP for capabilities it represents well, especially semantic symbol occurrences and relationships supplied by the corresponding indexer.

Conceptual flow:

```text
Repository
    │
    ▼
Language-specific semantic indexer
    │
    ▼
SCIP
    │
    ├── symbols
    ├── occurrences
    ├── definitions
    ├── references
    └── relationships available from indexer
    │
    ▼
MINOS ingestion / Glean ingestion
```

### Benefits

- common interchange format;
- avoids a MINOS-specific parser implementation per language;
- allows different indexers to feed a common pipeline;
- separates indexing from querying;
- useful bridge toward Glean.

### Limits

SCIP does not automatically guarantee identical semantic depth across every language.

MINOS must track provider capabilities and quality independently.

Example:

```text
Provider A
- definitions: yes
- references: yes
- implementations: yes
- call graph: partial
- data flow: no

Provider B
- definitions: yes
- references: yes
- call graph: yes
- data flow: yes
```

MINOS must not infer capability solely from the presence of a SCIP file.

## 3. Glean role

Glean is a specialized open-source system for storing, deriving and querying typed facts about source code.

Its architecture is attractive to MINOS because it provides:

- code-oriented fact storage;
- typed schemas;
- fact de-duplication;
- declarative Angle queries;
- relationship-oriented querying;
- support for adding custom facts;
- existing indexing integrations;
- SCIP-based ingestion paths.

Glean can therefore potentially provide much of the low-level knowledge infrastructure beneath MINOS.

## 4. Why Glean is not the MINOS domain

MINOS requires concepts that must remain stable even if its infrastructure changes.

Examples:

```text
SymbolResult
UsageResult
DependencyResult
ImpactResult
Evidence
Confidence
IndexStatus
```

If these concepts directly expose Glean fact IDs, Angle predicates or Thrift-generated types, changing the backend would break every consumer.

The required boundary is therefore:

```text
Consumer
   │
   ▼
MINOS Query Service
   │
   ▼
CodeKnowledgeStore
   │
   ▼
Glean Adapter
   │
   ▼
Glean
```

## 5. What MINOS should reuse from Glean

Candidate capabilities to reuse heavily:

- persistent code-fact storage;
- indexing result ingestion;
- typed fact schemas;
- semantic relationship queries;
- caller/reference/definition queries where supported;
- transitive graph queries;
- custom schemas for MINOS-derived facts;
- efficient local querying.

## 6. What MINOS should own

MINOS should own:

- project/workspace registry;
- language/build discovery;
- indexer registry and capability selection;
- indexer process orchestration;
- normalized public/domain model;
- provenance and confidence model;
- derived dependency semantics;
- related-test analysis;
- impact-analysis semantics;
- architecture overview semantics;
- compact context generation;
- CLI contracts;
- MCP contracts;
- API contracts;
- NEXUS integration contracts.

## 7. Possible storage split

M0 should evaluate whether a split storage model is useful.

Conceptual option:

```text
MINOS metadata
(project registry, configuration, snapshots)
            │
            ▼
    Lightweight local store

Code facts and graph queries
            │
            ▼
          Glean
```

This could keep project-management data simple while using Glean only where its strengths matter.

No storage split is accepted yet; it is an M0 experiment.

## 8. Operational questions for Glean

The M0 spike must answer:

1. How easy is local installation on the target developer environments?
2. Can MINOS package or orchestrate Glean without forcing users to understand its stack?
3. What is the startup cost?
4. What is the indexing cost on small, medium and large repositories?
5. What is the on-disk database size?
6. How are databases isolated per project/workspace?
7. How are schema upgrades managed?
8. How should MINOS communicate with Glean from Java?
9. Is a sidecar process acceptable?
10. Can a distribution be made practical on Windows, Linux and macOS?
11. What happens when Glean is unavailable or its database is corrupted?
12. Can MINOS rebuild all derived state from source/indexes?

## 9. Initial Glean integration options

### Option A — Sidecar process

```text
MINOS JVM
   │
   │ RPC
   ▼
Glean service/process
   │
   ▼
Glean database
```

Advantages:

- isolates non-Java runtime concerns;
- keeps MINOS domain clean;
- follows service/client architecture.

Disadvantages:

- process lifecycle management;
- distribution complexity;
- local ports or IPC concerns.

### Option B — CLI orchestration for M0

```text
MINOS spike
   │
   ▼
Glean CLI
   │
   ▼
Glean DB
```

Advantages:

- fastest validation path;
- minimal integration code.

Disadvantages:

- unsuitable as the long-term application API;
- output parsing may be brittle.

Recommended use: **M0 spike only**.

### Option C — Direct generated RPC client

Evaluate whether generated Thrift/RPC clients provide a maintainable Java integration path.

This is a candidate for post-spike integration if supported cleanly.

## 10. First experiments

### Experiment A — Java semantic indexing

```text
Ariane repository
    │
    ▼
scip-java
    │
    ▼
index.scip
```

Validate:

- classes;
- interfaces;
- methods;
- overloaded methods;
- definitions;
- references;
- implementations;
- Maven multi-module behavior;
- Quarkus/CDI code patterns.

### Experiment B — SCIP to Glean

```text
index.scip
    │
    ▼
scip-to-glean
    │
    ▼
Glean DB
```

Validate queries for:

- symbol definition;
- symbol references;
- implementations;
- callers when available.

### Experiment C — MINOS normalization

Implement only:

```text
find_symbol
find_usages
```

Both queries must return MINOS types, not Glean types.

### Experiment D — Non-Java proof

Run the same conceptual pipeline on at least one non-JVM repository.

The objective is architectural validation, not broad language support.

## 11. Decision gate

At the end of M0, choose one of:

### Proceed

SCIP + Glean provides sufficient precision, performance and local operability.

### Proceed with constraints

Glean remains useful for selected modes or repository sizes but MINOS needs an additional lightweight backend.

### Revise

SCIP remains useful but Glean operational complexity is too high; retain `CodeKnowledgeStore` and select another backend.

### Replace

Both assumptions fail; investigate alternative semantic index and graph architectures while preserving the MINOS domain boundaries.

## 12. Current recommendation

The current architectural recommendation is:

> Reuse SCIP and Glean aggressively to avoid rebuilding mature code-intelligence infrastructure, while ensuring that MINOS owns its domain, orchestration, explainability and public query contracts.

In short:

> **SCIP/Glean provide facts. MINOS provides Code Intelligence. NEXUS provides Context Intelligence.**
