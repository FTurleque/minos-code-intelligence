#!/usr/bin/env python3
"""Validate authoritative MINOS current-state documentation and release contracts.

The checker deliberately verifies durable product/source contracts, not incidental
wording. Exact-head qualification remains the responsibility of the M29 runners.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def normalized(value: str) -> str:
    value = value.replace("\ufeff", "").replace("\u00a0", " ")
    value = re.sub(r"[`*]+", "", value)
    value = re.sub(r"\s*:\s*", ":", value)
    return re.sub(r"\s+", " ", value).strip().casefold()


def require(relative: str, text: str, expected: str) -> None:
    if normalized(expected) not in normalized(text):
        raise RuntimeError(f"{relative}: missing current fact: {expected}")


def forbid(relative: str, text: str, stale: str) -> None:
    if normalized(stale) in normalized(text):
        raise RuntimeError(f"{relative}: stale current-state text is forbidden: {stale}")


def require_regex(relative: str, text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.IGNORECASE | re.MULTILINE):
        raise RuntimeError(f"{relative}: missing {label}")


def require_file(relative: str) -> None:
    if not (ROOT / relative).is_file():
        raise RuntimeError(f"missing required file: {relative}")


def require_all(relative: str, text: str, tokens: tuple[str, ...]) -> None:
    for token in tokens:
        require(relative, text, token)


def main() -> int:
    try:
        current_files = {
            "README.md": read("README.md"),
            "docs/STATUS.md": read("docs/STATUS.md"),
            "docs/ROADMAP.md": read("docs/ROADMAP.md"),
            "docs/roadmap/M21_EXECUTION.md": read("docs/roadmap/M21_EXECUTION.md"),
            "docs/roadmap/M21_S2_AUGUST_RECOVERY.md": read("docs/roadmap/M21_S2_AUGUST_RECOVERY.md"),
            "docs/roadmap/M28_EXECUTION.md": read("docs/roadmap/M28_EXECUTION.md"),
            "docs/roadmap/M29_EXECUTION.md": read("docs/roadmap/M29_EXECUTION.md"),
            "docs/user/docker-runtime.md": read("docs/user/docker-runtime.md"),
            "docs/user/production-installation.md": read("docs/user/production-installation.md"),
            "docs/releases/1.0.0.md": read("docs/releases/1.0.0.md"),
            "docs/releases/1.0.1.md": read("docs/releases/1.0.1.md"),
        }

        readme = current_files["README.md"]
        status = current_files["docs/STATUS.md"]
        roadmap = current_files["docs/ROADMAP.md"]
        m21 = current_files["docs/roadmap/M21_EXECUTION.md"]
        m21_recovery = current_files["docs/roadmap/M21_S2_AUGUST_RECOVERY.md"]
        m28 = current_files["docs/roadmap/M28_EXECUTION.md"]
        m29 = current_files["docs/roadmap/M29_EXECUTION.md"]
        docker_runtime = current_files["docs/user/docker-runtime.md"]
        install = current_files["docs/user/production-installation.md"]
        release_100 = current_files["docs/releases/1.0.0.md"]
        release_101 = current_files["docs/releases/1.0.1.md"]

        # Authoritative product line: 1.0.0 immutable, 1.0.1 not published.
        for relative, text in (("README.md", readme), ("docs/STATUS.md", status), ("docs/ROADMAP.md", roadmap)):
            require_all(relative, text, ("C0 → M28", "1.0.0", "1.0.1", "#98"))
        require("README.md", readme, "NON PUBLIÉ")

        require_all("docs/STATUS.md", status, (
            "#73 CLOSED / completed",
            "#93 CLOSED / completed",
            "PR de promotion #102",
            "1adbc45339efe37cd26d1937025bfa69d7b57811",
            "M29 #107",
            "m29-autonomous-docker-runtime",
            "M29-S3                           ✅ PASS exact-head 3df1b40...",
            "M29-S4                           ✅ PASS exact-head 3df1b40...",
            "M29-S5                           ✅ PASS exact-head 0959fb9...",
            "M29-S6                           ✅ PASS exact-head f7ef0e3...",
            "M29-S7                           🟨",
            "run-s7.ps1",
            "M29-S8",
        ))
        for stale in (
            "M29-S6                           🟨",
            "run-s6.ps1 exact-head",
        ):
            forbid("docs/STATUS.md", status, stale)

        require_all("docs/ROADMAP.md", roadmap, (
            "M29 — Autonomous Docker Runtime & Native Parity",
            "Issue : **#107**",
            "EN COURS",
            "index-v2.bin",
            "native result == docker result",
            "3df1b40ca0daf50779596f6e955d966ed5eb4973",
            "0959fb9f64e2ecf61e20281f29c694e86d67c62b",
            "f7ef0e3dbe820253decd83a1dc27bf2651ef6de9",
            "M29-S3 | Autonomous Docker administration plane | ✅ PASS exact-head `3df1b40...`",
            "M29-S4 | Provider-complete Docker image | ✅ PASS exact-head `3df1b40...`",
            "M29-S5 | Autonomous indexing & vector lifecycle | ✅ PASS exact-head `0959fb9...`",
            "M29-S6 | Backend-agnostic MCP client integration | ✅ PASS exact-head `f7ef0e3...`",
            "M29-S7 | Installer, switching & lifecycle | 🟨",
            "run-s7.ps1",
        ))
        forbid("docs/ROADMAP.md", roadmap, "M29-S6 | Backend-agnostic MCP client integration | 🟨")

        # Historical dispositions must never regress.
        require_all("docs/roadmap/M21_EXECUTION.md", m21, ("TERMINÉ", "#73 CLOSED / completed", "PASS_WITH_CONSTRAINTS"))
        require_all("docs/roadmap/M21_S2_AUGUST_RECOVERY.md", m21_recovery, ("#102", "MERGED"))
        require_all("docs/roadmap/M28_EXECUTION.md", m28, ("#93 CLOSED / completed", "PR #102 MERGED", "v1.0.0"))

        # M29 execution truth: S1-S6 have exact historical proofs; S7 is the only
        # active qualification gate; S8 remains the parity gate.
        require_all("docs/roadmap/M29_EXECUTION.md", m29, (
            "EN COURS",
            "#107",
            "m29-autonomous-docker-runtime",
            "db33cae87b37f9c2c36e536c96a4ccb6e24df3e5",
            "index-v2.bin",
            "float32",
            "HEURISTIC",
            "M29-S1",
            "M29-S2",
            "M29-S3",
            "M29-S4",
            "M29-S5",
            "M29-S6",
            "M29-S7",
            "M29-S8",
            "417 PASS",
            "McpBackendRouterTest",
            "ProjectPathMappingTest",
            "b780feb7d27bd34952d1952f8d80b06755980684",
            "f39802e966370f0934436163eecc180e4d76a271",
            "45536e2fc7d32ed67932e2715e458fa26a8239b1",
            "workspace/mvnw",
            "error=2, No such file or directory",
            "JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native",
            "3df1b40ca0daf50779596f6e955d966ed5eb4973",
            "M29-S3 DOCKER ADMINISTRATION QUALIFICATION SUCCESS",
            "M29-S4 PROVIDER-COMPLETE DOCKER IMAGE QUALIFICATION SUCCESS",
            "registeredProjectRoot",
            "projectRelativeRoot",
            "fixtures/polyglot/m29-scoped-modules",
            "format 5",
            "local-hash",
            "minos-local-hash",
            "384 dimensions",
            "READY_WITH_SEMANTIC",
            "0959fb9f64e2ecf61e20281f29c694e86d67c62b",
            "M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS",
            "verify-mcp-client-backend-routing.ps1",
            "M29McpClientBackendAgnosticContractTest",
            "byte-identical",
            "f7ef0e3dbe820253decd83a1dc27bf2651ef6de9",
            "M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS",
            "switch-mcp-backend.ps1",
            "probe-mcp-backend.ps1",
            "prepare -> validate -> handshake -> commit backend.properties -> retire ancien backend",
            "New-DockerRuntimeSnapshot",
            "verify-mcp-backend-lifecycle.ps1",
            "M29InstallerBackendLifecycleContractTest",
            "run-s7.ps1",
            "M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS",
            "native result == docker result",
            "#98",
        ))
        require("docs/roadmap/M29_EXECUTION.md", m29, "ne crée pas une nouvelle base vectorielle externe")
        for stale in (
            "Statut : **PLANIFIÉ",
            "démarrage prévu le 3 août 2026",
            "S6 Backend-agnostic MCP client integration implémenté sur un HEAD plus récent",
            "M29-S6 | Backend-agnostic MCP client integration | 🟨",
        ):
            forbid("docs/roadmap/M29_EXECUTION.md", m29, stale)

        # Docker user guide retains the runtime/security/provider/vector facts.
        require_all("docs/user/docker-runtime.md", docker_runtime, (
            "minos-mcp",
            "minos-admin",
            "minos-bootstrap",
            "minos-tools-bootstrap",
            "minos-provider-tools",
            "network_mode: none",
            "cap_drop: ALL",
            "no-new-privileges: true",
            "tools verify --all",
            "provider-inventory.json",
            "provider-binary-sha256.txt",
            "Apache Maven 3.9.16",
            "/var/lib/minos/runs/<run-id>/scip-java/workspace",
            "registeredProjectRoot",
            "projectRelativeRoot",
            "fixtures/polyglot/m29-scoped-modules",
            "installation.json",
            "format 5",
            "local-hash",
            "minos-local-hash",
            "READY_WITH_SEMANTIC",
            "SEMANTIC_SIGNAL_IS_HEURISTIC_NOT_STRUCTURAL_FACT",
            "run-s5.ps1",
        ))

        stale_current = (
            "release 0.2.0 candidate",
            "#73 OPEN",
            "PR #102 OPEN",
            "PR #102 candidate",
            "État livré sur `main` : C0→M20",
            "S2 reste le seul sous-incrément ouvert",
            "M28-S9 — PARTIEL / PENDING",
        )
        for relative, text in current_files.items():
            for stale in stale_current:
                forbid(relative, text, stale)

        require_all("docs/releases/1.0.0.md", release_100, (
            "Tag target",
            "NoClassDefFoundError: org/w3c/dom/Node",
            "ne pas modifier ni retagger",
        ))
        require_all("docs/releases/1.0.1.md", release_101, (
            "NON PUBLIÉ",
            "jdeps",
            "initialize",
            "Codex Desktop",
            "Mode MCP",
            "MCP natif Windows — recommandé",
            "MCP Docker — isolation renforcée",
            "Ne pas configurer maintenant",
            "switch-mcp-backend.ps1",
            "prepare",
            "validate",
            "handshake",
            "backend.properties",
            "%LOCALAPPDATA%\\MINOS",
            "Non / conserver",
            "run-s7.ps1",
        ))
        forbid("docs/releases/1.0.1.md", release_101, "Les deux modes peuvent être activés simultanément")

        # User-facing Windows contract follows the S6/S7 architecture but still
        # states 1.0.1 as unpublished and parity as pending S8.
        require_all("docs/user/production-installation.md", install, (
            "NON PUBLIÉE",
            "Mode MCP",
            "MCP natif Windows — recommandé",
            "MCP Docker — isolation renforcée",
            "Ne pas configurer maintenant",
            "Clients IA",
            "launcher VS Code détecté",
            "Codex Desktop",
            "RUNTIME-MODULES.txt",
            "java.xml",
            "backend.properties",
            "switch-mcp-backend.ps1",
            "probe-mcp-backend.ps1",
            "aucune équivalence métier native/Docker",
            "build-local-windows-candidate.ps1",
            "%LOCALAPPDATA%\\MINOS",
            "Supprimer également toutes les données MINOS locales",
            "Non / conserver",
        ))
        require_regex(
            "docs/user/production-installation.md",
            install,
            r"MINOS-<version>-THIRD-PARTY-NOTICES\.txt",
            "complete Windows release asset list",
        )
        forbid("docs/user/production-installation.md", install, "Les deux modes MCP peuvent être activés simultanément")
        forbid("docs/user/production-installation.md", install, "Ces intégrations sont 100 % natives")

        # Source/release contracts.
        pom = read("pom.xml")
        minos_version = read("minos-application/src/main/java/com/minos/runtime/MinosVersion.java")
        build_distribution = read("scripts/release/build-windows-distribution.ps1")
        build_installer = read("scripts/release/build-windows-installer.ps1")
        publish = read("scripts/release/publish-windows-release.ps1")
        local_candidate = read("scripts/release/build-local-windows-candidate.ps1")
        installer = read("packaging/windows/minos-installer.iss.template")
        zip_installer = read("scripts/install/install-windows.ps1")
        release_workflow = read(".github/workflows/release-windows.yml")
        docker_compose = read("docker/compose.mcp.prod.yaml")
        docker_workflow = read("docker/scripts/prod-mcp-release.ps1")
        docker_image = read("docker/Dockerfile.mcp.release")
        scip_java_plan = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java")
        scope_resolver = read("minos-application/src/main/java/com/minos/orchestration/IndexerExecutionScopeResolver.java")
        lifecycle = read("minos-application/src/main/java/com/minos/orchestration/IndexingLifecycleService.java")
        runtime_ports = read("minos-engine/src/main/java/com/minos/orchestration/IndexingRuntimePorts.java")
        snapshot_lifecycle = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java")
        tools_command = read("minos-cli/src/main/java/com/minos/cli/ToolsCommand.java")
        mcp_clients = read("scripts/install/configure-mcp-clients.ps1")
        codex_mcp = read("scripts/install/configure-codex-mcp.ps1")
        mcp_preflight = read("scripts/install/verify-mcp-client-preflight.ps1")
        mcp_backend_verifier = read("scripts/install/verify-mcp-client-backend-routing.ps1")
        backend_probe = read("scripts/install/probe-mcp-backend.ps1")
        backend_switcher = read("scripts/install/switch-mcp-backend.ps1")
        backend_lifecycle_verifier = read("scripts/install/verify-mcp-backend-lifecycle.ps1")
        installer_verifier = read("scripts/install/verify-installer-template.ps1")
        s6_contract_test = read("minos-app/src/test/java/com/minos/packaging/M29McpClientBackendAgnosticContractTest.java")
        s7_contract_test = read("minos-app/src/test/java/com/minos/packaging/M29InstallerBackendLifecycleContractTest.java")
        s3_runner = read("scripts/m29/run-s3.ps1")
        s4_runner = read("scripts/m29/run-s4.ps1")
        s5_runner = read("scripts/m29/run-s5.ps1")
        s6_runner = read("scripts/m29/run-s6.ps1")
        s7_runner = read("scripts/m29/run-s7.ps1")

        require_all("pom.xml", pom, ("<revision>1.0.1-SNAPSHOT</revision>", "slf4j-nop"))
        require("MinosVersion.java", minos_version, 'DEVELOPMENT_VERSION = "1.0.1-SNAPSHOT"')

        require_all("build-windows-distribution.ps1", build_distribution, (
            "jdeps.exe",
            "--print-module-deps",
            "--list-modules",
            "java.xml",
            "RUNTIME-MODULES.txt",
            "verify-mcp-backend-lifecycle.ps1",
            "probe-mcp-backend.ps1",
            "switch-mcp-backend.ps1",
        ))
        require_all("build-windows-installer.ps1", build_installer, (
            "[switch] $Smoke",
            "MINOS-Release-Smoke-",
            "@@SMOKE_MODE@@",
            "integration\\probe-mcp-backend.ps1",
            "integration\\switch-mcp-backend.ps1",
        ))
        require_all("publish-windows-release.ps1", publish, ("Invoke-McpHandshake", "MinosNativeMcpSmoke.java", "isolated smoke setup"))
        forbid("publish-windows-release.ps1", publish, "!docker")
        require_all("build-local-windows-candidate.ps1", local_candidate, ("Publication   : NOT PERFORMED", "MINOS LOCAL WINDOWS CANDIDATE SUCCESS"))

        # Installer UI and fail-closed selection.
        require_all("minos-installer.iss.template", installer, (
            "Mode MCP",
            "MCP natif Windows — recommandé",
            "MCP Docker — isolation renforcée",
            "Ne pas configurer maintenant",
            "McpModePage := CreateInputOptionPage(",
            "McpModePage.Values[2]",
            "Clients IA",
            "ExistingMcpBackend",
            "LoadStringFromFile(ConfigPath, ConfigText)",
            "detect-mcp-clients.ps1",
            "switch-mcp-backend.ps1",
            "if ConfigureSelectedMcpBackend() then",
            "if not DockerReady() then",
            "aucun fallback silencieux",
            "ShouldRunGlobalCleanup",
            "PromptForUserDataRemoval",
            "DeleteMinosUserData",
            "MB_YESNO or MB_DEFBUTTON2",
            "DelTree(UserDataRoot, True, True, True)",
        ))
        for stale in (
            'Name: "docker"',
            'Name: "mcp_copilot_jetbrains"',
            'Name: "mcp_copilot_cli"',
            'Name: "mcp_claude_code"',
            'Name: "mcp_claude_desktop"',
            'Name: "mcp_codex"',
            "WizardIsTaskSelected('docker')",
            "ConfigureDockerMcp;",
            "ConfigureNativeMcpClients;",
        ):
            forbid("minos-installer.iss.template", installer, stale)

        # ZIP install follows the same backend switcher and owns rollback of its
        # program/PATH mutations around a failed backend activation.
        require_all("install-windows.ps1", zip_installer, (
            "[ValidateSet('none', 'native', 'docker')]",
            "integration\\probe-mcp-backend.ps1",
            "integration\\switch-mcp-backend.ps1",
            "TargetBackend = $McpBackend",
            "DataRoot = $DataRoot",
            "DockerInstallRoot = $DockerInstallRoot",
            "DockerDataRoot = $DockerDataRoot",
            "yyyyMMdd-HHmmssfff",
            "$OriginalUserPath",
            "$PathChanged",
            "SetEnvironmentVariable('Path', $OriginalUserPath, 'User')",
            "Move-Item -LiteralPath $Backup -Destination $InstallRoot",
        ))

        # Docker runtime/product invariants.
        require_all("docker/compose.mcp.prod.yaml", docker_compose, (
            "minos-mcp:",
            "minos-admin:",
            "minos-bootstrap:",
            "minos-tools-bootstrap:",
            "minos-provider-tools:",
            "MINOS_RUNTIME_LOCATION: docker",
            "network_mode: none",
            "MAVEN_OPTS: -Dmaven.repo.local=/var/lib/minos/cache/maven/repository",
            "HOME: /var/lib/minos/cache/home",
            "JAVA_TOOL_OPTIONS: -Djava.io.tmpdir=/run/minos-native -Djna.tmpdir=/run/minos-native",
            'MINOS_SEMANTIC_PROVIDER: "${MINOS_SEMANTIC_PROVIDER:-disabled}"',
        ))
        require_all("docker/scripts/prod-mcp-release.ps1", docker_workflow, (
            "'Admin'",
            "MINOS_HOST_PROJECTS_ROOT",
            "minos-bootstrap",
            "minos-tools-bootstrap",
            "minos-admin",
            "provider-inventory.json",
            "provider-binary-sha256.txt",
            "formatVersion = 5",
            "Resolve-SemanticProvider",
            "disabled, local-hash",
            "semanticProvider = $ResolvedSemanticProvider",
            "'--volumes'",
        ))
        require_all("docker/Dockerfile.mcp.release", docker_image, (
            "FROM eclipse-temurin:24-jdk",
            "MINOS_RUNTIME_LOCATION=docker",
            "MAVEN_VERSION=3.9.16",
            "SCIP_TYPESCRIPT_VERSION=0.4.0",
            "SCIP_JAVA_VERSION=0.13.1",
            "SCIP_PYTHON_VERSION=0.6.6",
            "SCIP_CLANG_VERSION=0.4.0",
            "SCIP_DOTNET_VERSION=0.2.14",
            "SCIP_GO_VERSION=0.2.7",
            "RUST_ANALYZER_RELEASE=2026-07-27",
            "provider-evidence/provider-inventory.json",
        ))

        # S5 scoped provider contract remains locked.
        require_all("ScipJavaProcessPlanFactory.java", scip_java_plan, (
            "STAGING_EXCLUDED_DIRECTORIES",
            "STAGING_EXCLUDED_ROOT_FILES",
            'Set.of("mvnw", "mvnw.cmd")',
            "prepareWritableWorkspace",
            'run.resolve("workspace")',
            "staging workspace must be outside the source project",
        ))
        require_all("IndexerExecutionScopeResolver.java", scope_resolver, ("registeredProjectRoot", "projectRelativeRoot", "MULTI_MODULE", "no compatible module/build root"))
        require_all("IndexingLifecycleService.java", lifecycle, ("scopedTargets", "multi-scope incremental indexing is not qualified", "projectRelativeRoot"))
        require_all("IndexingRuntimePorts.java", runtime_ports, ("registeredProjectRoot", "projectRelativeRoot", "projectRoot must stay inside registeredProjectRoot"))
        require_all("ScipProjectSnapshotLifecycle.java", snapshot_lifecycle, ("scopeKey", "projectRelativeRoot", "provider snapshot collision"))
        require_all("ToolsCommand.java", tools_command, ("verify --all", "parsed.all()", "--all is only valid with tools verify"))

        # S6 client contract remains locked while S7 switches behind it.
        require_all("configure-mcp-clients.ps1", mcp_clients, ("command = $MinosExe", "args = @('mcp')", "MINOS_HOME = $DataRoot", "CopilotJetBrains", "CopilotCli", "ClaudeCode", "ClaudeDesktop", "Codex"))
        forbid("configure-mcp-clients.ps1", mcp_clients, "docker exec")
        require_all("configure-codex-mcp.ps1", codex_mcp, ("[mcp_servers.minos]", 'args = ["mcp"]', "MINOS_HOME", "$MinosExe"))
        forbid("configure-codex-mcp.ps1", codex_mcp, "docker exec")
        require("verify-mcp-client-preflight.ps1", mcp_preflight, "verify-mcp-client-backend-routing.ps1")
        require_all("verify-mcp-client-backend-routing.ps1", mcp_backend_verifier, (
            "Copilot JetBrains",
            "Copilot CLI",
            "Claude Code",
            "Claude Desktop",
            "Codex CLI/Desktop",
            "backend.properties",
            "backend=native",
            "backend=docker",
            "native -> docker changes backend.properties only",
            "client configs remain byte-identical",
            "MINOS MCP BACKEND-AGNOSTIC CLIENT ROUTING VERIFICATION SUCCESS",
        ))
        require_all("M29McpClientBackendAgnosticContractTest.java", s6_contract_test, ("everySupportedClientTargetsStableMinosMcpEntrypoint", "backend.properties", "byte-identical", "run-s6.ps1"))

        # S7 handshake/switch/rollback contracts.
        require_all("probe-mcp-backend.ps1", backend_probe, (
            "notifications/initialized",
            "tools/list",
            "minos_search_code",
            "minos_impact",
            "MINOS MCP BACKEND HANDSHAKE SUCCESS",
        ))
        require_all("switch-mcp-backend.ps1", backend_switcher, (
            "Test-ReusableDockerRuntime",
            "PREPARE target=docker mode=install result=success",
            "PREPARE target=docker mode=reuse result=success",
            "HANDSHAKE target=$TargetBackend result=success",
            "COMMIT backend=$TargetBackend result=success",
            "Restore-BackendConfiguration",
            "New-DockerRuntimeSnapshot",
            "Restore-DockerRuntimeSnapshot",
            "ROLLBACK upgrade=docker runtime=restored action=start result=success",
            "MINOS MCP BACKEND SWITCH SUCCESS",
        ))
        require_all("verify-mcp-backend-lifecycle.ps1", backend_lifecycle_verifier, (
            "Fresh native backend selection was not committed",
            "Same-version native -> Docker rebuilt the managed runtime instead of reusing it",
            "Retirement failure did not restore the previous Docker config",
            "Docker package upgrade did not prepare a new runtime generation",
            "Failed Docker upgrade did not restore the previous runtime generation",
            "Backend lifecycle modified unrelated third-party client configuration",
            "MINOS MCP BACKEND TRANSACTIONAL LIFECYCLE VERIFICATION SUCCESS",
        ))
        require_all("verify-installer-template.ps1", installer_verifier, (
            "exclusive and require exactly one choice",
            "switch-mcp-backend.ps1",
            "no-MCP selection",
            "fail-closed backend selection",
            "User data must never be deleted unconditionally",
            "MINOS INSTALLER TEMPLATE VERIFICATION SUCCESS",
        ))
        require_all("M29InstallerBackendLifecycleContractTest.java", s7_contract_test, (
            "installerAndZipUseOneTransactionalBackendLifecycle",
            "Test-ReusableDockerRuntime",
            "Restore-DockerRuntimeSnapshot",
            "Ne pas configurer maintenant",
            "run-s7.ps1",
            "M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS",
        ))

        # Historical/exact-head runners remain present; S7 is the current gate.
        require_all("run-s3.ps1", s3_runner, ("'index', 'm29-s3-fixture'", "Invoke-McpHandshake", "FAIL_OR_BLOCKED"))
        require_all("run-s4.ps1", s4_runner, ("M29-S4 exact-head mismatch", "provider-inventory.json", "provider-binary-sha256.txt", "run-s5.ps1"))
        require_all("run-s5.ps1", s5_runner, ("M29-S5 exact-head mismatch", "m29-scoped-modules", "minos-local-hash", "index-v2.bin", "READY_WITH_SEMANTIC", "NO_CHANGES", "M29-S5 AUTONOMOUS INDEXING AND VECTOR LIFECYCLE QUALIFICATION SUCCESS"))
        require_all("run-s6.ps1", s6_runner, ("M29-S6 exact-head mismatch", "verify-mcp-client-backend-routing.ps1", "M29-S6 BACKEND-AGNOSTIC MCP CLIENT QUALIFICATION SUCCESS"))
        require_all("run-s7.ps1", s7_runner, (
            "M29-S7 exact-head mismatch",
            "M29-S7 PowerShell parse preflight SUCCESS",
            "verify-mcp-backend-lifecycle.ps1",
            "verify-mcp-client-backend-routing.ps1",
            "verify-installer-template.ps1",
            "build-windows-distribution.ps1",
            "build-windows-installer.ps1",
            "M29-S7 native-only install SUCCESS",
            "M29-S7 ZIP upgrade SUCCESS",
            "M29-S7 Docker-only install SUCCESS",
            "M29-S7 Docker -> native SUCCESS",
            "M29-S7 native -> Docker reuse SUCCESS",
            "M29-S7 uninstall-preserve SUCCESS",
            "M29-S7 explicit purge SUCCESS",
            "M29-S7 INSTALLER SWITCHING AND LIFECYCLE QUALIFICATION SUCCESS",
        ))

        # Controlled S5 fixture must remain a monorepo without a root TS manifest.
        fixture = ROOT / "fixtures/polyglot/m29-scoped-modules"
        for relative in (
            "fixtures/polyglot/m29-scoped-modules/pom.xml",
            "fixtures/polyglot/m29-scoped-modules/src/main/java/com/minos/fixture/RootGreeting.java",
            "fixtures/polyglot/m29-scoped-modules/ui/app/package.json",
            "fixtures/polyglot/m29-scoped-modules/ui/app/tsconfig.json",
            "fixtures/polyglot/m29-scoped-modules/ui/lib/package.json",
            "fixtures/polyglot/m29-scoped-modules/ui/lib/tsconfig.json",
        ):
            require_file(relative)
        if fixture.joinpath("package.json").exists() or fixture.joinpath("tsconfig.json").exists():
            raise RuntimeError("M29 S5 fixture must not contain a root TypeScript manifest")

        require_all("release-windows.yml", release_workflow, ("default: '1.0.1'", "workflow_dispatch"))
        if (ROOT / ".github/workflows/release-v1.0.0.yml").exists():
            raise RuntimeError("completed one-shot release-v1.0.0.yml must not remain on the maintenance line")

        print("MINOS CURRENT DOCUMENTATION AND RELEASE CONTRACT CONSISTENCY SUCCESS")
        return 0
    except Exception as exc:
        print(f"MINOS CURRENT DOCUMENTATION AND RELEASE CONTRACT CONSISTENCY FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
