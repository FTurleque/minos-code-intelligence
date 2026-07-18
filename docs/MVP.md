# MINOS MVP Definition

Status: **Draft**

## 1. MVP statement

The MINOS MVP must prove that a local software repository can be semantically indexed, normalized and queried through MINOS without requiring an AI model to read the entire repository.

The MVP is successful when MINOS can answer reliable symbol- and relationship-level questions through its own stable contracts while reusing external indexing infrastructure underneath.

## 2. Primary user story

Given a local repository, a developer or agent can index it and ask:

```text
Where is this symbol?
Who uses it?
What does it depend on?
What depends on it?
What implements it?
Who calls it?
```

MINOS returns compact structured results containing locations, relationships and evidence without returning full files by default.

## 3. Initial validation languages

The MVP architecture is language-agnostic.

Implementation validation will begin with:

1. Java as the first primary repository target;
2. at least one non-JVM language to prove that the architecture is not accidentally Java-specific.

Additional languages are added according to provider availability and quality, not by changing the MINOS domain.

## 4. Included capabilities

### Project registration

- register a local repository;
- identify the project root;
- record detected languages;
- record detected build systems;
- track index status and last successful index.

### Indexing

- select an indexer provider from a registry;
- execute a semantic indexer;
- prefer SCIP where an appropriate indexer exists;
- ingest the resulting facts;
- store/query through `CodeKnowledgeStore`;
- use Glean as the preferred real backend.

### Symbol model

Minimum symbol kinds:

```text
CLASS
INTERFACE
RECORD
ENUM
ANNOTATION
METHOD
CONSTRUCTOR
FIELD
FUNCTION
```

The model remains extensible for language-specific concepts.

### Relationships

Initial normalized relationships:

```text
DECLARES
CONTAINS
REFERENCES
EXTENDS
IMPLEMENTS
CALLS
```

Derived relationships may include:

```text
DEPENDS_ON
```

Derived relationships must retain evidence.

### Queries

Required MVP queries:

```text
find_symbol
find_usages
find_implementations
find_dependencies
find_dependents
```

Target additional query if provider data is reliable:

```text
find_callers
find_callees
```

### Output

Every query must support a structured output suitable for machine consumption.

Default results should favor:

```text
symbol
signature
kind
qualified name
location
relationship
relevant source range
evidence
```

over complete source-file content.

## 5. Explicitly excluded from MVP

- complete impact analysis;
- semantic embeddings;
- vector databases;
- mandatory LLM analysis;
- NEXUS integration;
- production MCP server;
- production REST API;
- cloud service;
- GitHub or GitLab remote ingestion;
- IDE plugins;
- perfect dynamic dispatch resolution;
- complete runtime behavior analysis;
- support for every programming language.

## 6. Technical validation criteria

### Symbol precision

On controlled fixtures:

- 100% of expected top-level symbols detected;
- 100% of expected overloaded symbols uniquely identifiable;
- no duplicate normalized symbols for the same declaration.

### Reference precision

On controlled fixtures:

- at least 99% of statically resolvable internal references correctly linked.

### Query correctness

For fixture graphs, expected answers for `find_usages`, dependencies and dependents must be deterministic and asserted automatically.

### Backend isolation

- no Glean-specific type in the MINOS domain contract;
- no Angle query exposed to CLI/MCP/API consumers;
- `InMemoryCodeKnowledgeStore` can satisfy core query-service tests.

### Local-first

- indexing and querying require no mandatory cloud service;
- repository source is not uploaded externally by default.

### Query latency

Initial targets on an already-built local index:

```text
find_symbol p95 < 100 ms
find_usages p95 < 250 ms
one-hop dependency query p95 < 250 ms
```

Targets may be revised after M0 benchmarks, but measurements are mandatory.

### Explainability

100% of heuristic or derived relationships must expose their origin and evidence.

## 7. MVP exit gate

MINOS may be called an MVP only when all of the following are true:

1. a representative Java repository is indexed end-to-end;
2. a repository in at least one additional language validates provider extensibility;
3. `find_symbol` and `find_usages` run through MINOS-owned contracts;
4. dependencies/dependents can be queried or derived with evidence;
5. Glean is hidden behind `CodeKnowledgeStore`;
6. the CLI can return compact structured JSON;
7. automated fixture tests validate symbol and relationship correctness;
8. benchmark results are documented;
9. no mandatory LLM, cloud or NEXUS dependency exists.
