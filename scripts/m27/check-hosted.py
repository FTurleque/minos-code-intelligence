#!/usr/bin/env python3
"""Fail-closed structural gate for M27 Team / Hosted Mode."""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "5db06f2a778b60b318ae6d83ad76928c24672810"
QUALIFIED_HEAD = "d4bd51ef52cb329ab75b70b32bc22e2b236bd65d"
MERGE_DEVELOP = "ee22c3b39b9cd891c18cb61188eb8e973fc7e822"


def read(relative: str) -> str:
    path = ROOT / relative
    if not path.is_file():
        raise RuntimeError(f"missing required file: {relative}")
    return path.read_text(encoding="utf-8")


def require(relative: str, text: str, *facts: str) -> None:
    for fact in facts:
        if fact not in text:
            raise RuntimeError(f"{relative}: missing required fact: {fact}")


def require_pattern(relative: str, text: str, pattern: str, description: str) -> None:
    if re.search(pattern, text) is None:
        raise RuntimeError(f"{relative}: missing required contract: {description}")


def forbid(relative: str, text: str, *facts: str) -> None:
    lowered = text.casefold()
    for fact in facts:
        if fact.casefold() in lowered:
            raise RuntimeError(f"{relative}: forbidden M27 weakening: {fact}")


def tool_names(source: str) -> list[str]:
    return re.findall(r'tool\("(minos_[a-z0-9_]+)"', source)


