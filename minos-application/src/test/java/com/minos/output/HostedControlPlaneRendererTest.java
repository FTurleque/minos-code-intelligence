package com.minos.output;

import com.minos.application.team.TeamAuditEvent;
import com.minos.application.team.TeamBinding;
import com.minos.application.team.TeamBootstrap;
import com.minos.application.team.TeamKeyRotation;
import com.minos.application.team.TeamMember;
import com.minos.application.team.TeamRetentionApply;
import com.minos.application.team.TeamRetentionPlan;
import com.minos.application.team.TeamRetentionPolicy;
import com.minos.application.team.TeamRole;
import com.minos.application.team.TeamTenant;
import com.minos.application.team.TeamWorkspace;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Every team view renders the application models with its fixed keys, in their declared order. */
class HostedControlPlaneRendererTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID WORKSPACE = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = Instant.parse("2026-01-02T00:00:00Z");
    private static final TeamRetentionPolicy POLICY = new TeamRetentionPolicy(200, 30, 7);
    private static final TeamTenant TENANT_VIEW = new TeamTenant(TENANT, "Acme", "key-a", 3, T0, T1, 2, 1, 5, POLICY);
    private static final String TENANT_JSON = "\"tenantId\":\"" + TENANT + "\",\"name\":\"Acme\",\"keyId\":\"key-a\","
            + "\"version\":3,\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-02T00:00:00Z\","
            + "\"members\":2,\"workspaces\":1,\"auditSequence\":5,"
            + "\"retention\":{\"maxAuditEvents\":200,\"auditRetentionDays\":30,\"archivedWorkspaceRetentionDays\":7},"
            + "\"isolation\":\"TENANT_SCOPED\",\"encryptionAtRest\":\"AES_256_GCM\"";
    private static final TeamRetentionPlan PLAN = new TeamRetentionPlan(TENANT, T1, 4, List.of(WORKSPACE));
    private static final String PLAN_JSON = "\"tenantId\":\"" + TENANT + "\",\"evaluatedAt\":\"2026-01-02T00:00:00Z\","
            + "\"auditEventsToRemove\":4,\"archivedWorkspacesToRemove\":[\"" + WORKSPACE + "\"]";

    @Test
    void rendersTheTenantAndTheSecretOutputs() {
        assertEquals("{" + TENANT_JSON + "}", HostedControlPlaneRenderer.renderTenant(TENANT_VIEW));
        assertEquals("{" + TENANT_JSON + ",\"bearerToken\":\"token-1\",\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\"}",
                HostedControlPlaneRenderer.renderBootstrap(new TeamBootstrap(TENANT_VIEW, "token-1")));
        assertEquals("{" + TENANT_JSON + ",\"replacementBearerToken\":\"token-2\","
                        + "\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\"}",
                HostedControlPlaneRenderer.renderRotation(new TeamKeyRotation(TENANT_VIEW, "token-2")));
        assertEquals("{\"bearerToken\":\"token-3\",\"tokenHandling\":\"SECRET_OUTPUT_ONCE_DO_NOT_LOG\"}",
                HostedControlPlaneRenderer.renderToken("token-3"));
    }

    @Test
    void rendersWorkspacesWithTheirBindings() {
        TeamWorkspace active = new TeamWorkspace(WORKSPACE, TENANT, "Platform", "ACTIVE", T0, T1, null,
                List.of(new TeamBinding(PROJECT, "snap-1", T1, "alice")));
        String json = "{\"workspaceId\":\"" + WORKSPACE + "\",\"tenantId\":\"" + TENANT + "\",\"name\":\"Platform\","
                + "\"status\":\"ACTIVE\",\"createdAt\":\"2026-01-01T00:00:00Z\",\"updatedAt\":\"2026-01-02T00:00:00Z\","
                + "\"archivedAt\":null,\"bindings\":[{\"projectId\":\"" + PROJECT + "\",\"snapshotId\":\"snap-1\","
                + "\"boundAt\":\"2026-01-02T00:00:00Z\",\"boundBy\":\"alice\"}]}";
        assertEquals(json, HostedControlPlaneRenderer.renderWorkspace(active));
        assertEquals("{\"isolation\":\"TENANT_SCOPED\",\"workspaces\":[" + json + "]}",
                HostedControlPlaneRenderer.renderWorkspaces(List.of(active)));
        TeamWorkspace archived = new TeamWorkspace(WORKSPACE, TENANT, "Platform", "ARCHIVED", T0, T1, T1, List.of());
        assertEquals("{\"workspaceId\":\"" + WORKSPACE + "\",\"tenantId\":\"" + TENANT + "\",\"name\":\"Platform\","
                        + "\"status\":\"ARCHIVED\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                        + "\"updatedAt\":\"2026-01-02T00:00:00Z\",\"archivedAt\":\"2026-01-02T00:00:00Z\",\"bindings\":[]}",
                HostedControlPlaneRenderer.renderWorkspace(archived));
    }

    @Test
    void rendersMembersAndAuditEvents() {
        TeamMember member = new TeamMember("bob", "Bob", TeamRole.VIEWER, List.of("MEMBER_READ", "WORKSPACE_READ"), T0);
        assertEquals("{\"isolation\":\"TENANT_SCOPED\",\"members\":[{\"principalId\":\"bob\",\"displayName\":\"Bob\","
                        + "\"role\":\"VIEWER\",\"permissions\":[\"MEMBER_READ\",\"WORKSPACE_READ\"],"
                        + "\"createdAt\":\"2026-01-01T00:00:00Z\"}]}",
                HostedControlPlaneRenderer.renderMembers(List.of(member)));
        String previous = "0".repeat(64);
        String hash = "a".repeat(64);
        TeamAuditEvent event = new TeamAuditEvent(1, TENANT, T0, "alice", "WORKSPACE_CREATE", "workspace",
                WORKSPACE.toString(), "ALLOWED", "req-1", "key-a", previous, hash);
        assertEquals("{\"integrity\":\"HMAC_SHA256_CHAINED\",\"events\":[{\"sequence\":1,\"tenantId\":\"" + TENANT
                        + "\",\"occurredAt\":\"2026-01-01T00:00:00Z\",\"principalId\":\"alice\","
                        + "\"action\":\"WORKSPACE_CREATE\",\"resourceType\":\"workspace\",\"resourceId\":\"" + WORKSPACE
                        + "\",\"outcome\":\"ALLOWED\",\"requestId\":\"req-1\",\"keyId\":\"key-a\","
                        + "\"previousHash\":\"" + previous + "\",\"hash\":\"" + hash + "\"}]}",
                HostedControlPlaneRenderer.renderAudit(List.of(event)));
    }

    @Test
    void rendersTheRetentionPlanAndItsApplication() {
        assertEquals("{\"policy\":{\"maxAuditEvents\":200,\"auditRetentionDays\":30,"
                        + "\"archivedWorkspaceRetentionDays\":7},\"plan\":{" + PLAN_JSON + "},\"implicitDeletion\":false}",
                HostedControlPlaneRenderer.renderRetention(POLICY, PLAN));
        assertEquals("{" + PLAN_JSON + ",\"newTenantVersion\":6,\"remainingAuditEvents\":9,\"remainingWorkspaces\":0}",
                HostedControlPlaneRenderer.renderRetentionApply(new TeamRetentionApply(PLAN, 6, 9, 0)));
    }
}
