# ADR-0036 — Fail-closed production boundaries and measured ProgramGraph convergence

- Status: **Accepted**
- Date: 2026-07-31
- Accepted: 2026-08-09
- Origin: M28

## Context

M22→M27 delivered advanced program analysis, polyglot providers, remote workers, runtime observations and an embedded tenant control plane. The pre-M28 audit identified three convergence risks:

1. the Java advanced provider concentrated discovery, parsing, CFG, def-use, interprocedural flow, taint and graph assembly in one implementation ;
2. process/workspace isolation could be misread as an OS sandbox even though the original native worker could not enforce network denial ;
3. the embedded hosted control plane could be misread as a complete operated SaaS although transport, availability and operator integrations are not supplied.

ProgramGraph also needed an explicit performance decision rather than an assumed storage/cache migration.

The post-1.0.1 audit reopened the sandbox part of this decision as issue #98 and required real platform mechanisms plus negative escape tests before any hostile-code claim could be accepted.

## Decision

### 1. Keep the production Java provider exact-snapshot constrained

The production composition uses `FingerprintConstrainedJavaProgramGraphProvider`. A persisted exact project fingerprint is part of the cache key. On a cache miss, every Java source represented by the active structured snapshot is checked against its persisted size and SHA-256 before AST analysis.

A working tree that differs from the active snapshot fails closed with :

```text
JAVA_ADVANCED_PROVIDER_SOURCE_DIFFERS_FROM_SNAPSHOT_FINGERPRINT
```

The in-memory ProgramGraph cache remains the candidate backend until performance profiles prove a bottleneck requiring another design.

### 2. Decompose analysis responsibilities without changing capability claims

`JavaSourceProgramGraphProvider` remains a stable facade. Discovery/fingerprint, AST parsing, CFG, def-use, interprocedural resolution, taint, deterministic graph emission and capability assembly live in focused components.

The decomposition preserves :

- deterministic node and edge IDs ;
- provider provenance ;
- evidence and confidence ;
- M22 capability/limitation semantics ;
- no compiler attribution claim from parse-only AST data.

### 3. Treat sandbox claims as platform-qualified facts

`WorkerSandboxBackend` exposes a `NetworkGuarantee`; `WorkerSandboxQualification` exposes network-deny, trust and platform dispositions.

The process-only backend remains explicit :

```text
PROCESS_EPHEMERAL_WORKSPACE
NetworkGuarantee.NONE
FAIL_CLOSED_NOT_ENFORCED
UNTRUSTED_CODE_UNSUPPORTED
WORKER_SANDBOX_CLAIM_PROHIBITED
```

`DENY` is rejected before provider execution whenever the selected backend cannot prove OS enforcement.

The post-audit implementation adds two real platform backends.

#### Linux

`LinuxBubblewrapWorkerSandboxBackend` uses :

- `bubblewrap --unshare-all` ;
- isolated network namespace for `DENY`, explicit host network sharing only for `ALLOW` ;
- host root mounted read-only ;
- writable workspace, artifact and run roots only ;
- dropped capabilities ;
- `prlimit` bounds for address space, process count, open files and CPU ;
- a bounded runtime capability probe before `OS_ENFORCED` is advertised.

On Ubuntu 24.04 qualification, the distribution-provided AppArmor profile `bwrap-userns-restrict` is loaded so the same unprivileged-user-namespace boundary exercised by production can be tested. If kernel/LSM policy or required tools do not permit the sandbox, discovery fails and MINOS remains process-only / fail-closed.

#### Windows

`WindowsAppContainerWorkerSandboxBackend` uses :

- an AppContainer token with an empty capability set, including no network capability ;
- verification of `TokenIsAppContainer` before child resume ;
- temporary ACL grants limited to MINOS/provider-owned roots ;
- no mutation of Windows system ACLs ;
- a Job Object with kill-on-close, active-process, job-memory and CPU hard-cap limits.

Both OS implementations are accepted only with negative tests for network/filesystem escape and with a real path test through `ProcessIndexerExecutor → sandbox → provider → artefact`.

### 4. Separate the embedded hosted control plane from operated-service claims

`HostedControlPlaneService` is a facade over cohesive tenant, authorization, membership, workspace, retention, token, audit and mutation services.

Operator boundaries remain explicit ports :

- `HostedIdentityProvider` ;
- `HostedTenantKeyProvider` ;
- `HostedAuditSink` ;
- `HostedTransportSecurityPort` ;
- `HostedAvailabilityPort`.

The embedded baseline exposes `HostedProductionBoundary.Mode.EMBEDDED_LOCAL_FIRST` and cannot claim qualified transport/TLS, backup/availability, SaaS operation or process isolation that has not been independently provided and qualified.

## Consequences

### Positive

- advanced capabilities are proven from the same production composition used by API, CLI/IDE and MCP ;
- the Java provider can evolve without recreating a monolith ;
- stale-source analysis is prevented rather than merely documented ;
- remote and hosted claims are machine-readable and fail closed ;
- Linux and Windows workers can satisfy `DENY` only when an actual OS sandbox is available ;
- sandbox availability itself is capability-probed rather than inferred from an executable name ;
- future KMS/IdP/transport/availability adapters retain explicit contracts ;
- backend evolution remains measurement-gated.

### Costs

- more focused classes and tests must remain synchronized ;
- exact fingerprint validation adds source hashing on ProgramGraph cache misses ;
- Linux sandbox operation depends on available `bubblewrap`/util-linux primitives and an LSM/userns policy that permits them ;
- Windows AppContainer setup requires correct ACL lifecycle and PowerShell/Win32 interop ;
- environments without a qualified sandbox cannot satisfy `DENY` and deliberately fail closed ;
- the embedded hosted mode remains intentionally insufficient for an operated SaaS service ;
- audit-sink export still needs idempotent retry handling in an operated adapter.

## Rejected alternatives

### Keep the monolithic Java provider

Rejected because it obscures responsibility boundaries, makes targeted qualification harder and increases regression risk.

### Treat process separation as a sandbox

Rejected because it would overclaim filesystem and network guarantees absent from the operating-system boundary.

### Advertise a sandbox from tool presence alone

Rejected after post-audit qualification showed that kernel/LSM policy can reject user namespaces even when `bwrap` is installed. A bounded runtime capability probe is mandatory on Linux.

### Silently downgrade `DENY` to `ALLOW`

Rejected because the caller's security intent would be violated. Failure is mandatory.

### Present embedded hosted mode as production SaaS

Rejected because identity federation, KMS, TLS transport, tenant/process isolation, backup/restore and availability require operator-specific adapters and independent qualification.

### Introduce ANN or a database-backed ProgramGraph cache immediately

Rejected until performance profiles demonstrate a concrete bottleneck.

## Validation

This ADR is **Accepted** because the convergence gates have been exercised across the delivered M28+ state and the post-audit remediation adds the previously missing sandbox evidence.

The acceptance evidence includes :

- module fitness functions and decomposition gates ;
- Product Facts and targeted JaCoCo ;
- ProgramGraph and security-negative tests ;
- PostgreSQL/pgvector real integration on Linux ;
- exact-head sandbox qualification on Linux and Windows with no skipped sandbox tests ;
- negative network/filesystem tests and resource-bound checks ;
- real `ProcessIndexerExecutor` execution through each OS sandbox ;
- fail-closed fallback when an OS backend cannot be qualified.

Execution state remains summarized in `docs/STATUS.md`, `docs/ROADMAP.md` and the post-audit PR/issue evidence. Historical M28 evidence remains in `docs/roadmap/M28_EXECUTION.md` and milestone records.
