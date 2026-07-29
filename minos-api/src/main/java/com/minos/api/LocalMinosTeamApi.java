package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedPrincipal;
import com.minos.hosted.HostedProjectBinding;
import com.minos.hosted.HostedRetentionPlan;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantState;
import com.minos.hosted.SharedWorkspace;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Direct Java mapping to the shared M27 control-plane service. */
public final class LocalMinosTeamApi implements MinosTeamApi {
    private static final String TOKEN_HANDLING = "SECRET_OUTPUT_ONCE_DO_NOT_LOG";
    private final Optional<HostedControlPlaneService> service;

    public LocalMinosTeamApi(MinosApplication application) {
        service = Objects.requireNonNull(application, "application").hostedControlPlaneService();
    }

    @Override public BootstrapDto bootstrap(BootstrapRequest request) throws MinosApi.MinosApiException {
        return execute(() -> {
            BootstrapRequest value = Objects.requireNonNull(request, "request");
            var result = hosted().bootstrap(value.tenantId(), value.tenantName(), value.keyId(), value.ownerPrincipalId(),
                    value.ownerDisplayName(), value.tokenLifetime(), value.requestId());
            return new BootstrapDto(tenantDto(result.state()), result.bearerToken(), TOKEN_HANDLING);
        });
    }
    @Override public TenantDto tenant(String token) throws MinosApi.MinosApiException {
        return execute(() -> tenantDto(hosted().tenant(token)));
    }
    @Override public List<WorkspaceDto> listWorkspaces(String token) throws MinosApi.MinosApiException {
        return execute(() -> hosted().listWorkspaces(token).stream().map(LocalMinosTeamApi::workspaceDto).toList());
    }
    @Override public WorkspaceDto getWorkspace(String token, UUID id) throws MinosApi.MinosApiException {
        return execute(() -> workspaceDto(hosted().workspace(token, id)));
    }
    @Override public WorkspaceDto createWorkspace(String token, String requestId, String name) throws MinosApi.MinosApiException {
        return execute(() -> workspaceDto(hosted().createWorkspace(token, requestId, name)));
    }
    @Override public WorkspaceDto archiveWorkspace(String token, String requestId, UUID id) throws MinosApi.MinosApiException {
        return execute(() -> workspaceDto(hosted().archiveWorkspace(token, requestId, id)));
    }
    @Override public List<MemberDto> listMembers(String token) throws MinosApi.MinosApiException {
        return execute(() -> hosted().listMembers(token).stream().map(LocalMinosTeamApi::memberDto).toList());
    }
    @Override public MemberDto grantMember(String token, String requestId, MemberGrantRequest request) throws MinosApi.MinosApiException {
        return execute(() -> {
            MemberGrantRequest value = Objects.requireNonNull(request, "request");
            return memberDto(hosted().grantMember(token, requestId, value.principalId(), value.displayName(), role(value.role())));
        });
    }
    @Override public void revokeMember(String token, String requestId, String principalId) throws MinosApi.MinosApiException {
        execute(() -> { hosted().revokeMember(token, requestId, principalId); return null; });
    }
    @Override public BindingDto bindProject(String token, String requestId, BindingRequest request) throws MinosApi.MinosApiException {
        return execute(() -> {
            BindingRequest value = Objects.requireNonNull(request, "request");
            return bindingDto(hosted().bindProject(token, requestId, value.workspaceId(), value.projectId(), value.snapshotId()));
        });
    }
    @Override public void unbindProject(String token, String requestId, UUID workspaceId, UUID projectId) throws MinosApi.MinosApiException {
        execute(() -> { hosted().unbindProject(token, requestId, workspaceId, projectId); return null; });
    }
    @Override public List<AuditEventDto> audit(String token, int limit) throws MinosApi.MinosApiException {
        return execute(() -> hosted().audit(token, limit).stream().map(LocalMinosTeamApi::auditDto).toList());
    }
    @Override public RetentionDto retentionPlan(String token) throws MinosApi.MinosApiException {
        return execute(() -> new RetentionDto(policyDto(hosted().tenant(token).retentionPolicy()),
                planDto(hosted().retentionPlan(token)), false));
    }
    @Override public RetentionDto setRetention(String token, String requestId, RetentionPolicyDto policy) throws MinosApi.MinosApiException {
        return execute(() -> {
            RetentionPolicyDto value = Objects.requireNonNull(policy, "policy");
            var saved = hosted().setRetention(token, requestId, new HostedRetentionPolicy(value.maxAuditEvents(),
                    value.auditRetentionDays(), value.archivedWorkspaceRetentionDays()));
            return new RetentionDto(policyDto(saved), planDto(hosted().retentionPlan(token)), false);
        });
    }
    @Override public RetentionApplyDto applyRetention(String token, String requestId) throws MinosApi.MinosApiException {
        return execute(() -> {
            var result = hosted().applyRetention(token, requestId);
            return new RetentionApplyDto(planDto(result.plan()), result.state().version(),
                    result.state().auditEvents().size(), result.state().workspaces().size());
        });
    }
    @Override public TokenDto issueToken(String token, String requestId, String principalId, Duration lifetime) throws MinosApi.MinosApiException {
        return execute(() -> new TokenDto(hosted().issueToken(token, requestId, principalId, lifetime), TOKEN_HANDLING));
    }
    @Override public RotationDto rotateKey(String token, String requestId, String keyId, Duration lifetime) throws MinosApi.MinosApiException {
        return execute(() -> {
            var result = hosted().rotateKey(token, requestId, keyId, lifetime);
            return new RotationDto(tenantDto(result.state()), result.replacementBearerToken(), TOKEN_HANDLING);
        });
    }