def main() -> int:
    try:
        role = read("minos-domain/src/main/java/com/minos/hosted/HostedRole.java")
        state = read("minos-domain/src/main/java/com/minos/hosted/HostedTenantState.java")
        workspace = read("minos-domain/src/main/java/com/minos/hosted/SharedWorkspace.java")
        token = read("minos-application/src/main/java/com/minos/hosted/HmacHostedIdentityProvider.java")
        service = read("minos-application/src/main/java/com/minos/hosted/HostedControlPlaneService.java")
        authorization = read("minos-application/src/main/java/com/minos/hosted/HostedAuthorizationService.java")
        audit_chain = read("minos-application/src/main/java/com/minos/hosted/HostedAuditChain.java")
        membership_service = read("minos-application/src/main/java/com/minos/hosted/HostedMembershipService.java")
        workspace_service = read("minos-application/src/main/java/com/minos/hosted/HostedWorkspaceService.java")
        key_provider = read("minos-storage-local/src/main/java/com/minos/store/EnvironmentHostedTenantKeyProvider.java")
        store = read("minos-storage-local/src/main/java/com/minos/store/FileHostedControlPlaneStore.java")
        app = read("minos-application/src/main/java/com/minos/application/MinosApplication.java")
        command = read("minos-cli/src/main/java/com/minos/cli/TeamCommand.java")
        api = read("minos-api/src/main/java/com/minos/api/MinosTeamApi.java")
        api_impl = read("minos-api/src/main/java/com/minos/api/LocalMinosTeamApi.java")
        mcp = read("minos-mcp/src/main/java/com/minos/mcp/MinosMcpTools.java")
        backend = read("minos-mcp/src/main/java/com/minos/mcp/MinosApplicationMcpBackend.java")

        require("HostedRole.java", role, "OWNER", "ADMIN", "CONTRIBUTOR", "VIEWER", "AUDITOR",
                "HostedPermission.KEY_ROTATE", "HostedPermission.AUDIT_READ")
        require("HostedTenantState.java", state, "MAX_MEMBERS = 1_024", "MAX_WORKSPACES = 512",
                "tenant requires at least one owner", "broken audit hash chain", "cross-tenant workspace")
        require("SharedWorkspace.java", workspace, "MAX_BINDINGS = 128", "duplicate project binding")
        require("HmacHostedIdentityProvider.java", token, 'TOKEN_PREFIX = "mht1"', "MAX_TOKEN_BYTES",
                "MAX_TOKEN_LIFETIME = Duration.ofHours(24)", "MessageDigest.isEqual", "expiresAt()")

        require("EnvironmentHostedTenantKeyProvider.java", key_provider, 'ENV_PREFIX = "MINOS_TEAM_KEY_"',
                "Base64.getDecoder", "key must decode to exactly 32 bytes", "HmacSHA256")
        require("FileHostedControlPlaneStore.java", store, 'Cipher.getInstance("AES/GCM/NoPadding")',
                "GCMParameterSpec(GCM_TAG_BITS", "ATOMIC_MOVE", "FileLock", "DEFAULT_MAX_TENANT_BYTES",
                "must not be a symbolic link", "authentication tag mismatch",
                "hosted tenant concurrent modification")
        forbid("FileHostedControlPlaneStore.java", store, "ObjectInputStream", "ObjectOutputStream")

        require("HostedControlPlaneService.java", service, "retentionPlan", "applyRetention", "rotateKey")
        require("HostedAuthorizationService.java", authorization, "authorizeMutation",
                "HostedAuditEvent.Outcome.DENIED", "hosted permission denied", "auditChain.verify(state)")
        require("HostedAuditChain.java", audit_chain, "HmacSHA256")
        require("HostedWorkspaceService.java", workspace_service, "requireSnapshot(projectId, snapshotId)")
        require("HostedMembershipService.java", membership_service,
                "cannot remove or demote the last tenant owner")
        forbid("HostedControlPlaneService.java", service, "ProjectDiscoveryService", "ProviderCapability")
        require("MinosApplication.java", app, 'HOSTED_MODE_ENV = "MINOS_HOSTED_MODE"',
                '"enabled"', 'home.resolve("hosted-control-plane")', "hostedTenantKeyProvider",
                "hostedControlPlaneService")

        require("TeamCommand.java", command, 'TOKEN_ENVIRONMENT_VARIABLE = "MINOS_TEAM_TOKEN"',
                "bearer tokens are accepted only through", "workspace-create", "member-grant", "project-bind",
                "key-rotate", "retention-plan", "retention-apply", "audit")
        require("MinosTeamApi.java", api, "interface MinosTeamApi", "BootstrapRequest", "RetentionApplyDto",
                "rotateKey", "bindProject")
        require("LocalMinosTeamApi.java", api_impl, "HostedControlPlaneService", "team mode is disabled",
                "SECRET_OUTPUT_ONCE_DO_NOT_LOG")

        names = tool_names(mcp)
        declared = re.search(r"TOOL_COUNT\s*=\s*(\d+)", mcp)
        if not declared or int(declared.group(1)) != len(names) or len(names) < 31:
            raise RuntimeError(f"MCP catalogue mismatch: declared={declared.group(1) if declared else None} actual={len(names)}")
        team_tools = {name for name in names if name.startswith("minos_team_")}
        expected_team = {"minos_team_tenant", "minos_team_workspaces", "minos_team_workspace",
                         "minos_team_members", "minos_team_audit"}
        if team_tools != expected_team:
            raise RuntimeError(f"M27 MCP team catalogue is not the exact read-only set: {sorted(team_tools)}")
        forbid("MinosMcpTools.java", mcp, "bearerToken", "minos_team_bootstrap", "minos_team_create",
               "minos_team_rotate", "minos_team_retention_apply")
        require("MinosApplicationMcpBackend.java", backend, 'System.getenv("MINOS_TEAM_TOKEN")',
                "hosted().tenant(hostedToken())", "hosted().audit(hostedToken(), limit)",
                "MINOS team mode is disabled")

        tests = {
            "HostedModelTest.java": read("minos-domain/src/test/java/com/minos/hosted/HostedModelTest.java"),
            "FileHostedControlPlaneStoreTest.java": read("minos-storage-local/src/test/java/com/minos/store/FileHostedControlPlaneStoreTest.java"),
            "HostedControlPlaneServiceTest.java": read("minos-application/src/test/java/com/minos/hosted/HostedControlPlaneServiceTest.java"),
            "TeamCommandTest.java": read("minos-cli/src/test/java/com/minos/cli/TeamCommandTest.java"),
            "LocalMinosTeamApiTest.java": read("minos-api/src/test/java/com/minos/api/LocalMinosTeamApiTest.java"),
            "MinosApplicationMcpBackendM27Test.java": read("minos-mcp/src/test/java/com/minos/mcp/MinosApplicationMcpBackendM27Test.java"),
            "SharedMinosApplicationIntegrationTest.java": read("minos-app/src/test/java/com/minos/application/SharedMinosApplicationIntegrationTest.java"),
        }
        require("FileHostedControlPlaneStoreTest.java", tests["FileHostedControlPlaneStoreTest.java"],
                "persistsOnlyCiphertextAndRoundTripsTenantState", "rejectsTamperingBeforePlaintextDeserialization",
                "enforcesOptimisticConcurrencyAndSupportsExplicitKeyRotation", "rejectsTenantFileSymlink")
        require("HostedControlPlaneServiceTest.java", tests["HostedControlPlaneServiceTest.java"],
                "authenticatesAndEnforcesRbacWhileAuditingDeniedMutations", "rejectsTamperedExpiredAndCrossTenantAccess",
                "preservesLastOwnerAndVerifiesExactSnapshotBindings", "rotatesEncryptionAndSigningKeyAndInvalidatesOldToken",
                "plansAndAppliesRetentionOnlyWhenExplicitlyRequested")
        require("TeamCommandTest.java", tests["TeamCommandTest.java"], "NeverAcceptsBearerTokenAsArgument",
                "DisabledTeamModeWithoutBreakingLocalCli")
        require("LocalMinosTeamApiTest.java", tests["LocalMinosTeamApiTest.java"], "JdkOnlyDtos", "FailClosed")
        require("MinosApplicationMcpBackendM27Test.java", tests["MinosApplicationMcpBackendM27Test.java"],
                "CredentialsOnlyFromSupplier")
        require("SharedMinosApplicationIntegrationTest.java", tests["SharedMinosApplicationIntegrationTest.java"],
                "MinosMcpTools.TOOL_COUNT", "minos_team_tenant", "minos_team_audit")
        forbid("SharedMinosApplicationIntegrationTest.java", tests["SharedMinosApplicationIntegrationTest.java"],
               "assertEquals(26, mcpTools.size())")

        e2e = read("scripts/m27/run-hosted-e2e.py")
        windows = read("scripts/m27/run-final.ps1")
        linux = read("scripts/m27/run-final.sh")
        require("run-hosted-e2e.py", e2e, "M27 TEAM HOSTED END-TO-END SUCCESS", "crossTenantLeak",
                "viewerMutationDenied", "staleRejected", "tamperRejected", "oldKeyRejected",
                "secret JSON output redacted")
        require("run-final.ps1", windows, "ExpectedHead", "check-hosted.py", "run-hosted-e2e.py",
                "M27 FINAL TEAM HOSTED MODE VALIDATION SUCCESS")
        require("run-final.sh", linux, "EXPECTED_HEAD", "check-hosted.py", "run-hosted-e2e.py",
                "M27 LINUX TEAM HOSTED MODE VALIDATION SUCCESS")
        forbid("run-final.ps1", windows, "gh workflow", "gh run", "workflow_dispatch")
        forbid("run-final.sh", linux, "gh workflow", "gh run", "workflow_dispatch")

        documents = {
            "docs/roadmap/M27_EXECUTION.md": read("docs/roadmap/M27_EXECUTION.md"),
            "docs/adr/0035-opt-in-tenant-control-plane-with-external-keys.md": read("docs/adr/0035-opt-in-tenant-control-plane-with-external-keys.md"),
            "docs/user/team-hosted-mode.md": read("docs/user/team-hosted-mode.md"),
            "docs/developer/team-hosted-mode.md": read("docs/developer/team-hosted-mode.md"),
        }
        for relative, document in documents.items():
            require(relative, document, "opt-in", "tenant", "AES-256-GCM", "audit")
            if "rétention" not in document.casefold() and "retention" not in document.casefold():
                raise RuntimeError(f"{relative}: missing retention contract")
        execution = documents["docs/roadmap/M27_EXECUTION.md"]
        require("M27_EXECUTION.md", execution, "#90", "#91", BASE, QUALIFIED_HEAD, MERGE_DEVELOP,
                "M27-S9", "QUALIFIED_WITH_CONSTRAINTS", "strictement en pause jusqu’en août 2026")
        forbid("M27_EXECUTION.md", execution, "CANDIDATE_FOR_QUALIFICATION",
               "Qualified HEAD : PENDING", "Merge develop  : PENDING")
        adr = documents["docs/adr/0035-opt-in-tenant-control-plane-with-external-keys.md"]
        require_pattern("ADR-0035", adr,
                        r"(?im)^\s*-\s*Status:\s*\*\*Accepted(?:\s+—[^*]+)?\*\*\s*$",
                        "accepted ADR status, with optional final-evidence qualifier")
        require("ADR-0035", adr, "external keys", "MINOS_TEAM_TOKEN", QUALIFIED_HEAD, MERGE_DEVELOP)

        quality = read("scripts/quality/check-jacoco.py")
        require("check-jacoco.py", quality, '"m27-team-hosted-control-plane"',
                "FileHostedControlPlaneStore", "TeamCommand", "LocalMinosTeamApi")
        print("M27 TEAM HOSTED CONSISTENCY SUCCESS")
        return 0
    except Exception as exception:
        print(f"M27 TEAM HOSTED CONSISTENCY FAILED: {exception}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
