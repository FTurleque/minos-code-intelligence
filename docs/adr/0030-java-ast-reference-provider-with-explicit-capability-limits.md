# ADR-0030 — Java AST reference provider with explicit capability limits

Status: **Accepted**

Origin: **M22 — Advanced Provider Intelligence**

## Context

M19 introduced a provider-independent program graph and explicitly separated the capability of the analysis engine from the facts actually supplied by a provider. M21 then productionized a local sidecar contract so an external static analyzer can contribute CFG, data-flow, interprocedural and taint facts without changing the structured snapshot.

That architecture still leaves a product gap: a default MINOS installation does not itself produce advanced Java facts beyond normalized relationships unless an external sidecar is supplied.

The temptation would be either to infer missing capabilities from `CALLS/READS/WRITES`, or to run a permissive source heuristic and advertise complete CFG/data-flow coverage. Both would violate MINOS capability honesty.

## Decision

MINOS ships a **reference Java advanced-program provider** based on the public JDK compiler AST APIs.

The provider:

1. analyzes only `.java` files represented by the active structured snapshot;
2. confines all source paths to the registered project root;
3. parses all required source units as one fail-closed operation;
4. derives control-flow and local def-use facts from AST structure;
5. derives interprocedural argument/return flow only when a simple method name + arity identifies exactly one project method;
6. derives security source/sink/sanitizer paths only from explicit project-local rules;
7. publishes each capability only when matching graph facts are actually present;
8. preserves limitations whenever type attribution, external calls, fields, loop fixpoints or runtime behavior are not proven;
9. keeps all advanced output reconstructible and subordinate to the active structured snapshot.

The provider does **not** perform implicit full-classpath compiler attribution in M22 v1. Therefore it does not claim overload/type resolution that it has not established.

The M21 sidecar remains supported and composable. A stronger Java analyzer may later replace or complement the reference provider without changing the `ProgramGraphProvider` contract.

## Information nature

AST nodes directly observed in parsed source are `FACTUAL` with `OriginType.AST`.

Edges produced by control-flow, def-use, interprocedural and taint analysis are `DERIVED`, with explicit confidence, `Evidence` and `OriginType.DERIVED_BY_MINOS`.

No static path is promoted to a runtime fact.

## Security taxonomy

Security classification is opt-in through:

```text
.minos/java-advanced-provider.properties
```

Source, sink and sanitizer method names are explicit local policy. Without this file, `SECURITY_TAINT` is unavailable.

## Consequences

### Positive

- a stock MINOS installation can provide real advanced Java facts without a separate analyzer installation;
- M19 capabilities become useful while remaining capability-honest;
- the provider is local-first and reconstructible;
- source/config changes invalidate the provider cache deterministically;
- precision/recall can be qualified on controlled fixtures;
- external sidecars remain a clean extension point for stronger analyzers.

### Constraints

- v1 interprocedural resolution is intentionally conservative and name+arity based;
- local def-use is not a full SSA engine;
- fields and external calls are not silently modeled;
- exception CFG edges are conservative;
- security flow is intraprocedural and taxonomy-driven;
- TypeScript/Python are not promoted by this ADR.

## Rejected alternatives

### Infer advanced capabilities from existing snapshot relationships

Rejected because `CALLS/READS/WRITES` do not prove CFG, execution order, def-use, argument binding, return binding or taint semantics.

### Advertise Java support based only on successful parsing

Rejected because parser availability is not proof of every advanced capability.

### Require an external Java analyzer immediately

Rejected as the only path because M22 needs a useful local-first reference implementation. External providers remain supported through the sidecar contract.

### Run full compiler attribution with a guessed-classpath

Rejected for M22 v1 because a guessed-classpath or incomplete classpath could turn unresolved semantics into false certainty. A future provider may add attributed analysis only with explicit classpath provenance and qualification.

## Evidence

Operational proof and exact-head qualification are maintained in [`../roadmap/M22_EXECUTION.md`](../roadmap/M22_EXECUTION.md) and the M22 milestone history once promoted.