    private HostedControlPlaneService hosted() {
        return service.orElseThrow(() -> new IllegalStateException("MINOS team mode is disabled"));
    }
    private static HostedRole role(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("role must not be blank");
        try { return HostedRole.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException exception) { throw new IllegalArgumentException("unsupported hosted role: " + value); }
    }
    private static TenantDto tenantDto(HostedTenantState value) {
        return new TenantDto(value.tenantId(), value.name(), value.keyId(), value.version(), value.createdAt().toString(),
                value.updatedAt().toString(), value.members().size(), value.workspaces().size(), value.auditSequence(),
                policyDto(value.retentionPolicy()), "TENANT_SCOPED", "AES_256_GCM");
    }
    private static WorkspaceDto workspaceDto(SharedWorkspace value) {
        return new WorkspaceDto(value.workspaceId(), value.tenantId(), value.name(), value.status().name(),
                value.createdAt().toString(), value.updatedAt().toString(),
                value.archivedAt() == null ? null : value.archivedAt().toString(),
                value.bindings().stream().map(LocalMinosTeamApi::bindingDto).toList());
    }
    private static BindingDto bindingDto(HostedProjectBinding value) {
        return new BindingDto(value.projectId(), value.snapshotId(), value.boundAt().toString(), value.boundBy());
    }
    private static MemberDto memberDto(HostedPrincipal value) {
        return new MemberDto(value.principalId(), value.displayName(), value.role().name(),
                value.role().permissions().stream().map(Enum::name).sorted().toList(), value.createdAt().toString());
    }
    private static AuditEventDto auditDto(HostedAuditEvent value) {
        return new AuditEventDto(value.sequence(), value.tenantId(), value.occurredAt().toString(), value.principalId(),
                value.action(), value.resourceType(), value.resourceId(), value.outcome().name(), value.requestId(),
                value.keyId(), value.previousHash(), value.hash());
    }
    private static RetentionPolicyDto policyDto(HostedRetentionPolicy value) {
        return new RetentionPolicyDto(value.maxAuditEvents(), value.auditRetentionDays(), value.archivedWorkspaceRetentionDays());
    }
    private static RetentionPlanDto planDto(HostedRetentionPlan value) {
        return new RetentionPlanDto(value.tenantId(), value.evaluatedAt().toString(), value.auditEventsToRemove(),
                value.archivedWorkspacesToRemove());
    }
    private static <T> T execute(Call<T> call) throws MinosApi.MinosApiException {
        try { return call.run(); }
        catch (SecurityException exception) { throw failure(MinosApi.ErrorCode.UNAVAILABLE, exception); }
        catch (IllegalArgumentException exception) { throw failure(MinosApi.ErrorCode.INVALID_REQUEST, exception); }
        catch (IllegalStateException exception) { throw failure(MinosApi.ErrorCode.UNAVAILABLE, exception); }
        catch (IOException exception) { throw failure(MinosApi.ErrorCode.IO_FAILURE, exception); }
        catch (Exception exception) { throw failure(MinosApi.ErrorCode.EXECUTION_FAILURE, exception); }
    }
    private static MinosApi.MinosApiException failure(MinosApi.ErrorCode code, Exception exception) {
        String message = exception.getMessage();
        return new MinosApi.MinosApiException(code, message == null || message.isBlank()
                ? exception.getClass().getSimpleName() : message.replace('\r', ' ').replace('\n', ' '), exception);
    }
    @FunctionalInterface private interface Call<T> { T run() throws Exception; }
}
