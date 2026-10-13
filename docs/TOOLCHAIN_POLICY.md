# MINOS Toolchain Policy

MINOS intentionally targets narrow, reproducible build toolchains. The repository is not expected to compile on arbitrary newer or older JDK, Maven, Gradle or IntelliJ Platform versions until they have been explicitly qualified.

## Current baselines

### Core / CLI / MCP / storage / providers

- Java: **25**, enforced as `[25,26)` by Maven Enforcer. `maven.compiler.release` of the root `pom.xml` is the reference: `scripts/quality/check-toolchain-baseline.py` fails when the enforcer range, a `java-version:` of a workflow, an `eclipse-temurin` base image, this line, `AGENTS.md` or `openspec/config.yaml` states another version.
- Java support: Temurin **25** (LTS) receives updates until at least **2031-09** ([Adoptium support](https://adoptium.net/support/), read on 2026-10-13). The change of baseline is opened no later than **12 months** before that date. Java 24, the previous baseline, received its last update in July 2025 (24.0.2) and reached end of life in September 2025; it was replaced on 2026-10-13 (ADR 0059).
- Maven: **3.9.x**, enforced as `[3.9,4.0)`.
- Canonical build entry point: the checked-in Maven Wrapper (`mvnw` / `mvnw.cmd`).
- CI qualification platforms: Ubuntu 24.04 and Windows Server 2022.

Local core development should use the Maven Wrapper so plugin and reactor behavior match CI. A locally installed Maven may be used for diagnostics, but it is not the release authority.

### IntelliJ plugin

- Java: **21**.
- Gradle: **9.8.1**, through the wrapper `minos-intellij/gradlew` (distribution SHA-256 pinned in `gradle/wrapper/gradle-wrapper.properties`), locally and in CI. The wrapper is the reference: `scripts/quality/check-toolchain-baseline.py` fails when this line, `AGENTS.md` or a `gradle-version:` of a workflow states another version. A Dependabot raise of the wrapper therefore stays red until the same pull request moves them all: the baseline changes in the open, in one place (9.6.1 until two wrapper raises left the policy and the release workflows behind; realigned on 2026-10-12).
- IntelliJ Platform: **2026.1**.
- IntelliJ Platform Gradle Plugin: version pinned in `minos-intellij/build.gradle.kts`.
- Qualification: Linux plugin build/structure/verifier plus Windows unit/process-ownership tests.

The IntelliJ plugin is deliberately an external MINOS client and must not acquire `com.minos:*` implementation dependencies. Its Java 21 baseline is independent from the Java 25 product runtime baseline.

## Upgrade policy

A Java, Maven, Gradle or IntelliJ Platform baseline change is deliberate and must be handled as a compatibility change, not as routine dependency drift.

Before changing the core Java/Maven baseline:

1. verify the full Maven reactor on Linux and Windows;
2. run PostgreSQL-required verification on Linux;
3. run the targeted JaCoCo gates;
4. exercise provider/sandbox qualification paths affected by the runtime change;
5. validate Windows installer and Docker release workflows when their runtime is affected;
6. update developer documentation, CI setup actions, Docker images, installer prerequisites, and runtime diagnostics that state the supported version;
7. promote the new baseline through `develop` before `main`.

Before changing the IntelliJ Java/Gradle/Platform baseline:

1. run the plugin unit suite on Linux and Windows;
2. run `buildPlugin`, `verifyPluginProjectConfiguration`, `verifyPluginStructure` and `verifyPlugin`;
3. re-run Windows process-ownership/cleanup qualification;
4. verify CLI protocol compatibility and the no-`com.minos:*` dependency boundary;
5. update `intellij-plugin.yml`, release qualification and user/developer documentation in the same change.

## When to upgrade

Review a baseline when at least one of the following becomes true:

- the current runtime is no longer appropriate for supported production environments;
- the end-of-support date written in "Current baselines" is less than 12 months away (a baseline that is already out of support is entered in `docs/architecture/risks/register.md` with a deadline until it is replaced);
- a required dependency or security fix requires a newer baseline;
- a newer runtime/platform provides a capability MINOS intentionally adopts;
- CI images, JetBrains compatibility or distribution tooling can no longer reliably provide the current baseline.

Do not widen accepted version ranges merely because newer versions exist. MINOS should first demonstrate that the complete affected runtime, provider, persistence, MCP, installer, sandbox or IDE surface behaves correctly.

## Dependency automation

Dependabot covers Maven, the `minos-intellij` Gradle build, and GitHub Actions. It also covers the Docker images (`docker` and `docker-compose` ecosystems). Major bumps of `eclipse-temurin` are ignored on purpose (`.github/dependabot.yml`): the JDK major follows this policy, not a proposal of the bot. Automated dependency PRs may propose library/plugin updates, but they must not silently change the Java, Maven, Gradle or IntelliJ Platform compatibility contract. Baseline changes require the qualification above.
