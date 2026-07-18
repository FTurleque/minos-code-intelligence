# MINOS Architecture Overview

Status: **Draft / M0**

## 1. Purpose

MINOS is the Code Intelligence layer of the ecosystem. Its responsibility is to transform one or more software repositories into a structured, explainable and queryable representation of code.

MINOS does not decide which information should be injected into an AI prompt. That responsibility belongs to NEXUS.

```text
CODEBASE / WORKSPACE
        │
        ▼
      MINOS
  Code Intelligence
        │
        ▼
      NEXUS
 Context Intelligence
        │
        ▼
 Agent / LLM / IDE
```

## 2. Architectural goals

MINOS must be:

- language-agnostic;
- indexer-agnostic;
- storage-backend-agnostic at the domain boundary;
- local-first;
- independent of any LLM or AI vendor;
- capable of deterministic answers where the underlying evidence allows it;
- explicit about uncertainty when relationships are derived or heuristic;
- optimized for compact machine-consumable responses;
- extensible toward multi-repository workspaces.

## 3. High-level architecture

```text
Repositories / Workspaces
          │
          ▼
Project & Language Discovery
          │
          ├── repository structure
          ├── languages
          ├── build systems
          └── source roots
          │
          ▼
Indexer Orchestrator
          │
          ▼
Indexer Registry
          │
    ┌─────┼───────────────────────────────┐
    ▼     ▼                               ▼
   SCIP  Native Glean Indexers       Other Providers
    │     │                         AST / LSP / LSIF / CPG
    └─────┼───────────────────────────────┘
          ▼
Code Intelligence Ingestion
          │
          ▼
Normalized MINOS Code Model
          │
          ▼
CodeKnowledgeStore
          │
    ┌─────┴────────────┐
    ▼                  ▼
 Glean Adapter      Future Adapter
 preferred
          │
          ▼
MINOS Intelligence Layer
          │
 ┌────────┼───────────┐
 ▼        ▼           ▼
Graph   Analysis    Search
 │        │           │
 └────────┼───────────┘
          ▼
MINOS Query Services
          │
 ┌────────┼──────────────────────────────┐
 ▼        ▼                              ▼
Symbols  Relationships             Context Views
Usages   Dependencies              Compact ranges
Calls    Tests                     Evidence
Impact   Architecture              Confidence
          │
          ▼
   Exposure Layer
    ┌─────┼─────┐
    ▼     ▼     ▼
   CLI   MCP   API
          │
          ▼
        NEXUS
```

## 4. Core separation of responsibilities

### 4.1 Project Discovery

Detects repository structure without performing semantic code analysis.

Responsibilities:

- project roots;
- modules;
- build systems;
- languages;
- source and test roots;
- ignored paths;
- candidate indexer providers.

### 4.2 Indexer Registry

Maintains a registry of available indexing providers.

Providers advertise capabilities rather than being selected only by language.

Example capabilities:

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

A provider may support one language with many capabilities or several languages with a smaller capability set.

### 4.3 Code Intelligence Ingestion

Converts provider-specific data into MINOS concepts.

Possible inputs include:

- SCIP;
- native Glean facts;
- LSIF;
- Language Server output;
- compiler APIs;
- AST analyzers;
- Code Property Graphs;
- future custom analyzers.

No external representation is allowed to leak directly into the MINOS domain model.

### 4.4 Normalized MINOS Code Model

The normalized model provides stable concepts such as:

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

External identifiers such as SCIP symbol identifiers or Glean fact identifiers may be retained as metadata, but they are not the primary domain abstraction.

### 4.5 CodeKnowledgeStore

`CodeKnowledgeStore` is a MINOS-owned port.

It represents the capabilities MINOS needs from a code knowledge backend, not the API of a specific product.

Conceptual operations include:

```text
storeSymbols
storeRelationships
findSymbol
findUsages
findRelationships
traverseDependencies
findCallers
findCallees
queryEvidence
```

The initial preferred implementation is expected to be backed by Glean.

MINOS must not require consumers, domain services or MCP tools to understand Glean, Angle, RocksDB or Thrift.

### 4.6 Intelligence Layer

Adds MINOS-specific derived knowledge on top of indexed facts.

Examples:

```text
DEPENDS_ON
TESTS
RELATED_TEST
IMPACT_PATH
ARCHITECTURAL_ROLE
CENTRALITY
```

Derived information must carry provenance and confidence.

### 4.7 Query Services

The query layer exposes use-case-oriented operations independent of storage technology.

Initial target operations:

```text
findSymbol
findUsages
findImplementations
findDependencies
findDependents
findCallers
findCallees
getRelatedTests
```

Later operations:

```text
analyzeImpact
getArchitectureOverview
getSymbolContext
getModuleContext
```

## 5. Evidence and confidence

Every relationship should be categorized as one of:

```text
RESOLVED
PARTIALLY_RESOLVED
UNRESOLVED
HEURISTIC
```

Every derived or heuristic result should be able to expose:

```text
origin
confidence
evidence
path
```

Example:

```text
Relationship: RELATED_TEST
Source: DocumentIngestionServiceTest
Target: DocumentIngestionService
Resolution: HEURISTIC
Confidence: 0.98
Evidence:
- imports target symbol
- invokes ingest(Document)
- same package hierarchy
```

## 6. SCIP strategy

SCIP is treated as a preferred interoperability protocol where a suitable indexer exists.

It is not part of the MINOS domain model and it is not mandatory for every language.

The intended flow is:

```text
Language-specific tool
        │
        ▼
      SCIP
        │
        ▼
SCIP ingestion adapter
        │
        ▼
MINOS normalized model
```

Alternative flows remain possible.

## 7. Glean strategy

Glean is the preferred M0/MVP candidate for storing and querying detailed code facts because it already provides a code-fact model, schemas and a declarative query engine.

However, Glean is an infrastructure choice, not a domain boundary.

The architecture therefore requires:

```text
MINOS Domain
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

This allows MINOS to use Glean deeply while retaining the ability to:

- replace it if operational constraints become unacceptable;
- use a lighter local backend for small projects;
- introduce a test in-memory backend;
- combine multiple specialized engines in the future.

## 8. Multi-language principle

MINOS must never encode a fixed language list in its core domain.

Java, TypeScript and Python are useful first validation targets because mature semantic indexers exist, but future support may include any language for which a provider can supply sufficient code intelligence.

The core abstraction is therefore capability-based, not language-list-based.

## 9. Future specialized analysis engines

MINOS may later orchestrate specialized engines such as Code Property Graph or data-flow analyzers.

Example:

```text
find_symbol       -> semantic index / SCIP-backed facts
find_usages       -> semantic index / Glean
find_callers      -> Glean / language-specific facts
analyze_data_flow -> future CPG provider
security_analysis -> future specialized provider
```

MINOS remains the facade that chooses and normalizes the result.

## 10. Non-goals for M0

M0 will not attempt to implement:

- a custom Java parser;
- a complete multi-language parser framework;
- perfect dynamic call resolution;
- a production MCP server;
- NEXUS integration;
- embeddings;
- vector search;
- cloud indexing;
- a public REST platform.

The purpose of M0 is to validate architecture and technology choices with measurable spikes.
