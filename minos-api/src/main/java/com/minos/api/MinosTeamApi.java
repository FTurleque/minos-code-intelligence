package com.minos.api;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** JDK-only M27 contract for the opt-in tenant control plane. */
public interface MinosTeamApi {
    BootstrapDto bootstrap(BootstrapRequest request) throws MinosApi.MinosApiException;
    TenantDto tenant(String bearerToken) throws MinosApi.MinosApiException;
    List<WorkspaceDto> listWorkspaces(String bearerToken) throws MinosApi.MinosApiException;
    WorkspaceDto getWorkspace(String bearerToken, UUID workspaceId) throws MinosApi.MinosApiException;
    WorkspaceDto createWorkspace(String bearerToken, String requestId, String name) throws MinosApi.MinosApiException;
    WorkspaceDto archiveWorkspace(String bearerToken, String requestId, UUID workspaceId) throws MinosApi.MinosApiException;
    List<MemberDto> listMembers(String bearerToken) throws MinosApi.MinosApiException;
    MemberDto grantMember(String bearerToken, String requestId, MemberGrantRequest request) throws MinosApi.MinosApiException;
    void revokeMember(String bearerToken, String requestId, String principalId) throws MinosApi.MinosApiException;
    BindingDto bindProject(String bearerToken, String requestId, BindingRequest request) throws MinosApi.MinosApiException;
    void unbindProject(String bearerToken, String requestId, UUID workspaceId, UUID projectId) throws MinosApi.MinosApiException;
    List<AuditEventDto> audit(String bearerToken, int limit) throws MinosApi.MinosApiException;
    RetentionDto retentionPlan(String bearerToken) throws MinosApi.MinosApiException;
    RetentionDto setRetention(String bearerToken, String requestId, RetentionPolicyDto policy) throws MinosApi.MinosApiException;
    RetentionApplyDto applyRetention(String bearerToken, String requestId) throws MinosApi.MinosApiException;
    TokenDto issueToken(String bearerToken, String requestId, String principalId, Duration lifetime) throws MinosApi.MinosApiException;
    RotationDto rotateKey(String bearerToken, String requestId, String newKeyId, Duration lifetime) throws MinosApi.MinosApiException;

    record BootstrapRequest(UUID tenantId, String tenantName, String keyId, String ownerPrincipalId,
                            String ownerDisplayName, Duration tokenLifetime, String requestId) { }
    record MemberGrantRequest(String principalId, String displayName, String role) { }
    record BindingRequest(UUID workspaceId, UUID projectId, String snapshotId) { }
    record BootstrapDto(TenantDto tenant, String bearerToken, String tokenHandling) { }
    record TokenDto(String bearerToken, String tokenHandling) { }
    record RotationDto(TenantDto tenant, String replacementBearerToken, String tokenHandling) { }
    record TenantDto(UUID tenantId, String name, String keyId, long version, String createdAt, String updatedAt,
                     int memberCount, int workspaceCount, long auditSequence, RetentionPolicyDto retention,
                     String isolation, String encryptionAtRest) { }
    record WorkspaceDto(UUID workspaceId, UUID tenantId, String name, String status, String createdAt,
                        String updatedAt, String archivedAt, List<BindingDto> bindings) {
        public WorkspaceDto { bindings = bindings == null ? List.of() : List.copyOf(bindings); }
    }
    record BindingDto(UUID projectId, String snapshotId, String boundAt, String boundBy) { }
    record MemberDto(String principalId, String displayName, String role, List<String> permissions, String createdAt) {
        public MemberDto { permissions = permissions == null ? List.of() : List.copyOf(permissions); }
    }
    record AuditEventDto(long sequence, UUID tenantId, String occurredAt, String principalId, String action,
                         String resourceType, String resourceId, String outcome, String requestId, String keyId,
                         String previousHash, String hash) { }
    record RetentionPolicyDto(int maxAuditEvents, int auditRetentionDays, int archivedWorkspaceRetentionDays) { }
    record RetentionPlanDto(UUID tenantId, String evaluatedAt, int auditEventsToRemove,
                            List<UUID> archivedWorkspacesToRemove) {
        public RetentionPlanDto {
            archivedWorkspacesToRemove = archivedWorkspacesToRemove == null
                    ? List.of() : List.copyOf(archivedWorkspacesToRemove);
        }
    }
    record RetentionDto(RetentionPolicyDto policy, RetentionPlanDto plan, boolean implicitDeletion) { }
    record RetentionApplyDto(RetentionPlanDto plan, long newTenantVersion, int remainingAuditEvents,
                             int remainingWorkspaces) { }
}
