# MINOS Toolchain Policy

MINOS intentionally targets a narrow, reproducible build toolchain. The repository is not expected to compile on arbitrary newer or older JDK/Maven versions until they have been explicitly qualified.

## Current baseline

- Java: **24**, enforced as `[24,25)` by Maven Enforcer.
- Maven: **3.9.x**, enforced as `[3.9,4.0)`.
- Canonical build entry point: the checked-in Maven Wrapper (`mvnw` / `mvnw.cmd`).
- CI qualification platforms: Ubuntu 24.04 and Windows Server 2022.

Local development should use the Maven Wrapper so plugin and reactor behavior match CI. A locally installed Maven may be used for diagnostics, but it is not the release authority.

## Upgrade policy

A Java or Maven baseline change is deliberate and must be handled as a compatibility change, not as routine dependency drift. Before changing the enforced range:

1. verify the full Maven reactor on Linux and Windows;
2. run PostgreSQL-required verification on Linux;
3. run the targeted JaCoCo gates;
4. exercise provider/sandbox qualification paths affected by the runtime change;
5. validate Windows installer and Docker release workflows when their runtime is affected;
6. update developer documentation, CI setup actions, Docker images, installer prerequisites, and any runtime diagnostics that state the supported version;
7. promote the new baseline through `develop` before `main`.

## When to upgrade

Review the baseline when at least one of the following becomes true:

- the current JDK is no longer appropriate for supported production environments;
- a required dependency or security fix requires a newer JDK/Maven baseline;
- a newer JDK provides a capability MINOS intentionally adopts;
- CI images or distribution tooling can no longer reliably provide the current baseline.

Do not widen the accepted Java range merely because a newer JDK exists. MINOS should first demonstrate that the complete runtime, provider, persistence, MCP, installer, and sandbox surfaces behave correctly on that JDK.

## Dependency automation

Dependabot may propose Maven and GitHub Actions updates, but toolchain baseline changes still require the explicit qualification above. Automated update PRs must not silently change the enforced Java or Maven compatibility contract.
