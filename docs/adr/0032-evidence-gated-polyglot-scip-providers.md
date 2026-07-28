# ADR-0032 — Evidence-gated polyglot SCIP providers

Status: **Accepted for M24 implementation; final provider dispositions remain exact-head gated.**

Date: 2026-07-28

## Context

M17 established language/build discovery SPI, provider descriptors, exhaustive capability profiles and provider-neutral runtime composition. M22 then proved that advanced program intelligence must remain capability-honest: a provider emitting symbols and references does not automatically provide CFG, def-use, data-flow or security facts. M23 keeps structured snapshots authoritative and semantic retrieval optional and `HEURISTIC`.

M24 must add C/C++, C#, Go and Rust coverage without creating language switches in the core or treating upstream parser/indexer availability as MINOS support.

The evaluated ecosystems expose viable SCIP paths, but with materially different operational constraints:

- `scip-clang` 0.4.0 indexes C/C++ precisely from a JSON compilation database, but its upstream binary releases are limited to Linux x86_64 and macOS arm64;
- `scip-dotnet` 0.2.14 indexes C#/VB through Roslyn and is distributed as a .NET tool; the release moved its SDK to .NET 10;
- `scip-go` 0.2.7 indexes canonical Go module projects and can be installed reproducibly with `go install` at a pinned version;
- Rust SCIP is emitted by `rust-analyzer scip`; M24 pins the qualified rust-analyzer release to `2026-05-25` / `v0.3.2913`. The `scip-rust` 0.0.6 project is only a thin wrapper around the same command and is not a distinct semantic engine.

## Decision

1. **SCIP remains the preferred interoperability boundary for M24.** All four language integrations feed the existing SCIP ingestion and snapshot pipeline. No new language-specific domain model or storage path is introduced.
2. **Discovery remains SPI-driven.** M24 may extend the `Language` and `BuildSystem` vocabularies, but `ProjectDiscoveryService` itself must not branch on C/C++/C#/Go/Rust.
3. **Each provider receives an explicit, exhaustive capability and operational profile.** The profile records provider id/version, languages, build systems, capability support, limitations, supported qualification platforms, runtime requirements, installation/readiness behavior, stable-identity behavior and provenance behavior.
4. **Provider disposition is evidence-gated.** `QUALIFIED`, `QUALIFIED_WITH_CONSTRAINTS` or `EXPERIMENTAL` may only be assigned after the corresponding fixtures and platform gates pass. A provider that cannot satisfy its claimed runtime/platform contract remains experimental or is documented unsupported for that platform.
5. **Managed installations stay local.** When MINOS installs a provider, files are confined under `MINOS_HOME/tools`; no global npm/dotnet/go/rust installation is performed and no mandatory remote service is introduced.
6. **C/C++ is platform-honest.** M24 may qualify `scip-clang` on Linux x86_64, but it must expose Windows runtime execution as unsupported unless a reproducible Windows provider path is actually proven. Discovery support is not runtime support.
7. **C# and Go use pinned local tool installs.** `scip-dotnet` 0.2.14 is installed with a local tool path and `scip-go` 0.2.7 with a scoped `GOBIN`, both beneath `MINOS_HOME/tools`.
8. **Rust does not silently manage a compiler toolchain.** The provider requires a compatible `cargo`/`rustc` and the pinned `rust-analyzer` version. M24 may manage only the indexer binary if it can do so reproducibly; otherwise readiness is explicit and installation remains unsupported rather than mutating the user's Rust toolchain.
9. **Stable identity is measured, not inferred from SCIP existence.** Repeated fixture indexing must produce stable MINOS symbol identities; namespace/package/module distinctions and overloaded/homonymous symbols must not trivially collide. Raw provider symbols remain preserved as `ProviderReference` evidence.
10. **Provenance is mandatory.** Every normalized symbol/occurrence/relation keeps the provider id, provider version, index run and SCIP origin already represented by MINOS. Missing or unresolved upstream facts remain unresolved; M24 must not fabricate relationships.
11. **Advanced M22 capabilities are not promoted.** New M24 providers default to no claim for CFG, def-use, interprocedural data-flow or security analysis. Those capabilities require separate provider evidence.
12. **M23 semantics remain non-authoritative.** New polyglot snapshots may feed semantic documents through the existing M20/M23 path, but semantic results remain `HEURISTIC`, opt-in and subordinate to structured facts. `KEEP_CURRENT_M20_BACKEND` remains in force.
13. **Public surfaces remain additive.** CLI, Java API, MCP, IntelliJ and NEXUS expose provider/discovery data through shared core contracts; they do not maintain divergent hard-coded language capability tables.
14. **Promotion requires the same exact HEAD on Windows and Linux.** Platform-inapplicable providers must report a documented limitation, not a fake PASS. No GitHub Actions result participates in M24 promotion during July 2026.

## Consequences

### Positive

- MINOS gains real polyglot paths without fragmenting its provider architecture;
- capability claims are comparable across ecosystems and auditable;
- runtime/platform constraints become visible product facts;
- stable identity and provenance remain first-class gates instead of assumptions;
- language additions naturally flow through the existing CLI/API/MCP/IntelliJ/NEXUS surfaces;
- external tool installations remain local-first and reversible.

### Trade-offs

- C/C++ cannot be advertised as a qualified Windows indexing runtime in M24 unless upstream/runtime evidence changes;
- .NET, Go and Rust providers depend on local toolchains whose absence blocks readiness;
- Rust qualification may remain `EXPERIMENTAL` if a pinned cross-platform rust-analyzer execution cannot be reproduced on both qualification platforms;
- provider profiles may differ substantially in structural relationship quality even when symbols/references are strong;
- a shared SCIP transport does not imply shared semantic completeness.

## Rejected alternatives

### Add language-specific branches to the core

Rejected. It would violate M17 and force CLI/MCP/IntelliJ to drift as more ecosystems are added.

### Mark all SCIP-emitting languages as fully supported

Rejected. SCIP is a transport contract, not proof that a particular indexer emits every fact MINOS can consume.

### Infer CFG/data-flow/security from symbol/reference support

Rejected. M22 explicitly separated engine capability from provider evidence.

### Install global toolchains automatically

Rejected. Global `dotnet tool`, `go install` into the user's normal `GOBIN`, `rustup` mutation or opaque system installers would weaken local-first isolation and reproducibility.

### Force C/C++ through an unqualified Windows workaround

Rejected. Upstream `scip-clang` does not publish a supported Windows binary path; an unproved workaround would convert a platform limitation into a false support claim.

### Change the semantic vector backend while adding languages

Rejected. M21-S8/M23 retain `KEEP_CURRENT_M20_BACKEND`; M24 supplies no new measurement justifying ANN/Lucene/vector DB work.
