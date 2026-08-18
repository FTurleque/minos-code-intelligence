#!/usr/bin/env python3
"""Fail-closed invariants for the residual post-MNE audit remediation."""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from windows_launcher import assemble, is_assembled_launcher  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ACTIVE_MILESTONE_GATES = (
    "scripts/m21/check-s7-provider.py",
    "scripts/m22/check-provider.py",
    "scripts/m23/check-semantic.py",
    "scripts/m24/check-polyglot.py",
    "scripts/m25/check-remote-distributed.py",
    "scripts/m26/check-runtime-dynamic.py",
    "scripts/m27/check-hosted.py",
    "scripts/m28/check-m28.py",
    "scripts/m28/check-current-docs.py",
)


def read(relative: str) -> str:
    path = ROOT / relative
    if is_assembled_launcher(ROOT, relative):
        # Assert against the launcher the runtime actually writes, not a template.
        return assemble(ROOT, relative)
    if not path.is_file():
        raise RuntimeError(f"missing post-MNE evidence file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle not in text:
            raise RuntimeError(f"{relative}: missing post-MNE invariant: {needle}")


def forbid(relative: str, text: str, *needles: str) -> None:
    for needle in needles:
        if needle in text:
            raise RuntimeError(f"{relative}: forbidden post-MNE regression: {needle}")


def forbid_unbounded_production_properties_loads() -> None:
    allowed = Path("minos-engine/src/main/java/com/minos/io/BoundedProperties.java")
    for source in ROOT.glob("*/src/main/java/**/*.java"):
        relative = source.relative_to(ROOT)
        if relative == allowed:
            continue
        text = source.read_text(encoding="utf-8")
        variables = re.findall(r"\bProperties\s+(\w+)\s*=\s*new\s+Properties\s*\(", text)
        for variable in variables:
            if re.search(rf"\b{re.escape(variable)}\s*\.\s*load\s*\(", text):
                raise RuntimeError(
                    f"{relative}: direct Properties.load must use the common bounded reader")


def run_active_milestone_gates() -> None:
    for relative in ACTIVE_MILESTONE_GATES:
        completed = subprocess.run(
            [sys.executable, relative],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if completed.returncode != 0:
            details = (completed.stderr or completed.stdout).strip()
            raise RuntimeError(f"{relative}: active milestone gate failed: {details}")


def main() -> int:
    try:
        linux = read("minos-runtime-local/src/main/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend.java")
        windows = read("minos-runtime-local/src/main/java/com/minos/runtime/WindowsAppContainerWorkerSandboxBackend.java")
        windows_launcher = read(
            "minos-runtime-local/src/main/resources/com/minos/runtime/windows-appcontainer-sandbox-v4.ps1")
        worker = read("minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java")
        workspace_files = read("minos-runtime-local/src/main/java/com/minos/runtime/ProviderWorkspaceFiles.java")
        worker_contract = read("minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxBackend.java")
        clone_policy = read("minos-integration-git/src/main/java/com/minos/git/RemoteRepositoryCachePolicy.java")
        clone = read("minos-integration-git/src/main/java/com/minos/git/JGitRemoteRepositoryMaterializer.java")
        java_plan = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java")
        source_probe = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/BoundedProviderSourceProbe.java")
        ignore_rules = read("minos-engine/src/main/java/com/minos/source/ProjectIgnoreRules.java")
        runtime_settings = read("minos-application/src/main/java/com/minos/storage/MinosRuntimeSettings.java")
        backend_store = read("minos-app/src/main/java/com/minos/cli/McpBackendConfigurationStore.java")
        path_store = read("minos-application/src/main/java/com/minos/registry/ProjectPathMappingStore.java")
        registry = read("minos-application/src/main/java/com/minos/registry/LocalProjectRegistry.java")
        storage_config = read("minos-application/src/main/java/com/minos/storage/StorageBackendConfiguration.java")
        postgres = read("minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresConnectionFactory.java")
        mcp_tools = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
        mcp_backend = read("minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java")
        json = read("minos-application/src/main/java/com/minos/output/DeterministicJson.java")
        local_storage = read("minos-application/src/main/java/com/minos/storage/LocalStorageBackend.java")
        postgres_storage = read(
            "minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresStorageBackend.java")
        retention_policy = read("minos-application/src/main/java/com/minos/storage/PersistentRetentionPolicy.java")
        indexing = read("minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java")
        distributed = read("minos-runtime-local/src/main/java/com/minos/runtime/DistributedArtifactBundleStore.java")
        shared_cache_leases = read("minos-engine/src/main/java/com/minos/io/SharedCacheLeaseRegistry.java")
        file_tree = read("minos-engine/src/main/java/com/minos/io/FileTreeOperations.java")
        managed_java = read(
            "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipProviderRuntimeManager.java")
        managed_python = read(
            "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedScipPythonRuntimeManager.java")
        managed_polyglot = read(
            "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ManagedPolyglotScipRuntimeManager.java")
        locked_npm = read(
            "minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/LockedNpmPackage.java")
        run_retention = read("minos-runtime-local/src/main/java/com/minos/runtime/RunDirectoryRetention.java")
        executor = read("minos-runtime-local/src/main/java/com/minos/runtime/ProcessIndexerExecutor.java")
        lifecycle = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java")
        installer = read("scripts/install/configure-runtime-settings.ps1")
        docker_services = read("docker/scripts/configure-m30-docker-services.ps1")
        product_facts = read("scripts/docs/product-facts.py")
        readme = read("README.md")
        production = read("docs/user/production-installation.md")
        remote_user = read("docs/user/remote-indexing.md")
        remote_developer = read("docs/developer/remote-worker-sandbox-disposition.md")

        # MINOS-01/02: remote code always requires a platform-qualified sandbox and shared ignores.
        require("WorkerSandboxBackend.java", worker_contract,
                "supportsUntrustedCode", "UNTRUSTED_CODE_SUPPORTED", "qualifiedForCurrentPlatform")
        require("LocalIsolatedIndexWorker.java", worker,
                "!sandboxBackend.supportsUntrustedCode()", "ProviderWorkspaceFiles.copyWorkspace")
        require("ProviderWorkspaceFiles.java", workspace_files,
                "ProjectIgnoreRules.load(source)", "ignoreRules.isHardIgnored(relative)",
                "ignoreRules.isIgnored(relative, false)", "budget.accountTraversalEntry()",
                "budget.accountBytes(read)")
        if worker.index("!sandboxBackend.supportsUntrustedCode()") > worker.index("sandboxBackend.execute"):
            raise RuntimeError("LocalIsolatedIndexWorker.java: trust qualification occurs after execution")
        require("LinuxBubblewrapWorkerSandboxBackend.java", linux,
                "WorkerNetworkPolicy.ALLOW", 'sandbox.add("--share-net")')
        require("WindowsAppContainerWorkerSandboxBackend.java", windows,
                "networkPolicy", "WINDOWS_ALLOW_APPCONTAINER_INTERNET_CLIENT_CAPABILITY_ONLY")
        require("windows-appcontainer-sandbox-v4.ps1", windows_launcher,
                'ConvertStringSidToSid("S-1-15-3-1"', "allowNetwork ? 1u : 0u",
                "$networkPolicy -eq 'ALLOW'")

        # MINOS-03: remote clone and every traversal/cleanup are multidimensionally bounded/streaming.
        require("RemoteRepositoryCachePolicy.java", clone_policy,
                "maxBytes", "maxFiles", "maxDirectories", "maxTraversalEntries", "cloneTimeout")
        require("JGitRemoteRepositoryMaterializer.java", clone,
                "new CloneBudget(repositoryRoot, cachePolicy)", "files > maxFiles", "directories > maxDirectories",
                "traversalEntries > maxTraversalEntries", "Files.walkFileTree",
                "MAX_CACHE_ROOT_SCAN_ENTRIES", "LinkOption.NOFOLLOW_LINKS")
        forbid("JGitRemoteRepositoryMaterializer.java", clone,
               ".sorted(Comparator.reverseOrder()).toList()")

        # 01: managed Linux runtime is exactly <provider>/<version>, never <provider>.
        require("LinuxBubblewrapWorkerSandboxBackend.java", linux,
                "relative.getNameCount() < 2", "relative.subpath(0, 2)")
        forbid("LinuxBubblewrapWorkerSandboxBackend.java", linux,
               "return tools.resolve(relative.getName(0));")

        # 02/03: provider-visible source traversal shares bounded ignore/budget policy.
        require("ProjectIgnoreRules.java", ignore_rules,
                'root.resolve(".gitignore")', 'root.resolve(".minosignore")', "BoundedInputStream")
        require("ScipJavaProcessPlanFactory.java", java_plan,
                "ProjectIgnoreRules.load(root)", "SourceBudgetPolicy.Tracker",
                "budget.accountTraversalEntry()", "budget.accountBytes(read)")
        require("BoundedProviderSourceProbe.java", source_probe,
                "ProjectIgnoreRules.load(normalizedRoot)", "budget.accountRegularFile(attributes.size())")

        # 04: small runtime metadata/secrets use the common bounded reader.
        for relative, text in (
            ("MinosRuntimeSettings.java", runtime_settings),
            ("McpBackendConfigurationStore.java", backend_store),
            ("ProjectPathMappingStore.java", path_store),
            ("LocalProjectRegistry.java", registry),
        ):
            require(relative, text, "BoundedProperties")
        require("MinosRuntimeSettings.java", runtime_settings, "MAX_SECRET_BYTES")

        # 05/08: PostgreSQL external transport/diagnostics and stale connection reuse are hardened.
        require("StorageBackendConfiguration.java", storage_config,
                "postgresManaged", "safePostgresUrl", "managed=")
        require("PostgresConnectionFactory.java", postgres,
                'ALLOWED_URL_PARAMETERS = Set.of("sslmode")', '"verify-full"',
                "connection.isValid", "state.startsWith(\"08\")", "duplicate parameter")
        forbid("PostgresConnectionFactory.java", postgres, "SENSITIVE_URL_PARAMETERS")
        require("configure-runtime-settings.ps1", installer,
                "Assert-ExternalPostgresUrl", "sslmode=verify-full", "Read-BoundedUtf8",
                "'minos.postgres.managed'] = 'false'", "PostgresUrl contains unsupported parameter",
                "PostgresUrl contains duplicate parameter")
        require("configure-m30-docker-services.ps1", docker_services,
                "Read-BoundedUtf8", "'minos.postgres.managed'] = 'true'")

        # 06: accept must not reacquire a global Java monitor around blocking file locks. The local
        # critical section stays striped per cache key, and the cross-process lease is delegated to
        # the bounded registry rather than a raw unbounded channel lock.
        if re.search(r"public\s+synchronized\s+VerifiedArtifact\s+accept", distributed):
            raise RuntimeError("DistributedArtifactBundleStore.java: accept is globally synchronized")
        require("DistributedArtifactBundleStore.java", distributed,
                "ReentrantLock operationLock = operationStripe(cacheKey)",
                "operationStripes[Math.floorMod(cacheKey.hashCode(), operationStripes.length)]",
                "SharedCacheLeaseRegistry", "leases.acquire(cacheKey)", "LEASE_ACQUIRE_TIMEOUT")
        forbid("DistributedArtifactBundleStore.java", distributed, "channel.lock()")
        require("SharedCacheLeaseRegistry.java", shared_cache_leases,
                "FileLock", "tryLock()", "OverlappingFileLockException", "LeaseDeadline",
                "deadline.pauseBeforeRetry")
        forbid("SharedCacheLeaseRegistry.java", shared_cache_leases, "channel.lock()")

        # 06b: cache/runtime cleanup and diagnostic reads stay streaming and bounded.
        require("FileTreeOperations.java", file_tree,
                "Files.walkFileTree", "LinkOption.NOFOLLOW_LINKS", "postVisitDirectory")
        require("DistributedArtifactBundleStore.java", distributed,
                "FileTreeOperations.deleteRecursively", "MAX_CACHE_ROOT_ENTRIES",
                "MAX_CACHE_ENTRY_TREE_ENTRIES", "attributes.isRegularFile()")
        require("ManagedScipProviderRuntimeManager.java", managed_java,
                "BoundedInputStream", "BoundedLineReader", "FileTreeOperations.deleteRecursively")
        require("ManagedScipPythonRuntimeManager.java", managed_python,
                "FileTreeOperations.deleteRecursively")
        require("ManagedPolyglotScipRuntimeManager.java", managed_polyglot,
                "readBoundedText", "FileTreeOperations.deleteRecursively")
        require("LockedNpmPackage.java", locked_npm,
                "BoundedInputStream", "MAX_LOCKFILE_BYTES", "LinkOption.NOFOLLOW_LINKS")
        for relative, text in (
            ("DistributedArtifactBundleStore.java", distributed),
            ("ManagedScipProviderRuntimeManager.java", managed_java),
            ("ManagedScipPythonRuntimeManager.java", managed_python),
            ("ManagedPolyglotScipRuntimeManager.java", managed_polyglot),
        ):
            forbid(relative, text, ".sorted(Comparator.reverseOrder()).toList()", "Files.readAllLines(log)")

        # 07: transient run/staging storage has explicit retention and cleanup.
        require("RunDirectoryRetention.java", run_retention,
                "maxEntries", "maxBytes", "maxAge", "MAX_SCAN_ENTRIES_PER_RUN",
                "MAX_RUN_ROOT_SCAN_ENTRIES", "FileTreeOperations.deleteRecursively")
        require("ProcessIndexerExecutor.java", executor,
                "RunDirectoryRetention.prune(runsRoot, providerRunDirectory.getParent())")
        require("ScipProjectSnapshotLifecycle.java", lifecycle,
                "cleanupProviderWorkspaces", "deleteRecursively(stagedRunRoot)")

        # MINOS-05: the MCP boundary measures UTF-8 and the largest renderer stops upstream.
        require("MinosMcpTools.java", mcp_tools,
                "MAX_RESULT_BYTES", "MCP_RESULT_BUDGET_EXCEEDED", "exceedsUtf8Budget")
        require("MinosApplicationMcpBackend.java", mcp_backend,
                "renderProgramGraph", "MinosMcpTools.MAX_RESULT_BYTES")
        require("DeterministicJson.java", json,
                "maximumUtf8Bytes", "OutputBudgetExceededException", "utf8Bytes")

        # MINOS-06: both durable backends expose retention and the real indexing lifecycle invokes it.
        require("PersistentRetentionPolicy.java", retention_policy,
                "DEFAULT_MAX_HISTORICAL_SNAPSHOTS = 2", "DEFAULT_MAX_SUCCEEDED_RUNS = 20",
                "DEFAULT_MAX_NON_SUCCEEDED_RUNS = 10")
        require("LocalStorageBackend.java", local_storage,
                "new LocalStorageRetentionService", "retentionService()")
        local_retention = read(
            "minos-application/src/main/java/com/minos/storage/LocalStorageRetentionService.java")
        require("LocalStorageRetentionService.java", local_retention,
                "compactWithActiveSnapshot", "CompactionResult::activeSnapshotId")
        require("PostgresStorageBackend.java", postgres_storage,
                "new PostgresStorageRetentionService", "retentionService()")
        require("LocalAutonomousIndexOperations.java", indexing,
                "application.retentionService().compact(prepared.project().id())")
        if indexing.count("application.retentionService().compact(prepared.project().id())") < 4:
            raise RuntimeError("LocalAutonomousIndexOperations.java: retention is not applied on every terminal path")

        # MINOS-07: no production metadata parser may bypass the bounded primitive.
        forbid_unbounded_production_properties_loads()

        # Historical milestone contracts remain executable after later decomposition and hardening.
        run_active_milestone_gates()

        # 09: mutable documentation is gated against authoritative release/sandbox facts.
        require("product-facts.py", product_facts, "check_authoritative_documentation")
        forbid("README.md", readme,
               "#98 sandbox OS worker réelle     🚧 OPEN", "L'issue **#98** reste ouverte")
        forbid("production-installation.md", production,
               "1.0.1` reste **NON PUBLIÉE**", "1.0.1 reste **NON PUBLIÉE**")

        # MINOS-08: active remote documentation states the same qualified/fail-closed contract.
        for relative, text in (
            ("docs/user/remote-indexing.md", remote_user),
            ("docs/developer/remote-worker-sandbox-disposition.md", remote_developer),
        ):
            require(relative, text, "ALLOW", "DENY", "sandbox OS qualifiée", "fail-closed")
            forbid(relative, text,
                   "worker natif accepte", "backend durci futur", "Conditions d’un futur PASS")

        print("POST-MNE REMEDIATION INVARIANTS SUCCESS")
        return 0
    except Exception as exception:
        print(f"POST-MNE REMEDIATION INVARIANTS FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())