package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.application.team.TeamAuditEvent;
import com.minos.application.team.TeamBinding;
import com.minos.application.team.TeamControlPlane;
import com.minos.application.team.TeamMember;
import com.minos.application.team.TeamRetentionPlan;
import com.minos.application.team.TeamRetentionPolicy;
import com.minos.application.team.TeamRole;
import com.minos.application.team.TeamTenant;
import com.minos.application.team.TeamWorkspace;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Direct Java mapping to the team control plane of the application. */
public final class LocalMinosTeamApi implements MinosTeamApi {
    private static final String TOKEN_HANDLING = "SECRET_OUTPUT_ONCE_DO_NOT_LOG";
    private static final String REQUEST = "request";
    private final Optional<TeamControlPlane> service;

    public LocalMinosTeamApi(MinosApplication application) {
        service = Objects.requireNonNull(application, "application").teamControlPlane();
    }

    @Override public BootstrapDto bootstrap(BootstrapRequest request) throws MinosApi.MinosApiException {
        return execute(() -> {
            BootstrapRequest value = MinosApiSupport.required(request, REQUEST);
            var result = teamControlPlane().bootstrap(value.tenantId(), value.tenantName(), value.keyId(), value.ownerPrincipalId(),
                    value.ownerDisplayName(), value.tokenLifetime(), value.requestId());
            return new BootstrapDto(tenantDto(result.tenant()), result.bearerToken(), TOKEN_HANDLING);
        });
    }
    @Override public TenantDto tenant(String token) throws MinosApi.MinosApiException {
        return execute(() -> tenantDto(teamControlPlane().tenant(token)));
    }
    @Override public List<WorkspaceDto> listWorkspaces(String token) throws MinosApi.MinosApiException {
        return execute(() -> teamControlPlane().listWorkspaces(token).stream().map(LocalMinosTeamApi::workspaceDto).toList());
    }
    @Override public WorkspaceDto getWorkspace(String token, UUID id) throws MinosApi.MinosApiException {
        return execute(() -> workspaceDto(teamControlPlane().workspace(token, id)));
    }
    @Override public WorkspaceDto createWorkspace(String token, String requestId, String name) throws MinosApi.MinosApiException {
        return execute(() -> workspaceDto(teamControlPlane().createWorkspace(token, requestId, name)));
    }
    @Override public WorkspaceDto archiveWorkspace(String token, String requestId, UUID id) throws MinosApi.MinosApiException {
        return execute(() -> workspaceDto(teamControlPlane().archiveWorkspace(token, requestId, id)));
    }
    @Override public List<MemberDto> listMembers(String token) throws MinosApi.MinosApiException {
        return execute(() -> teamControlPlane().listMembers(token).stream().map(LocalMinosTeamApi::memberDto).toList());
    }
    @Override public MemberDto grantMember(String token, String requestId, MemberGrantRequest request) throws MinosApi.MinosApiException {
        return execute(() -> {
            MemberGrantRequest value = MinosApiSupport.required(request, REQUEST);
            return memberDto(teamControlPlane().grantMember(token, requestId, value.principalId(), value.displayName(),
                    role(value.role())));
        });
    }
    @Override public void revokeMember(String token, String requestId, String principalId) throws MinosApi.MinosApiException {
        execute(() -> { teamControlPlane().revokeMember(token, requestId, principalId); return null; });
    }
    @Override public BindingDto bindProject(String token, String requestId, BindingRequest request) throws MinosApi.MinosApiException {
        return execute(() -> {
            BindingRequest value = MinosApiSupport.required(request, REQUEST);
            return bindingDto(teamControlPlane().bindProject(token, requestId, value.workspaceId(), value.projectId(), value.snapshotId()));
        });
    }
    @Override public void unbindProject(String token, String requestId, UUID workspaceId, UUID projectId) throws MinosApi.MinosApiException {
        execute(() -> { teamControlPlane().unbindProject(token, requestId, workspaceId, projectId); return null; });
    }
    @Override public List<AuditEventDto> audit(String token, int limit) throws MinosApi.MinosApiException {
        return execute(() -> teamControlPlane().audit(token, limit).stream().map(LocalMinosTeamApi::auditDto).toList());
    }
    @Override public RetentionDto retentionPlan(String token) throws MinosApi.MinosApiException {
        return execute(() -> new RetentionDto(policyDto(teamControlPlane().tenant(token).retentionPolicy()),
                planDto(teamControlPlane().retentionPlan(token)), false));
    }
    @Override public RetentionDto setRetention(String token, String requestId, RetentionPolicyDto policy) throws MinosApi.MinosApiException {
        return execute(() -> {
            RetentionPolicyDto value = MinosApiSupport.required(policy, "policy");
            var saved = teamControlPlane().setRetention(token, requestId, new TeamRetentionPolicy(value.maxAuditEvents(),
                    value.auditRetentionDays(), value.archivedWorkspaceRetentionDays()));
            return new RetentionDto(policyDto(saved), planDto(teamControlPlane().retentionPlan(token)), false);
        });
    }
    @Override public RetentionApplyDto applyRetention(String token, String requestId) throws MinosApi.MinosApiException {
        return execute(() -> {
            var result = teamControlPlane().applyRetention(token, requestId);
            return new RetentionApplyDto(planDto(result.plan()), result.tenantVersion(),
                    result.remainingAuditEvents(), result.remainingWorkspaces());
        });
    }
    @Override public TokenDto issueToken(String token, String requestId, String principalId, Duration lifetime) throws MinosApi.MinosApiException {
        return execute(() -> new TokenDto(teamControlPlane().issueToken(token, requestId, principalId, lifetime), TOKEN_HANDLING));
    }
    @Override public RotationDto rotateKey(String token, String requestId, String keyId, Duration lifetime) throws MinosApi.MinosApiException {
        return execute(() -> {
            var result = teamControlPlane().rotateKey(token, requestId, keyId, lifetime);
            return new RotationDto(tenantDto(result.tenant()), result.replacementBearerToken(), TOKEN_HANDLING);
        });
    }

