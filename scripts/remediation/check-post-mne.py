#!/usr/bin/env python3
"""Fail-closed invariants for the residual post-MNE audit remediation."""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(relative: str) -> str:
    path = ROOT / relative
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


def main() -> int:
    try:
        linux = read("minos-runtime-local/src/main/java/com/minos/runtime/LinuxBubblewrapWorkerSandboxBackend.java")
        java_plan = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipJavaProcessPlanFactory.java")
        source_probe = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/BoundedProviderSourceProbe.java")
        ignore_rules = read("minos-engine/src/main/java/com/minos/source/ProjectIgnoreRules.java")
        runtime_settings = read("minos-application/src/main/java/com/minos/storage/MinosRuntimeSettings.java")
        backend_store = read("minos-app/src/main/java/com/minos/cli/McpBackendConfigurationStore.java")
        path_store = read("minos-application/src/main/java/com/minos/registry/ProjectPathMappingStore.java")
        registry = read("minos-application/src/main/java/com/minos/registry/LocalProjectRegistry.java")
        storage_config = read("minos-application/src/main/java/com/minos/storage/StorageBackendConfiguration.java")
        postgres = read("minos-storage-postgresql/src/main/java/com/minos/storage/postgresql/PostgresConnectionFactory.java")
        distributed = read("minos-runtime-local/src/main/java/com/minos/runtime/DistributedArtifactBundleStore.java")
        run_retention = read("minos-runtime-local/src/main/java/com/minos/runtime/RunDirectoryRetention.java")
        executor = read("minos-runtime-local/src/main/java/com/minos/runtime/ProcessIndexerExecutor.java")
        lifecycle = read("minos-provider-scip/src/main/java/com/minos/adapter/scip/runtime/ScipProjectSnapshotLifecycle.java")
        installer = read("scripts/install/configure-runtime-settings.ps1")
        docker_services = read("docker/scripts/configure-m30-docker-services.ps1")
        product_facts = read("scripts/docs/product-facts.py")
        readme = read("README.md")
        production = read("docs/user/production-installation.md")

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
                '"verify-full"', "connection.isValid", "state.startsWith(\"08\")",
                "MINOS_POSTGRES_URL must not contain sensitive parameter")
        require("configure-runtime-settings.ps1", installer,
                "Assert-ExternalPostgresUrl", "sslmode=verify-full", "Read-BoundedUtf8",
                "'minos.postgres.managed'] = 'false'")
        require("configure-m30-docker-services.ps1", docker_services,
                "Read-BoundedUtf8", "'minos.postgres.managed'] = 'true'")

        # 06: accept must not reacquire a global Java monitor around blocking file locks.
        if re.search(r"public\s+synchronized\s+VerifiedArtifact\s+accept", distributed):
            raise RuntimeError("DistributedArtifactBundleStore.java: accept is globally synchronized")
        require("DistributedArtifactBundleStore.java", distributed,
                "ReentrantLock operationLock = leaseStripe(cacheKey)")

        # 07: transient run/staging storage has explicit retention and cleanup.
        require("RunDirectoryRetention.java", run_retention,
                "maxEntries", "maxBytes", "maxAge", "MAX_SCAN_ENTRIES_PER_RUN")
        require("ProcessIndexerExecutor.java", executor,
                "RunDirectoryRetention.prune(runsRoot, providerRunDirectory.getParent())")
        require("ScipProjectSnapshotLifecycle.java", lifecycle,
                "cleanupProviderWorkspaces", "deleteRecursively(stagedRunRoot)")

        # 09: mutable documentation is gated against authoritative release/sandbox facts.
        require("product-facts.py", product_facts, "check_authoritative_documentation")
        forbid("README.md", readme,
               "#98 sandbox OS worker réelle     🚧 OPEN", "L'issue **#98** reste ouverte")
        forbid("production-installation.md", production,
               "1.0.1` reste **NON PUBLIÉE**", "1.0.1 reste **NON PUBLIÉE**")

        print("POST-MNE REMEDIATION INVARIANTS SUCCESS")
        return 0
    except Exception as exception:
        print(f"POST-MNE REMEDIATION INVARIANTS FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
