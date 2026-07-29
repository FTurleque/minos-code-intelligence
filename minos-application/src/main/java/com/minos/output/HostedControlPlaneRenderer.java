package com.minos.output;

import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedProjectBinding;
import com.minos.hosted.HostedRetentionPlan;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedTenantState;
import com.minos.hosted.SharedWorkspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic, secret-conscious JSON renderer for M27 hosted control-plane views. */
public final class HostedControlPlaneRenderer {
    private HostedControlPlaneRenderer() { }

    public static String renderBootstrap(HostedControlPlaneService.BootstrapResult value) {
        Map<String, Object> map = tenantMap(value.state());
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

    public static String renderRotation(HostedControlPlaneService.RotateKeyResult value) {
        Map<String, Object> map = tenantMap(value.state());
        map.put("replacementBearerToken", value.replacementBearerToken());
        map.put("tokenHandling", "SECRET_OUTPUT_ONCE_DO_NOT_LOG");
        return DeterministicJson.render(map);
    }

    public static String renderTenant(HostedTenantState value) {
        return DeterministicJson.render(tenantMap(value));
    }

    public static String renderWorkspaces(List<SharedWorkspace> values) {
        return DeterministicJson.render(Map.of(
                "isolation", "TENANT_SCOPED",
                "workspaces", values.stream().map(HostedControlPlaneRenderer::workspaceMap).toList()));
    }

    public static String renderWorkspace(SharedWorkspace value) {
        return DeterministicJson.render(workspaceMap(value));
    }

    public static String renderMembers(List<HostedPrincipal> values) {
        return DeterministicJson.render(Map.of(
                "isolation", "TENANT_SCOPED",
                "members", values.stream().map(HostedControlPlaneRenderer::memberMap).toList()));
    }

    public static String renderAudit(List<HostedAuditEvent> values) {
        return DeterministicJson.render(Map.of(
                "integrity", "HMAC_SHA256_CHAINED",
                "events", values.stream().map(HostedControlPlaneRenderer::auditMap).toList()));
    }

    public static String renderRetention(HostedRetentionPolicy policy, HostedRetentionPlan plan) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("policy", retentionMap(policy));
        map.put("plan", retentionPlanMap(plan));
        map.put("implicitDeletion", false);
        return DeterministicJson.render(map);
    }

    public static String renderRetentionApply(HostedControlPlaneService.RetentionApplyResult value) {
        Map<String, Object> map = retentionPlanMap(value.plan());
        map.put("newTenantVersion", value.state().version());
        map.put("remainingAuditEvents", value.state().auditEvents().size());
        map.put("remainingWorkspaces", value.state().workspaces().size());
        return DeterministicJson.render(map);
    }

    private static Map<String, Object> tenantMap(HostedTenantState value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", value.tenantId().toString());
        map.put("name", value.name());
        map.put("keyId", value.keyId());
        map.put("version", value.version());
        map.put("createdAt", value.createdAt().toString());
        map.put("updatedAt", value.updatedAt().toString());
        map.put("members", value.members().size());
        map.put("workspaces", value.workspaces().size());
        map.put("auditSequence", value.auditSequence());
        map.put("retention", retentionMap(value.retentionPolicy()));
        map.put("isolation", "TENANT_SCOPED");
        map.put("encryptionAtRest", "AES_256_GCM");
        return map;
    }

    private static Map<String, Object> memberMap(HostedPrincipal value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("principalId", value.principalId());
        map.put("displayName", value.displayName());
        map.put("role", value.role().name());
        map.put("permissions", value.role().permissions().stream().map(Enum::name).sorted().toList());
        map.put("createdAt", value.createdAt().toString());
        return map;
    }

    private static Map<String, Object> workspaceMap(SharedWorkspace value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("workspaceId", value.workspaceId().toString());
        map.put("tenantId", value.tenantId().toString());
        map.put("name", value.name());
        map.put("status", value.status().name());
        map.put("createdAt", value.createdAt().toString());
        map.put("updatedAt", value.updatedAt().toString());
        map.put("archivedAt", value.archivedAt() == null ? null : value.archivedAt().toString());
        map.put("bindings", value.bindings().stream().map(HostedControlPlaneRenderer::bindingMap).toList());
        return map;
    }

    private static Map<String, Object> bindingMap(HostedProjectBinding value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("projectId", value.projectId().toString());
        map.put("snapshotId", value.snapshotId());
        map.put("boundAt", value.boundAt().toString());
        map.put("boundBy", value.boundBy());
        return map;
    }

    private static Map<String, Object> auditMap(HostedAuditEvent value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sequence", value.sequence());
        map.put("tenantId", value.tenantId().toString());
        map.put("occurredAt", value.occurredAt().toString());
        map.put("principalId", value.principalId());
        map.put("action", value.action());
        map.put("resourceType", value.resourceType());
        map.put("resourceId", value.resourceId());
        map.put("outcome", value.outcome().name());
        map.put("requestId", value.requestId());
        map.put("keyId", value.keyId());
        map.put("previousHash", value.previousHash());
        map.put("hash", value.hash());
        return map;
    }

    private static Map<String, Object> retentionMap(HostedRetentionPolicy value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("maxAuditEvents", value.maxAuditEvents());
        map.put("auditRetentionDays", value.auditRetentionDays());
        map.put("archivedWorkspaceRetentionDays", value.archivedWorkspaceRetentionDays());
        return map;
    }

    private static Map<String, Object> retentionPlanMap(HostedRetentionPlan value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tenantId", value.tenantId().toString());
        map.put("evaluatedAt", value.evaluatedAt().toString());
        map.put("auditEventsToRemove", value.auditEventsToRemove());
        map.put("archivedWorkspacesToRemove", value.archivedWorkspacesToRemove().stream().map(UUID::toString).toList());
        return map;
    }
}
