package com.minos.hosted;

import com.minos.io.Sha256;

import java.io.IOException;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Central fail-closed authentication, membership and RBAC enforcement for hosted operations. */
final class HostedAuthorizationService {

    private static final System.Logger LOGGER = System.getLogger(HostedAuthorizationService.class.getName());
    /** Prefix of the deterministic stand-in recorded when a refused caller supplies an unusable resource id. */
    static final String INVALID_RESOURCE_PREFIX = "invalid:";
    /**
     * Share of a store's per-tenant byte limit that chained refusals may occupy, as a denominator: refusals
     * take at most a quarter, so the rest stays available to authorized mutations (including retention).
     */
    static final long DENIED_BYTES_SHARE_DENOMINATOR = 4;

    private final HostedControlPlaneStore store;
    private final HostedIdentityProvider identities;
    private final HostedAuditChain auditChain;
    private final HostedAuditSink auditSink;
    private final Clock clock;
    private final HostedDenialThrottle denialThrottle = new HostedDenialThrottle();
    /** Bounds the journal entries of tokens signed with a retired key, keyed by (tenant, key id). */
    private final HostedDenialThrottle inactiveKeyThrottle = new HostedDenialThrottle();

    HostedAuthorizationService(
            HostedControlPlaneStore store,
            HostedIdentityProvider identities,
            HostedAuditChain auditChain,
            HostedAuditSink auditSink,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.auditChain = Objects.requireNonNull(auditChain, "auditChain");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    Authorization authorizeRead(String bearerToken, HostedPermission permission) throws IOException {
        HostedAccessClaims claims = identities.authenticate(bearerToken, clock.instant());
        HostedTenantState state = loadVerified(claims.tenantId());
        if (!claims.keyId().equals(state.keyId())) {
            throw new SecurityException("hosted bearer token uses an inactive key");
        }
        HostedPrincipal principal = member(state, claims.principalId())
                .orElseThrow(() -> new SecurityException("authenticated principal is not a tenant member"));
        if (!principal.role().allows(permission)) {
            throw new SecurityException("hosted permission denied: " + permission);
        }
        return new Authorization(claims, state, principal);
    }

    MutationContext authorizeMutation(
            String bearerToken,
            String requestId,
            HostedPermission permission,
            String action,
            String resourceType,
            String resourceId
    ) throws IOException {
        String safeRequestId = HostedPrincipal.safeId(requestId, "requestId");
        HostedAccessClaims claims = identities.authenticate(bearerToken, clock.instant());
        HostedTenantState state = loadVerified(claims.tenantId());
        if (!claims.keyId().equals(state.keyId())) {
            // A retired key still authenticates (it must, to verify history) but never opens a mutation: this is
            // an authentication failure, exactly as for a read, not an RBAC refusal chained under a name the
            // token merely claims.
            journalInactiveKey(state, claims);
            throw new SecurityException("hosted bearer token uses an inactive key");
        }
        Optional<HostedPrincipal> membership = member(state, claims.principalId());
        boolean allowed = membership.isPresent() && membership.orElseThrow().role().allows(permission);
        if (!allowed) {
            recordDenial(state, claims.principalId(), action, resourceType, resourceId, safeRequestId);
            throw new SecurityException("hosted permission denied: " + permission);
        }
        return new MutationContext(claims, state, membership.orElseThrow(), safeRequestId);
    }

    /**
     * Refuses a mutation whose coarse permission check already passed but whose business rule
     * (for example role governance) forbids it. The refusal is audited, persisted and published
     * exactly like a permission refusal; the returned exception carries only the action name.
     */
    SecurityException deny(
            MutationContext context,
            String action,
            String resourceType,
            String resourceId
    ) {
        recordDenial(
                context.state(), context.claims().principalId(), action, resourceType, resourceId,
                context.requestId());
        return new SecurityException("hosted permission denied: " + action);
    }

    /**
     * Records a refusal on both refusal paths (permission and post-authorization rule). A refusal
     * is chained, persisted (version + 1) and published only while the refusals already chained
     * stay below the policy's denied capacity (authorized events are not counted), they stay below their
     * share of the store's byte limit, the authorized headroom below the hard capacity is untouched and the
     * (tenant, principal) refusal budget of this process is not exhausted; otherwise it is delivered to the
     * sink as an unchained event and the tenant state is untouched, so looping refusals can neither exhaust
     * the audit capacity or the stored bytes nor churn the tenant version.
     *
     * <p>A refusal never fails: the resource id is reduced to a bounded canonical form first, and any
     * failure to chain or persist the refusal falls back to the unchained path, so the caller always gets
     * the refusal and the refusal always leaves a trace.</p>
     */
    private void recordDenial(
            HostedTenantState state,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            String requestId
    ) {
        String boundedResourceId = refusalResourceId(resourceId);
        HostedAuditEvent candidate = auditChain.unchainedRefusal(
                state, principalId, action, resourceType, boundedResourceId, requestId, state.keyId());
        HostedTenantState denied = null;
        try {
            if (admitsChainedDenial(state, candidate)
                    && denialThrottle.tryAcquire(state.tenantId(), principalId, clock.instant())) {
                denied = auditChain.append(
                        state,
                        principalId,
                        action,
                        resourceType,
                        boundedResourceId,
                        HostedAuditEvent.Outcome.DENIED,
                        requestId,
                        state.keyId(),
                        state.version() + 1);
                HostedCommitRecovery.save(store, denied, state.version());
            }
        } catch (IOException | RuntimeException failure) {
            // Version conflict, I/O failure or an uncertain commit: the refusal is still enforced and traced.
            // An uncertain commit may have persisted the chained refusal; a double trace beats a lost one.
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Hosted refusal could not be chained; delivering it unchained (tenant=" + state.tenantId()
                            + ", principal=" + principalId + ", action=" + action + ", requestId=" + requestId + ")",
                    failure);
            denied = null;
        }
        if (denied == null) {
            HostedAuditDelivery.publishUnchained(auditSink, candidate);
            return;
        }
        HostedAuditDelivery.publishAfterCommit(auditSink, denied.auditEvents().getLast());
    }

    /**
     * Whether one more refusal may join the chain: the count-based admission of the retention policy and the
     * byte budget of the store. Without a byte limit only the count applies.
     */
    private boolean admitsChainedDenial(HostedTenantState state, HostedAuditEvent candidate) {
        DeniedTally tally = deniedTally(state);
        if (!state.retentionPolicy().admitsChainedDenial(tally.count(), state.auditEvents().size())) {
            return false;
        }
        return store.tenantByteLimit().stream().noneMatch(limit ->
                tally.bytes() + candidate.estimatedEncodedBytes() > limit / DENIED_BYTES_SHARE_DENOMINATOR);
    }

    /**
     * Resource id recorded for a refusal: the caller-supplied value when it is a safe identifier, otherwise a
     * deterministic stand-in made of {@value #INVALID_RESOURCE_PREFIX} and the SHA-256 of the raw value. The
     * raw value is never recorded, so the size of a refusal does not depend on what the caller sent, and two
     * different invalid values stay distinguishable.
     */
    static String refusalResourceId(String resourceId) {
        if (HostedPrincipal.isSafeId(resourceId)) {
            return resourceId.trim();
        }
        return INVALID_RESOURCE_PREFIX + Sha256.hex(resourceId == null ? "" : resourceId);
    }

    private void journalInactiveKey(HostedTenantState state, HostedAccessClaims claims) {
        if (!inactiveKeyThrottle.tryAcquire(state.tenantId(), claims.keyId(), clock.instant())) {
            return;
        }
        LOGGER.log(
                System.Logger.Level.WARNING,
                "Hosted mutation refused: bearer token signed with an inactive key (tenant=" + state.tenantId()
                        + ", keyId=" + claims.keyId() + ")");
    }

    /**
     * Refusals currently held by the retained chain, and the bytes they are estimated to take. Derived from
     * the chain itself, the tally is coherent with any explicit retention by construction (no counter to
     * persist or resynchronize). The scan is bounded by {@link HostedRetentionPolicy#MAX_AUDIT_EVENTS}, runs
     * only on a refusal, and is dominated by the HMAC verification of the same chain that
     * {@link #loadVerified} has just performed on the same path.
     */
    private static DeniedTally deniedTally(HostedTenantState state) {
        long count = 0;
        long bytes = 0;
        for (HostedAuditEvent event : state.auditEvents()) {
            if (event.outcome() == HostedAuditEvent.Outcome.DENIED) {
                count++;
                bytes += event.estimatedEncodedBytes();
            }
        }
        return new DeniedTally(count, bytes);
    }

    private record DeniedTally(long count, long bytes) {
    }

    HostedTenantState loadVerified(UUID tenantId) throws IOException {
        HostedTenantState state = store.find(tenantId)
                .orElseThrow(() -> new SecurityException("authenticated hosted tenant does not exist"));
        auditChain.verify(state);
        return state;
    }

    private static Optional<HostedPrincipal> member(HostedTenantState state, String principalId) {
        return state.members().stream()
                .filter(value -> value.principalId().equals(principalId))
                .findFirst();
    }

    record Authorization(HostedAccessClaims claims, HostedTenantState state, HostedPrincipal principal) {
        Authorization {
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(principal, "principal");
        }
    }

    record MutationContext(
            HostedAccessClaims claims,
            HostedTenantState state,
            HostedPrincipal principal,
            String requestId
    ) {
        MutationContext {
            Objects.requireNonNull(claims, "claims");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(principal, "principal");
            requestId = HostedPrincipal.safeId(requestId, "requestId");
        }
    }
}
