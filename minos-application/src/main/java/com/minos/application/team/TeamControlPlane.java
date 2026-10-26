package com.minos.application.team;

import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedRole;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Team control plane as the surfaces consume it. Every operation delegates to {@link HostedControlPlaneService},
 * which authenticates, authorizes, audits and persists; this facade only converts its inputs and projects its
 * results into application models. It adds no check, catches nothing and logs nothing: every exception of the
 * service reaches the caller unchanged, and a missing input reaches the service, which authorizes before it
 * validates.
 */
public final class TeamControlPlane {
    /** Smallest number of audit events {@link #audit} returns. */
    public static final int MIN_AUDIT_LIMIT = HostedControlPlaneService.MIN_AUDIT_LIMIT;
    /** Largest number of audit events {@link #audit} returns; surfaces validate against it before calling. */
    public static final int MAX_AUDIT_LIMIT = HostedControlPlaneService.MAX_AUDIT_LIMIT;

    private final HostedControlPlaneService service;

    public TeamControlPlane(HostedControlPlaneService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    public TeamBootstrap bootstrap(UUID tenantId, String tenantName, String keyId, String ownerPrincipalId,
                                   String ownerDisplayName, Duration tokenLifetime, String requestId)
            throws IOException {
        HostedControlPlaneService.BootstrapResult result = service.bootstrap(tenantId, tenantName, keyId,
                ownerPrincipalId, ownerDisplayName, tokenLifetime, requestId);
        return new TeamBootstrap(TeamTenant.of(result.state()), result.bearerToken());
    }

    public TeamTenant tenant(String bearerToken) throws IOException {
        return TeamTenant.of(service.tenant(bearerToken));
    }

    public List<TeamWorkspace> listWorkspaces(String bearerToken) throws IOException {
        return service.listWorkspaces(bearerToken).stream().map(TeamWorkspace::of).toList();
    }

    public TeamWorkspace workspace(String bearerToken, UUID workspaceId) throws IOException {
        return TeamWorkspace.of(service.workspace(bearerToken, workspaceId));
    }

    public List<TeamMember> listMembers(String bearerToken) throws IOException {
        return service.listMembers(bearerToken).stream().map(TeamMember::of).toList();
    }

    public List<TeamAuditEvent> audit(String bearerToken, int limit) throws IOException {
        return service.audit(bearerToken, limit).stream().map(TeamAuditEvent::of).toList();
    }

    public TeamWorkspace createWorkspace(String bearerToken, String requestId, String name) throws IOException {
        return TeamWorkspace.of(service.createWorkspace(bearerToken, requestId, name));
    }

    public TeamWorkspace archiveWorkspace(String bearerToken, String requestId, UUID workspaceId) throws IOException {
        return TeamWorkspace.of(service.archiveWorkspace(bearerToken, requestId, workspaceId));
    }

    public TeamMember grantMember(String bearerToken, String requestId, String principalId, String displayName,
                                  TeamRole role) throws IOException {
        HostedRole hosted = role == null ? null : HostedRole.valueOf(role.name());
        return TeamMember.of(service.grantMember(bearerToken, requestId, principalId, displayName, hosted));
    }

    public void revokeMember(String bearerToken, String requestId, String principalId) throws IOException {
        service.revokeMember(bearerToken, requestId, principalId);
    }

    public TeamBinding bindProject(String bearerToken, String requestId, UUID workspaceId, UUID projectId,
                                   String snapshotId) throws IOException {
        return TeamBinding.of(service.bindProject(bearerToken, requestId, workspaceId, projectId, snapshotId));
    }

    public void unbindProject(String bearerToken, String requestId, UUID workspaceId, UUID projectId)
            throws IOException {
        service.unbindProject(bearerToken, requestId, workspaceId, projectId);
    }

    public TeamRetentionPolicy setRetention(String bearerToken, String requestId, TeamRetentionPolicy policy)
            throws IOException {
        return TeamRetentionPolicy.of(service.setRetention(bearerToken, requestId,
                policy == null ? null : policy.toHosted()));
    }

    public TeamRetentionPlan retentionPlan(String bearerToken) throws IOException {
        return TeamRetentionPlan.of(service.retentionPlan(bearerToken));
    }

    public TeamRetentionApply applyRetention(String bearerToken, String requestId) throws IOException {
        HostedControlPlaneService.RetentionApplyResult result = service.applyRetention(bearerToken, requestId);
        return new TeamRetentionApply(TeamRetentionPlan.of(result.plan()), result.state().version(),
                result.state().auditEvents().size(), result.state().workspaces().size());
    }

    public String issueToken(String bearerToken, String requestId, String targetPrincipalId, Duration lifetime)
            throws IOException {
        return service.issueToken(bearerToken, requestId, targetPrincipalId, lifetime);
    }

    public TeamKeyRotation rotateKey(String bearerToken, String requestId, String newKeyId,
                                     Duration replacementTokenLifetime) throws IOException {
        HostedControlPlaneService.RotateKeyResult result = service.rotateKey(bearerToken, requestId, newKeyId,
                replacementTokenLifetime);
        return new TeamKeyRotation(TeamTenant.of(result.state()), result.replacementBearerToken());
    }
}
