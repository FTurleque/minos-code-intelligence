#!/usr/bin/env python3
"""Fail-closed structural gate for M25 remote and distributed indexing."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "b17631de59871848351a4139b12be6e0354989bc"


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def normalized(value: str) -> str:
    return re.sub(r"\s+", " ", value.replace("`", "").replace("*", "")).strip().casefold()


def require_facts(relative: str, text: str, *facts: str) -> None:
    haystack = normalized(text)
    missing = [fact for fact in facts if normalized(fact) not in haystack]
    if missing:
        raise RuntimeError(f"{relative}: missing semantic facts: {', '.join(missing)}")


def require_pattern(relative: str, text: str, pattern: str, label: str) -> None:
    if not re.search(pattern, text, re.MULTILINE | re.DOTALL):
        raise RuntimeError(f"{relative}: missing {label}")


def forbid(relative: str, text: str, *values: str) -> None:
    haystack = normalized(text)
    present = [value for value in values if normalized(value) in haystack]
    if present:
        raise RuntimeError(f"{relative}: forbidden facts present: {', '.join(present)}")


def main() -> int:
    try:
        request = read("minos-engine/src/main/java/com/minos/remote/RemoteRepositoryRequest.java")
        manifest = read("minos-engine/src/main/java/com/minos/remote/DistributedArtifactManifest.java")
        worker_contract = read("minos-engine/src/main/java/com/minos/remote/DistributedIndexing.java")
        cache_policy = read("minos-integration-git/src/main/java/com/minos/git/RemoteRepositoryCachePolicy.java")
        materializer = read("minos-integration-git/src/main/java/com/minos/git/JGitRemoteRepositoryMaterializer.java")
        artifact_policy = read("minos-runtime-local/src/main/java/com/minos/runtime/DistributedArtifactCachePolicy.java")
        artifact_limits = read("minos-engine/src/main/java/com/minos/orchestration/IndexArtifactLimits.java")
        artifact_store = read("minos-runtime-local/src/main/java/com/minos/runtime/DistributedArtifactBundleStore.java")
        local_worker = read("minos-runtime-local/src/main/java/com/minos/runtime/LocalIsolatedIndexWorker.java")
        sandbox_backend = read("minos-runtime-local/src/main/java/com/minos/runtime/WorkerSandboxBackend.java")
        ignore_rules = read("minos-engine/src/main/java/com/minos/source/ProjectIgnoreRules.java")
        coordinator = read("minos-runtime-local/src/main/java/com/minos/runtime/DistributedIndexerExecutor.java")
        autonomous = read("minos-cli/src/main/java/com/minos/cli/LocalAutonomousIndexOperations.java")
        remote_operations = read("minos-cli/src/main/java/com/minos/cli/LocalRemoteIndexOperations.java")
        remote_command = read("minos-cli/src/main/java/com/minos/cli/RemoteIndexCommand.java")
        cli = read("minos-cli/src/main/java/com/minos/cli/MinosCli.java")
        e2e = read("scripts/m25/run-remote-e2e.py")
        windows_runner = read("scripts/m25/run-final.ps1")
        linux_runner = read("scripts/m25/run-final.sh")

        require_facts("RemoteRepositoryRequest.java", request,
                      'GITHUB("github.com")', 'GITLAB("gitlab.com")', '"https"',
                      "40-character Git SHA-1", "getRawUserInfo", "getRawQuery", "getRawFragment",
                      "refs/heads/", "refs/tags/", "FETCH_ONLY", "projectSubdirectory must stay inside")
        require_pattern("RemoteRepositoryRequest.java", request, r"\[0-9a-fA-F\]\{40\}", "full SHA-1 validation")
        forbid("RemoteRepositoryRequest.java", request, "http://", "ssh://", "git@")

        require_facts("RemoteRepositoryCachePolicy.java", cache_policy, "8", "10L * 1024L * 1024L * 1024L")
        require_facts("JGitRemoteRepositoryMaterializer.java", materializer,
                      "remote-cache", "FileLock", "setCloneSubmodules(false)", "setDepth(1)",
                      "expectedCommit", "isClean", "ATOMIC_MOVE", "deleteCacheTree", "credentials.clear")
        forbid("JGitRemoteRepositoryMaterializer.java", materializer,
               'setProperty("credential', "System.out", "System.err", "LOGGER", "logger")

        require_facts("DistributedIndexing.java", worker_contract,
                      "interface Worker", "WorkerRequest", "WorkerResponse", "DENY", "ALLOW",
                      "PROCESS_EPHEMERAL_WORKSPACE", "Ownership")
        require_facts("DistributedArtifactManifest.java", manifest,
                      "minos-distributed-artifact-v1", "minos-distributed-artifact-v2",
                      "projectRelativeRoot", "index.scip", "artifactSha256",
                      "networkDenyEnforced", "DENY network policy requires an enforced worker boundary",
                      "format v1 cannot carry a non-root projectRelativeRoot",
                      "NUL bytes", "must use '/' as the path separator")
        require_pattern("DistributedArtifactManifest.java", manifest, r"\[0-9a-f\]\{64\}", "lowercase SHA-256 validation")

        require_facts("DistributedArtifactCachePolicy.java", artifact_policy,
                      "32", "5L * 1024L * 1024L * 1024L", "IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES")
        require_facts("IndexArtifactLimits.java", artifact_limits,
                      "MAX_SCIP_ARTIFACT_BYTES = 512L * 1024L * 1024L")
        require_facts("DistributedArtifactBundleStore.java", artifact_store,
                      "manifest.properties", "exactly manifest.properties and index.scip", "MAX_MANIFEST_BYTES",
                      "unsafe or duplicate entry", "artifact checksum", "maxArtifactBytes", "evict",
                      "decodeV1", "decodeV2", "projectRelativeRoot", "FORMAT_V2")
        require_facts("LocalIsolatedIndexWorker.java", local_worker,
                      "ProjectIgnoreRules.load", "supportsUntrustedCode", "isSymbolicLink",
                      "deleteWorkerTree", ".bundle-",
                      "FORMAT_V2", "portableScope", "projectRelativeRoot")
        require_facts("WorkerSandboxBackend.java", sandbox_backend,
                      "native worker cannot prove OS-level network denial", "supportsUntrustedCode")
        require_facts("ProjectIgnoreRules.java", ignore_rules,
                      '".git"', '".minos"', 'root.resolve(".minosignore")')
        require_facts("DistributedIndexerExecutor.java", coordinator,
                      "sourceRepository", "sourceCommit", "providerVersion", "workerId",
                      "networkDenyEnforced", "provenance does not match", "deleteIfExists(response.bundle())",
                      "projectRelativeRoot", "manifestScope", "portableScope")

        require_facts("LocalAutonomousIndexOperations.java", autonomous, "executorDecorator", "UnaryOperator<IndexerExecutor>")
        require_facts("LocalRemoteIndexOperations.java", remote_operations,
                      "JGitRemoteRepositoryMaterializer", "DistributedArtifactBundleStore",
                      "DistributedIndexerExecutor", "force", "verified artifact evidence")
        require_facts("RemoteIndexCommand.java", remote_command,
                      "remote materialize", "remote index", "--ref", "--commit", "--subdir",
                      "--credential-env", "--worker-network", "--provider", "--format")
        require_facts("MinosCli.java", cli, "RemoteIndexCommand.NAME", "remoteIndexCommand.run")
        require_facts("scripts/m25/run-remote-e2e.py", e2e,
                      "scip-go", "0.2.7", "cacheHit", "activeSnapshotId",
                      "artifactSha256", "bundleSha256", "M25 REMOTE INDEXING END-TO-END SUCCESS")
        require_facts("scripts/m25/run-final.ps1", windows_runner,
                      "ExpectedHead", "check-remote-distributed.py", "run-remote-e2e.py",
                      "M25 FINAL REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS")
        require_facts("scripts/m25/run-final.sh", linux_runner,
                      "EXPECTED_HEAD", "check-remote-distributed.py", "run-remote-e2e.py",
                      "M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS")
        forbid("scripts/m25/run-final.ps1", windows_runner, "gh workflow", "gh run", "workflow_dispatch")
        forbid("scripts/m25/run-final.sh", linux_runner, "gh workflow", "gh run", "workflow_dispatch")

        tests = {
            "RemoteRepositoryRequestTest.java": read("minos-engine/src/test/java/com/minos/remote/RemoteRepositoryRequestTest.java"),
            "JGitRemoteRepositoryMaterializerTest.java": read("minos-integration-git/src/test/java/com/minos/git/JGitRemoteRepositoryMaterializerTest.java"),
            "DistributedArtifactManifestTest.java": read("minos-engine/src/test/java/com/minos/remote/DistributedArtifactManifestTest.java"),
            "DistributedArtifactBundleStoreTest.java": read("minos-runtime-local/src/test/java/com/minos/runtime/DistributedArtifactBundleStoreTest.java"),
            "LocalIsolatedIndexWorkerTest.java": read("minos-runtime-local/src/test/java/com/minos/runtime/LocalIsolatedIndexWorkerTest.java"),
            "ScopeSwapRejectionTest.java": read("minos-runtime-local/src/test/java/com/minos/runtime/ScopeSwapRejectionTest.java"),
            "RemoteIndexCommandTest.java": read("minos-cli/src/test/java/com/minos/cli/RemoteIndexCommandTest.java"),
            "LocalRemoteIndexOperationsIntegrationTest.java": read("minos-cli/src/test/java/com/minos/cli/LocalRemoteIndexOperationsIntegrationTest.java"),
        }
        require_facts("RemoteRepositoryRequestTest.java", tests["RemoteRepositoryRequestTest.java"],
                      "rejectsUnsafeHostsUrlsRefsPinsSubdirectoriesAndCredentialNames",
                      "token@github.com", "abc123", "../escape")
        require_facts("JGitRemoteRepositoryMaterializerTest.java", tests["JGitRemoteRepositoryMaterializerTest.java"],
                      "cache", "dirty", "evictsLeastRecentEntryAndRejectsUnexpectedCommitOrMissingSecret",
                      "super-secret-token", "MISSING_REMOTE_TOKEN")
        require_facts("DistributedArtifactManifestTest.java", tests["DistributedArtifactManifestTest.java"],
                      "v1ManifestRejectsNonRootScope", "v2AcceptsNonRootScopeAndNormalizesDot",
                      "rejectsAbsoluteBackslashNulAndOverscopedPaths")
        require_facts("DistributedArtifactBundleStoreTest.java", tests["DistributedArtifactBundleStoreTest.java"],
                      "rejectsTamperingUnknownEntriesAndOversize", "../escape", "evictsOldestArtifact",
                      "differentScopesProduceDifferentCacheKeys", "rejectsTamperedScopeFieldInManifest",
                      "v1ManifestAlwaysDecodesAsRootScope")
        require_facts("LocalIsolatedIndexWorkerTest.java", tests["LocalIsolatedIndexWorkerTest.java"],
                      "FailsClosed", "WrongProvenance", "transport envelopes",
                      "workerPreservesProjectRelativeRootFromRequestInManifest")
        require_facts("ScopeSwapRejectionTest.java", tests["ScopeSwapRejectionTest.java"],
                      "bundleForModuleBIsRejectedWhenModuleARequested",
                      "bundleForModuleAIsRejectedWhenModuleBRequested",
                      "rootBundleIsRejectedWhenModuleRequested",
                      "moduleBundleIsRejectedWhenRootRequested",
                      "v1BundleIsRejectedForNonRootRequest",
                      "differentScopeRequestsNeverShareCacheKey",
                      "provenance")
        require_facts("RemoteIndexCommandTest.java", tests["RemoteIndexCommandTest.java"],
                      "SecretFree", "RequiresExplicitWorkerNetworkPolicy", "token@",
                      "rendersProjectRelativeRootInJsonAndTextOutput")
        require_facts("LocalRemoteIndexOperationsIntegrationTest.java", tests["LocalRemoteIndexOperationsIntegrationTest.java"],
                      "WorkerTransportStagingAndAtomicLocalSnapshot", "loadActiveKnowledge",
                      "projectRelativeRoot")

        execution = read("docs/roadmap/M25_EXECUTION.md")
        adr = read("docs/adr/0033-immutable-remote-revisions-and-verified-worker-artifacts.md")
        user = read("docs/user/remote-indexing.md")
        developer = read("docs/developer/remote-distributed-indexing.md")
        for relative, document in (
            ("docs/roadmap/M25_EXECUTION.md", execution),
            ("docs/adr/0033-immutable-remote-revisions-and-verified-worker-artifacts.md", adr),
            ("docs/user/remote-indexing.md", user),
            ("docs/developer/remote-distributed-indexing.md", developer),
        ):
            require_facts(relative, document, "github.com", "gitlab.com", "SHA", "DENY", "fail-closed",
                          "minos-distributed-artifact-v1", "minos-distributed-artifact-v2",
                          "projectRelativeRoot", "provenance", "borné")
        require_facts("docs/roadmap/M25_EXECUTION.md", execution,
                      "#84", "CLOSED", "completed", "#85", "MERGED",
                      "b17631de59871848351a4139b12be6e0354989bc",
                      "fc395d189cf7fc5a0e06130210a3dc763fc48637",
                      "1a82f18115184606cbc13a9070b7cc78643ebb35", "M25-S9",
                      "M25 FINAL REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS",
                      "M25 LINUX REMOTE DISTRIBUTED INDEXING VALIDATION SUCCESS",
                      "M21-S2 / GitHub Actions reste strictement en pause jusqu’en août 2026")
        require_facts("docs/adr/0033-immutable-remote-revisions-and-verified-worker-artifacts.md", adr,
                      "Status: Accepted — final M25 dispositions recorded from exact-head Windows + Linux evidence.",
                      "PROCESS_EPHEMERAL_WORKSPACE", "ScipSymbolSnapshotImporter",
                      "QUALIFIED_WITH_CONSTRAINTS", "BLOCKED/NOT_RUN")

        print("M25 REMOTE DISTRIBUTED CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M25 REMOTE DISTRIBUTED CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
