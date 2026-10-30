package com.minos.output;

import com.minos.application.team.TeamAuditEvent;
import com.minos.application.team.TeamBinding;
import com.minos.application.team.TeamBootstrap;
import com.minos.application.team.TeamKeyRotation;
import com.minos.application.team.TeamMember;
import com.minos.application.team.TeamRetentionApply;
import com.minos.application.team.TeamRetentionPlan;
import com.minos.application.team.TeamRetentionPolicy;
import com.minos.application.team.TeamTenant;
import com.minos.application.team.TeamWorkspace;
import com.minos.output.json.DeterministicJson;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic, secret-conscious JSON renderer for the team control-plane views. */
public final class HostedControlPlaneRenderer {
    private HostedControlPlaneRenderer() { }

    public static String renderBootstrap(TeamBootstrap value) {
        Map<String, Object> map = tenantMap(value.tenant());
        map.put("bearerToken", value.bearerToken());
        map.put("tokenHandling", "SECRET_OUTPUT_ONCE_DO_NOT_LOG");
        return DeterministicJson.render(map);
    }

    public static String renderToken(String token) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("bearerToken", token);
        map.put("tokenHandling", "SECRET_OUTPUT_ONCE_DO_NOT_LOG");
        return DeterministicJson.render(map);
    }

    public static String renderRotation(TeamKeyRotation value) {
        Map<String, Object> map = tenantMap(value.tenant());
        map.put("replacementBearerToken", value.replacementBearerToken());
        map.put("tokenHandling", "SECRET_OUTPUT_ONCE_DO_NOT_LOG");
        return DeterministicJson.render(map);
    }

    public static String renderTenant(TeamTenant value) {
        return DeterministicJson.render(tenantMap(value));
    }

    public static String renderWorkspaces(List<TeamWorkspace> values) {
        return DeterministicJson.render(DeterministicJson.object(
                "isolation", "TENANT_SCOPED",
                "workspaces", values.stream().map(HostedControlPlaneRenderer::workspaceMap).toList()));
    }

    public static String renderWorkspace(TeamWorkspace value) {
        return DeterministicJson.render(workspaceMap(value));
    }

    public static String renderMembers(List<TeamMember> values) {
        return DeterministicJson.render(DeterministicJson.object(
                "isolation", "TENANT_SCOPED",
                "members", values.stream().map(HostedControlPlaneRenderer::memberMap).toList()));
    }

    public static String renderAudit(List<TeamAuditEvent> values) {
        return DeterministicJson.render(DeterministicJson.object(
                "integrity", "HMAC_SHA256_CHAINED",
                "events", values.stream().map(HostedControlPlaneRenderer::auditMap).toList()));
    }

    public static String renderRetention(TeamRetentionPolicy policy, TeamRetentionPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("policy", retentionMap(policy));
        map.put("plan", retentionPlanMap(plan));
        map.put("implicitDeletion", false);
        return DeterministicJson.render(map);
    }

    public static String renderRetentionApply(TeamRetentionApply value) {
        Map<String, Object> map = retentionPlanMap(value.plan());
        map.put("newTenantVersion", value.tenantVersion());
        map.put("remainingAuditEvents", value.remainingAuditEvents());
        map.put("remainingWorkspaces", value.remainingWorkspaces());
        return DeterministicJson.render(map);
    }

    private static Map<String, Object> tenantMap(TeamTenant value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", value.tenantId().toString());
        map.put("name", value.name());
        map.put("keyId", value.keyId());
        map.put("version", value.version());
        map.put("createdAt", value.createdAt().toString());
        map.put("updatedAt", value.updatedAt().toString());
        map.put("members", value.memberCount());
        map.put("workspaces", value.workspaceCount());
        map.put("auditSequence", value.auditSequence());
        map.put("retention", retentionMap(value.retentionPolicy()));
        map.put("isolation", "TENANT_SCOPED");
        map.put("encryptionAtRest", "AES_256_GCM");
        return map;
    }

    private static Map<String, Object> memberMap(TeamMember value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("principalId", value.principalId());
        map.put("displayName", value.displayName());
        map.put("role", value.role().name());
        map.put("permissions", value.permissions());
        map.put("createdAt", value.createdAt().toString());
        return map;
    }

    private static Map<String, Object> workspaceMap(TeamWorkspace value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", value.workspaceId().toString());
        map.put("tenantId", value.tenantId().toString());
        map.put("name", value.name());
        map.put("status", value.status());
        map.put("createdAt", value.createdAt().toString());
        map.put("updatedAt", value.updatedAt().toString());
        map.put("archivedAt", value.archivedAt() == null ? null : value.archivedAt().toString());
        map.put("bindings", value.bindings().stream().map(HostedControlPlaneRenderer::bindingMap).toList());
        return map;
    }

    private static Map<String, Object> bindingMap(TeamBinding value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", value.projectId().toString());
        map.put("snapshotId", value.snapshotId());
        map.put("boundAt", value.boundAt().toString());
        map.put("boundBy", value.boundBy());
        return map;
    }

    private static Map<String, Object> auditMap(TeamAuditEvent value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sequence", value.sequence());
        map.put("tenantId", value.tenantId().toString());
        map.put("occurredAt", value.occurredAt().toString());
        map.put("principalId", value.principalId());
        map.put("action", value.action());
        map.put("resourceType", value.resourceType());
        map.put("resourceId", value.resourceId());
        map.put("outcome", value.outcome());
        map.put("requestId", value.requestId());
        map.put("keyId", value.keyId());
        map.put("previousHash", value.previousHash());
        map.put("hash", value.hash());
        return map;
    }

    private static Map<String, Object> retentionMap(TeamRetentionPolicy value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxAuditEvents", value.maxAuditEvents());
        map.put("auditRetentionDays", value.auditRetentionDays());
        map.put("archivedWorkspaceRetentionDays", value.archivedWorkspaceRetentionDays());
        return map;
    }

    private static Map<String, Object> retentionPlanMap(TeamRetentionPlan value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", value.tenantId().toString());
        map.put("evaluatedAt", value.evaluatedAt().toString());
        map.put("auditEventsToRemove", value.auditEventsToRemove());
        map.put("archivedWorkspacesToRemove", value.archivedWorkspacesToRemove().stream().map(UUID::toString).toList());
        return map;
    }
}
