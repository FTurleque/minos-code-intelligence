# ADR-0028 — Capability-honest program graph and bounded advanced analysis

Status: Accepted for M19 implementation

Date: 2026-07-27

## Context

M19 introduces call graph v2, control flow, data flow, interprocedural propagation, a composed Code Property Graph view, Impact v2 and security-oriented paths. Existing MINOS snapshots already persist normalized symbols, occurrences and relationships, but providers do not uniformly expose control-flow or data-flow facts.

Promoting missing provider facts into apparently exact CFG/data-flow edges would violate MINOS's established distinction between factual, derived and heuristic information.

## Decision

MINOS introduces a provider-independent `ProgramGraph` model and `ProgramGraphProvider` SPI.

Advanced program analysis follows these rules:

1. The existing persisted Code Knowledge snapshot remains authoritative.
2. Program graphs are reconstructible views; M19 does not require a new authoritative persistence format.
3. Historical `CALLS` relationships can be projected directly into call-graph edges while preserving nature, confidence, provenance and evidence.
4. Historical `READS`/`WRITES` may only produce explicitly derived *potential* local flow and must carry the `EXECUTION_ORDER_NOT_PROVEN` limitation.
5. Exact CFG, exact def-use and security annotations require an explicit capable `ProgramGraphProvider`; unavailable capabilities remain unavailable.
6. Multiple providers are composed deterministically. Stable-id collisions with different content are rejected instead of silently overwritten.
7. Interprocedural analysis is bounded by request depth/result limits and reports cycles/truncation.
8. The CPG is a composed query view, not a second source of truth.
9. Security analysis reports observed source→sink paths and sanitizers; it does not claim exhaustive vulnerability presence or absence.
   *Implementation note (2026-10-17, AUD-SEC-11):* the Java provider reads its source/sink/sanitizer rules only from the analysed repository (`.minos/java-advanced-provider.properties`), which is untrusted; every response built on such rules carries the additive limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY`, relayed by `minos_program_graph`, `minos_impact_v2` and `minos_security_paths`. A declared sanitizer never removes a path: it is reported with `sanitizedPathObserved=true`. Operator-supplied rules outside the repository are not supported and remain a later, separately decided change.
10. Public API/MCP exposure is additive and versioned independently from the stable `MinosApi` v1 contract.

## Consequences

- Existing snapshots and providers remain compatible.
- M19 can expose useful call/data-flow intelligence immediately where facts exist.
- CFG/data-flow precision can improve provider by provider without branches in core orchestration.
- Consumers can distinguish unavailable, derived and factual capabilities.
- Analysis remains bounded and scalable under the M16 discipline.

## Rejected alternatives

### Infer CFG/data-flow from syntax heuristics by default

Rejected because it would silently turn provider absence into invented structure and would be language-specific in the core.

### Persist a new monolithic CPG as the new source of truth

Rejected because it duplicates the existing snapshot authority, forces migration before measurement, and couples all providers to one advanced representation.

### Report security findings as vulnerabilities

Rejected because static paths are not exhaustive runtime proofs; results must remain explanatory observations with explicit limitations.
