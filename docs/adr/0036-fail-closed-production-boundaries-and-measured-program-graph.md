# ADR-0036 — Fail-closed production boundaries and measured ProgramGraph convergence

- Status: **Proposed**
- Date: 2026-07-31
- Origin: M28

## Context

M22→M27 delivered advanced program analysis, polyglot providers, remote workers, runtime observations and an embedded tenant control plane. The pre-M28 audit found three convergence risks:

1. the Java advanced provider concentrated discovery, parsing, CFG, def-use, interprocedural flow, taint and graph assembly in one implementation;
2. process/workspace isolation could be misread as an OS sandbox even though the native worker cannot enforce network denial;
3. the embedded hosted control plane could be misread as a complete operated SaaS although transport, availability and operator integrations are not supplied.

ProgramGraph also needed an explicit performance decision rather than an assumed storage/cache migration.

## Decision

### 1. Keep the production Java provider exact-snapshot constrained

The production composition uses `FingerprintConstrainedJavaProgramGraphProvider`. A persisted exact project fingerprint is part of the cache key. On a cache miss, every Java source represented by the active structured snapshot is checked against its persisted size and SHA-256 before AST analysis.

A working tree that differs from the active snapshot fails closed with:

```text
JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT
```

The in-memory ProgramGraph cache remains the candidate backend until the M28 cold/warm/modified-source profiles prove a bottleneck requiring another design.

### 2. Decompose analysis responsibilities without changing capability claims

`JavaSourceProgramGraphProvider` is a stable facade. Discovery/fingerprint, AST parsing, CFG, def-use, interprocedural resolution, taint, deterministic graph emission and capability assembly live in focused components.

The decomposition must preserve:

- deterministic node and edge IDs ;
- provider provenance ;
- evidence and confidence ;
- M22 capability/limitation semantics ;
- no compiler attribution claim from parse-only AST data.

### 3. Treat sandbox claims as platform-qualified facts

`WorkerSandboxBackend` exposes a `NetworkGuarantee`; `WorkerSandboxQualification` exposes network-deny, trust and platform dispositions.

The native backend is explicitly classified as:

```text
PROCESS_EPHEMERAL_WORKSPACE
NetworkGuarantee.NONE
FAIL_CLOSED_NOT_ENFORCED
UNTRUSTED_CODE_UNSUPPORTED
WORKER_SANDBOX_CLAIM_PROHIBITED
```

Windows and Linux remain blocked until dedicated OS mechanisms and escape tests are implemented. `DENY` is rejected before provider execution when the selected backend cannot prove OS enforcement.

### 4. Separate the embedded hosted control plane from operated-service claims

`HostedControlPlaneService` is a facade over cohesive tenant, authorization, membership, workspace, retention, token, audit and mutation services.

Operator boundaries are explicit ports:

- `HostedIdentityProvider` ;
- `HostedTenantKeyProvider` ;
- `HostedAuditSink` ;
- `HostedTransportSecurityPort` ;
- `HostedAvailabilityPort`.

The embedded baseline exposes `HostedProductionBoundary.Mode.EMBEDDED_LOCAL_FIRST` and cannot claim qualified transport/TLS, backup/availability, SaaS operation or process isolation.

## Consequences

### Positive

- advanced capabilities are proven from the same production composition used by API, CLI/IDE and MCP ;
- the Java provider can evolve without recreating a monolith ;
- stale-source analysis is prevented rather than merely documented ;
- remote and hosted claims are machine-readable and fail closed ;
- future OS/KMS/IdP/transport/availability adapters have explicit contracts ;
- backend evolution remains measurement-gated.

### Costs

- more focused classes and tests must remain synchronized ;
- exact fingerprint validation adds source hashing on ProgramGraph cache misses ;
- the native remote worker cannot satisfy `DENY` and cannot run untrusted code ;
- the embedded hosted mode is intentionally not sufficient for an operated service ;
- audit-sink export occurs after tenant persistence and therefore needs idempotent retry handling in an operated adapter.

## Rejected alternatives

### Keep the monolithic Java provider

Rejected because it obscures responsibility boundaries, makes targeted qualification harder and increases regression risk.

### Treat process separation as a sandbox

Rejected because it would overclaim filesystem and network guarantees absent from the operating system boundary.

### Silently downgrade `DENY` to `ALLOW`

Rejected because the caller’s security intent would be violated. Failure is mandatory.

### Present embedded hosted mode as production SaaS

Rejected because identity federation, KMS, TLS transport, tenant/process isolation, backup/restore and availability require operator-specific adapters and independent qualification.

### Introduce ANN or a database-backed ProgramGraph cache immediately

Rejected until the M28 performance profile demonstrates a concrete bottleneck.

## Validation

This ADR may become **Accepted** only after the same immutable M28 head passes:

- decomposition and vertical capability gates ;
- Product Facts and module fitness functions ;
- targeted JaCoCo and security-negative tests ;
- ProgramGraph profiles on Windows and Linux ;
- M21-S2 / required-check governance after 1 August 2026.

Execution state and exact-head evidence are maintained in `docs/roadmap/M28_EXECUTION.md` and the M28 milestone evidence, not in this ADR.
