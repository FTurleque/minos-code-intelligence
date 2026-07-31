package com.minos.hosted;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable embedded hosted-control-plane facade.
 *
 * <p>M28 decomposes tenant bootstrap/read, authorization, membership, workspaces/bindings,
 * retention, tokens/key rotation, audit-chain integrity and mutation persistence into focused
 * services. This facade intentionally exposes no network listener and makes no operated SaaS claim.</p>
 */
public final class HostedControlPlaneService {

    private final HostedTenantService tenants;
    private final HostedWorkspaceService workspaces;
    private final HostedMembershipService memberships;
    private final HostedRetentionService retention;
    private final HostedTokenService tokens;
    private final HostedProductionBoundary productionBoundary;

    public HostedControlPlaneService(
            HostedControlPlaneStore store,
            HmacHostedIdentityProvider identities,
            HostedTenantKeyProvider keys,
            HostedBindingVerifier bindingVerifier,
            Clock clock
    ) {
        this(
                store,
                identities,
                keys,
                bindingVerifier,
                HostedAuditSink.embeddedNoop(),
                clock,
                false);
    }

    public HostedControlPlaneService(
            HostedControlPlaneStore store,
            HostedIdentityProvider identities,
            HostedTenantKeyProvider keys,
            HostedBindingVerifier bindingVerifier,
            HostedAuditSink auditSink,
            Clock clock
    ) {
        this(store, identities, keys, bindingVerifier, auditSink, clock, true);
    }

    private HostedControlPlaneService(
            HostedControlPlaneStore store,
            HostedIdentityProvider identities,
            HostedTenantKeyProvider keys,
            HostedBindingVerifier bindingVerifier,
            HostedAuditSink auditSink,
            Clock clock,
            boolean externalAuditSink
    ) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(identities, "identities");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(bindingVerifier, "bindingVerifier");
        Objects.requireNonNull(auditSink, "auditSink");
        Objects.requireNonNull(clock, "clock");

        HostedAuditChain auditChain = new HostedAuditChain(keys, clock);
        HostedAuthorizationService authorization = new HostedAuthorizationService(
                store, identities, auditChain, auditSink, clock);
        HostedTenantMutationWriter writer = new HostedTenantMutationWriter(
                store, auditChain, auditSink, clock);
        this.tokens = new HostedTokenService(authorization, identities, keys, writer, clock);
        this.tenants = new HostedTenantService(
                store, identities, authorization, auditChain, auditSink, tokens, clock);
        this.workspaces = new HostedWorkspaceService(authorization, bindingVerifier, writer, clock);
        this.memberships = new HostedMembershipService(authorization, writer, clock);
        this.retention = new HostedRetentionService(authorization, writer, clock);
        this.productionBoundary = HostedProductionBoundary.embeddedLocalFirst(externalAuditSink);
    }

    public HostedProductionBoundary productionBoundary() {
        return productionBoundary;
    }

    public BootstrapResult bootstrap(
            UUID tenantId,
            String tenantName,
            String keyId,
            String ownerPrincipalId,
            String ownerDisplayName,
            Duration tokenLifetime,
            String requestId
    ) throws IOException {
        HostedTenantService.Bootstrap result = tenants.bootstrap(
                tenantId, tenantName, keyId, ownerPrincipalId, ownerDisplayName, tokenLifetime, requestId);
        return new BootstrapResult(result.state(), result.bearerToken());
    }

    public HostedTenantState tenant(String bearerToken) throws IOException {
        return tenants.tenant(bearerToken);
    }

    public List<SharedWorkspace> listWorkspaces(String bearerToken) throws IOException {
        return workspaces.list(bearerToken);
    }

    public SharedWorkspace workspace(String bearerToken, UUID workspaceId) throws IOException {
        return workspaces.get(bearerToken, workspaceId);
    }

    public List<HostedPrincipal> listMembers(String bearerToken) throws IOException {
        return memberships.list(bearerToken);
    }

    public List<HostedAuditEvent> audit(String bearerToken, int limit) throws IOException {
        return tenants.audit(bearerToken, limit);
    }

    public SharedWorkspace createWorkspace(String bearerToken, String requestId, String name) throws IOException {
        return workspaces.create(bearerToken, requestId, name);
    }

    public SharedWorkspace archiveWorkspace(
            String bearerToken,
            String requestId,
            UUID workspaceId
    ) throws IOException {
        return workspaces.archive(bearerToken, requestId, workspaceId);
    }

    public HostedPrincipal grantMember(
            String bearerToken,
            String requestId,
            String principalId,
            String displayName,
            HostedRole role
    ) throws IOException {
        return memberships.grant(bearerToken, requestId, principalId, displayName, role);
    }

    public void revokeMember(String bearerToken, String requestId, String principalId) throws IOException {
        memberships.revoke(bearerToken, requestId, principalId);
    }

    public HostedProjectBinding bindProject(
            String bearerToken,
            String requestId,
            UUID workspaceId,
            UUID projectId,
            String snapshotId
    ) throws IOException {
        return workspaces.bind(bearerToken, requestId, workspaceId, projectId, snapshotId);
    }

    public void unbindProject(
            String bearerToken,
            String requestId,
            UUID workspaceId,
            UUID projectId
    ) throws IOException {
        workspaces.unbind(bearerToken, requestId, workspaceId, projectId);
    }

    public HostedRetentionPolicy setRetention(
            String bearerToken,
            String requestId,
            HostedRetentionPolicy policy
    ) throws IOException {
        return retention.set(bearerToken, requestId, policy);
    }

    public HostedRetentionPlan retentionPlan(String bearerToken) throws IOException {
        return retention.plan(bearerToken);
    }

    public RetentionApplyResult applyRetention(String bearerToken, String requestId) throws IOException {
        HostedRetentionService.ApplyResult result = retention.apply(bearerToken, requestId);
        return new RetentionApplyResult(result.plan(), result.state());
    }

    public String issueToken(
            String bearerToken,
            String requestId,
            String targetPrincipalId,
            Duration lifetime
    ) throws IOException {
        return tokens.issue(bearerToken, requestId, targetPrincipalId, lifetime);
    }

    public RotateKeyResult rotateKey(
            String bearerToken,
            String requestId,
            String newKeyId,
            Duration replacementTokenLifetime
    ) throws IOException {
        HostedTokenService.Rotation result = tokens.rotate(
                bearerToken, requestId, newKeyId, replacementTokenLifetime);
        return new RotateKeyResult(result.state(), result.replacementBearerToken());
    }

    public record BootstrapResult(HostedTenantState state, String bearerToken) {
        public BootstrapResult {
            Objects.requireNonNull(state, "state");
            bearerToken = HostedPrincipal.text(bearerToken, "bearerToken", 8192);
        }
    }

    public record RotateKeyResult(HostedTenantState state, String replacementBearerToken) {
        public RotateKeyResult {
            Objects.requireNonNull(state, "state");
            replacementBearerToken = HostedPrincipal.text(
                    replacementBearerToken, "replacementBearerToken", 8192);
        }
    }

    public record RetentionApplyResult(HostedRetentionPlan plan, HostedTenantState state) {
        public RetentionApplyResult {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(state, "state");
        }
    }
}
