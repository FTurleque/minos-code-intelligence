---
id: ADR-0028
title: "Capability-honest program graph and bounded advanced analysis"
domain: backend
rules: false
---

# ADR-0028 — Capability-honest program graph and bounded advanced analysis

> **Archivé le 2026-10-23** (tri du registre, [ADR 0063](0063-ecrire-les-adr-au-format-archgate-dans-le-registre-arc42.md)) : Superseded by [ADR-0072](../ARCH-0072-graphe-de-programme-honnete-sur-ses-capacites.md). Ce document est conservé pour l'historique ; il n'est plus lu par Archgate.

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

## Do's and Don'ts

### Do

- Garder le snapshot Code Knowledge persisté comme autorité ; les program graphs sont des vues reconstructibles.
- Marquer tout flux local dérivé de `READS`/`WRITES` comme potentiel, avec la limitation `EXECUTION_ORDER_NOT_PROVEN`.
- Rejeter, à la composition, les collisions d’identifiant stable dont le contenu diffère.
- Borner l’analyse interprocédurale par la profondeur et les limites de résultats, et signaler cycles et troncature.

### Don't

- Ne pas produire de CFG exact, de def-use exact ni d’annotation de sécurité sans `ProgramGraphProvider` capable.
- Ne pas faire du CPG une seconde source de vérité.
- Ne pas présenter les chemins de sécurité comme preuve exhaustive de présence ou d’absence de vulnérabilité.
- Ne jamais retirer un chemin parce qu’un sanitizer est déclaré.

## Consequences

- Existing snapshots and providers remain compatible.
- M19 can expose useful call/data-flow intelligence immediately where facts exist.
- CFG/data-flow precision can improve provider by provider without branches in core orchestration.
- Consumers can distinguish unavailable, derived and factual capabilities.
- Analysis remains bounded and scalable under the M16 discipline.

## Compliance and Enforcement

- Test `ProgramGraphSecurityRulesProvenanceTest` (`minos-application`, lancé par `mvnw verify`) — limitation `SECURITY_RULES_SUPPLIED_BY_ANALYZED_REPOSITORY` déclarée par les trois analyses ; un sanitizer sur le chemin le marque sans le retirer.
- Test `ProgramGraphComposerLimitationsTest` (`minos-application`) — la troncature déclare ses causes.
- Le harnais de jalon `scripts/history/m19/run-final.ps1` est une archive : aucun workflow ne le lance.
- Revue : une capacité avancée n’est publiée que par un provider capable ; tout flux dérivé porte sa limitation.

## Rejected alternatives

### Infer CFG/data-flow from syntax heuristics by default

Rejected because it would silently turn provider absence into invented structure and would be language-specific in the core.

### Persist a new monolithic CPG as the new source of truth

Rejected because it duplicates the existing snapshot authority, forces migration before measurement, and couples all providers to one advanced representation.

### Report security findings as vulnerabilities

Rejected because static paths are not exhaustive runtime proofs; results must remain explanatory observations with explicit limitations.