    private TeamControlPlane teamControlPlane() {
        return service.orElseThrow(() -> new IllegalStateException("MINOS team mode is disabled"));
    }
    private static TeamRole role(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("role must not be blank");
        try { return TeamRole.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unsupported hosted role: " + value, exception); }
    }
    private static TenantDto tenantDto(TeamTenant value) {
        return new TenantDto(value.tenantId(), value.name(), value.keyId(), value.version(), value.createdAt().toString(),
                value.updatedAt().toString(), value.memberCount(), value.workspaceCount(), value.auditSequence(),
                policyDto(value.retentionPolicy()), "TENANT_SCOPED", "AES_256_GCM");
    }
    private static WorkspaceDto workspaceDto(TeamWorkspace value) {
        return new WorkspaceDto(value.workspaceId(), value.tenantId(), value.name(), value.status(),
                value.createdAt().toString(), value.updatedAt().toString(),
                value.archivedAt() == null ? null : value.archivedAt().toString(),
                value.bindings().stream().map(LocalMinosTeamApi::bindingDto).toList());
    }
    private static BindingDto bindingDto(TeamBinding value) {
        return new BindingDto(value.projectId(), value.snapshotId(), value.boundAt().toString(), value.boundBy());
    }
    private static MemberDto memberDto(TeamMember value) {
        return new MemberDto(value.principalId(), value.displayName(), value.role().name(),
                value.permissions(), value.createdAt().toString());
    }
    private static AuditEventDto auditDto(TeamAuditEvent value) {
        return new AuditEventDto(value.sequence(), value.tenantId(), value.occurredAt().toString(), value.principalId(),
                value.action(), value.resourceType(), value.resourceId(), value.outcome(), value.requestId(),
                value.keyId(), value.previousHash(), value.hash());
    }
    private static RetentionPolicyDto policyDto(TeamRetentionPolicy value) {
        return new RetentionPolicyDto(value.maxAuditEvents(), value.auditRetentionDays(), value.archivedWorkspaceRetentionDays());
    }
    private static RetentionPlanDto planDto(TeamRetentionPlan value) {
        return new RetentionPlanDto(value.tenantId(), value.evaluatedAt().toString(), value.auditEventsToRemove(),
                value.archivedWorkspacesToRemove());
    }
    private static <T> T execute(MinosApiSupport.ApiCall<T> call) throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(call);
    }
}
