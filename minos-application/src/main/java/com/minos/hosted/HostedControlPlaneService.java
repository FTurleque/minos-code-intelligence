package com.minos.hosted;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Authenticated, fail-closed and tenant-isolated M27 control plane. */
public final class HostedControlPlaneService {
    private final HostedControlPlaneStore store;
    private final HmacHostedIdentityProvider identities;
    private final HostedTenantKeyProvider keys;
    private final HostedBindingVerifier bindingVerifier;
    private final Clock clock;

    public HostedControlPlaneService(
            HostedControlPlaneStore store,
            HmacHostedIdentityProvider identities,
            HostedTenantKeyProvider keys,
            HostedBindingVerifier bindingVerifier,
            Clock clock
    ) {
        this.store = Objects.requireNonNull(store, "store");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.bindingVerifier = Objects.requireNonNull(bindingVerifier, "bindingVerifier");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Objects.requireNonNull(tenantId, "tenantId");
        String safeKeyId = HostedPrincipal.safeId(keyId, "keyId");
        String ownerId = HostedPrincipal.safeId(ownerPrincipalId, "ownerPrincipalId");
        String safeRequestId = HostedPrincipal.safeId(requestId, "requestId");
        requireKeys(tenantId, safeKeyId);
        Instant now = clock.instant();
        HostedPrincipal owner = new HostedPrincipal(ownerId, ownerDisplayName, HostedRole.OWNER, now);
        HostedTenantState initial = new HostedTenantState(
                tenantId, tenantName, safeKeyId, 0, now, now, HostedRetentionPolicy.defaults(),
                List.of(owner), List.of(), 0, HostedAuditEvent.GENESIS_HASH, List.of());
        HostedTenantState audited = appendAudit(initial, ownerId, "TENANT_BOOTSTRAP", "TENANT",
                tenantId.toString(), HostedAuditEvent.Outcome.ALLOWED, safeRequestId, safeKeyId, 0);
        store.create(audited);
        String token = identities.issue(tenantId, ownerId, safeKeyId, now, tokenLifetime, UUID.randomUUID().toString());
        return new BootstrapResult(audited, token);
    }

    public HostedTenantState tenant(String bearerToken) throws IOException {
        return authorizeRead(bearerToken, HostedPermission.WORKSPACE_READ).state();
    }

    public List<SharedWorkspace> listWorkspaces(String bearerToken) throws IOException {
        HostedTenantState state = authorizeRead(bearerToken, HostedPermission.WORKSPACE_READ).state();
        return state.workspaces().stream()
                .sorted(Comparator.comparing(SharedWorkspace::name).thenComparing(SharedWorkspace::workspaceId))
                .toList();
    }

    public SharedWorkspace workspace(String bearerToken, UUID workspaceId) throws IOException {
        HostedTenantState state = authorizeRead(bearerToken, HostedPermission.WORKSPACE_READ).state();
        return requireWorkspace(state, workspaceId);
    }

    public List<HostedPrincipal> listMembers(String bearerToken) throws IOException {
        HostedTenantState state = authorizeRead(bearerToken, HostedPermission.MEMBER_READ).state();
        return state.members().stream().sorted(Comparator.comparing(HostedPrincipal::principalId)).toList();
    }

    public List<HostedAuditEvent> audit(String bearerToken, int limit) throws IOException {
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("audit limit must be between 1 and 10000");
        HostedTenantState state = authorizeRead(bearerToken, HostedPermission.AUDIT_READ).state();
        int start = Math.max(0, state.auditEvents().size() - limit);
        List<HostedAuditEvent> values = new ArrayList<>(state.auditEvents().subList(start, state.auditEvents().size()));
        java.util.Collections.reverse(values);
        return List.copyOf(values);
    }

    public SharedWorkspace createWorkspace(String bearerToken, String requestId, String name) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.WORKSPACE_WRITE,
                "WORKSPACE_CREATE", "WORKSPACE", "new");
        if (context.state().workspaces().size() >= HostedTenantState.MAX_WORKSPACES) {
            throw new IllegalStateException("hosted workspace capacity reached");
        }
        String safeName = HostedPrincipal.text(name, "workspace name", 256);
        if (context.state().workspaces().stream().anyMatch(value -> value.name().equalsIgnoreCase(safeName))) {
            throw new IllegalArgumentException("workspace name already exists in tenant");
        }
        Instant now = clock.instant();
        SharedWorkspace created = new SharedWorkspace(UUID.randomUUID(), context.state().tenantId(), safeName,
                SharedWorkspace.Status.ACTIVE, now, now, null, List.of());
        List<SharedWorkspace> workspaces = appended(context.state().workspaces(), created);
        saveAllowed(context, context.state().members(), workspaces, context.state().retentionPolicy(),
                context.state().keyId(), context.state().auditAnchorHash(), context.state().auditEvents(),
                context.state().auditSequence(), "WORKSPACE_CREATE", "WORKSPACE", created.workspaceId().toString());
        return created;
    }

    public SharedWorkspace archiveWorkspace(String bearerToken, String requestId, UUID workspaceId) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.WORKSPACE_WRITE,
                "WORKSPACE_ARCHIVE", "WORKSPACE", workspaceId.toString());
        SharedWorkspace existing = requireWorkspace(context.state(), workspaceId);
        if (existing.status() == SharedWorkspace.Status.ARCHIVED) {
            saveAllowed(context, context.state().members(), context.state().workspaces(),
                    context.state().retentionPolicy(), context.state().keyId(), context.state().auditAnchorHash(),
                    context.state().auditEvents(), context.state().auditSequence(),
                    "WORKSPACE_ARCHIVE", "WORKSPACE", workspaceId.toString());
            return existing;
        }
        Instant now = clock.instant();
        SharedWorkspace archived = new SharedWorkspace(existing.workspaceId(), existing.tenantId(), existing.name(),
                SharedWorkspace.Status.ARCHIVED, existing.createdAt(), now, now, existing.bindings());
        List<SharedWorkspace> workspaces = replaceWorkspace(context.state().workspaces(), archived);
        saveAllowed(context, context.state().members(), workspaces, context.state().retentionPolicy(),
                context.state().keyId(), context.state().auditAnchorHash(), context.state().auditEvents(),
                context.state().auditSequence(), "WORKSPACE_ARCHIVE", "WORKSPACE", workspaceId.toString());
        return archived;
    }

    public HostedPrincipal grantMember(
            String bearerToken,
            String requestId,
            String principalId,
            String displayName,
            HostedRole role
    ) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.MEMBER_WRITE,
                "MEMBER_GRANT", "PRINCIPAL", principalId);
        String safePrincipal = HostedPrincipal.safeId(principalId, "principalId");
        HostedPrincipal membership = new HostedPrincipal(safePrincipal, displayName, role, clock.instant());
        List<HostedPrincipal> members = new ArrayList<>(context.state().members());
        int existing = indexOfPrincipal(members, safePrincipal);
        if (existing >= 0) members.set(existing, membership);
        else {
            if (members.size() >= HostedTenantState.MAX_MEMBERS) throw new IllegalStateException("hosted member capacity reached");
            members.add(membership);
        }
        requireOwner(members);
        saveAllowed(context, List.copyOf(members), context.state().workspaces(), context.state().retentionPolicy(),
                context.state().keyId(), context.state().auditAnchorHash(), context.state().auditEvents(),
                context.state().auditSequence(), "MEMBER_GRANT", "PRINCIPAL", safePrincipal);
        return membership;
    }

    public void revokeMember(String bearerToken, String requestId, String principalId) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.MEMBER_WRITE,
                "MEMBER_REVOKE", "PRINCIPAL", principalId);
        String safePrincipal = HostedPrincipal.safeId(principalId, "principalId");
        List<HostedPrincipal> members = context.state().members().stream()
                .filter(value -> !value.principalId().equals(safePrincipal)).toList();
        if (members.size() == context.state().members().size()) throw new IllegalArgumentException("tenant member not found");
        requireOwner(members);
        saveAllowed(context, members, context.state().workspaces(), context.state().retentionPolicy(),
                context.state().keyId(), context.state().auditAnchorHash(), context.state().auditEvents(),
                context.state().auditSequence(), "MEMBER_REVOKE", "PRINCIPAL", safePrincipal);
    }

    public HostedProjectBinding bindProject(
            String bearerToken,
            String requestId,
            UUID workspaceId,
            UUID projectId,
            String snapshotId
    ) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.BINDING_WRITE,
                "PROJECT_BIND", "WORKSPACE", workspaceId.toString());
        SharedWorkspace workspace = requireActiveWorkspace(context.state(), workspaceId);
        bindingVerifier.requireSnapshot(projectId, snapshotId);
        if (workspace.bindings().stream().anyMatch(value -> value.projectId().equals(projectId))) {
            throw new IllegalArgumentException("project is already bound to workspace");
        }
        HostedProjectBinding binding = new HostedProjectBinding(
                projectId, snapshotId, clock.instant(), context.claims().principalId());
        List<HostedProjectBinding> bindings = appended(workspace.bindings(), binding);
        SharedWorkspace updated = new SharedWorkspace(workspace.workspaceId(), workspace.tenantId(), workspace.name(),
                workspace.status(), workspace.createdAt(), clock.instant(), null, bindings);
        saveAllowed(context, context.state().members(), replaceWorkspace(context.state().workspaces(), updated),
                context.state().retentionPolicy(), context.state().keyId(), context.state().auditAnchorHash(),
                context.state().auditEvents(), context.state().auditSequence(), "PROJECT_BIND", "PROJECT", projectId.toString());
        return binding;
    }

    public void unbindProject(
            String bearerToken,
            String requestId,
            UUID workspaceId,
            UUID projectId
    ) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.BINDING_WRITE,
                "PROJECT_UNBIND", "WORKSPACE", workspaceId.toString());
        SharedWorkspace workspace = requireActiveWorkspace(context.state(), workspaceId);
        List<HostedProjectBinding> bindings = workspace.bindings().stream()
                .filter(value -> !value.projectId().equals(projectId)).toList();
        if (bindings.size() == workspace.bindings().size()) throw new IllegalArgumentException("project binding not found");
        SharedWorkspace updated = new SharedWorkspace(workspace.workspaceId(), workspace.tenantId(), workspace.name(),
                workspace.status(), workspace.createdAt(), clock.instant(), null, bindings);
        saveAllowed(context, context.state().members(), replaceWorkspace(context.state().workspaces(), updated),
                context.state().retentionPolicy(), context.state().keyId(), context.state().auditAnchorHash(),
                context.state().auditEvents(), context.state().auditSequence(), "PROJECT_UNBIND", "PROJECT", projectId.toString());
    }

    public HostedRetentionPolicy setRetention(
            String bearerToken,
            String requestId,
            HostedRetentionPolicy policy
    ) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.RETENTION_MANAGE,
                "RETENTION_SET", "TENANT", "policy");
        HostedRetentionPolicy safePolicy = Objects.requireNonNull(policy, "policy");
        saveAllowed(context, context.state().members(), context.state().workspaces(), safePolicy,
                context.state().keyId(), context.state().auditAnchorHash(), context.state().auditEvents(),
                context.state().auditSequence(), "RETENTION_SET", "TENANT", "policy");
        return safePolicy;
    }

    public HostedRetentionPlan retentionPlan(String bearerToken) throws IOException {
        HostedTenantState state = authorizeRead(bearerToken, HostedPermission.RETENTION_MANAGE).state();
        return retentionPlan(state, clock.instant());
    }

    public RetentionApplyResult applyRetention(String bearerToken, String requestId) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.RETENTION_MANAGE,
                "RETENTION_APPLY", "TENANT", "retention");
        Instant now = clock.instant();
        HostedRetentionPlan plan = retentionPlan(context.state(), now);
        List<HostedAuditEvent> audit = context.state().auditEvents();
        String anchor = context.state().auditAnchorHash();
        if (plan.auditEventsToRemove() > 0) {
            anchor = audit.get(plan.auditEventsToRemove() - 1).hash();
            audit = List.copyOf(audit.subList(plan.auditEventsToRemove(), audit.size()));
        }
        var removed = java.util.Set.copyOf(plan.archivedWorkspacesToRemove());
        List<SharedWorkspace> workspaces = context.state().workspaces().stream()
                .filter(value -> !removed.contains(value.workspaceId())).toList();
        HostedTenantState saved = saveAllowed(context, context.state().members(), workspaces,
                context.state().retentionPolicy(), context.state().keyId(), anchor, audit,
                context.state().auditSequence(), "RETENTION_APPLY", "TENANT", "retention");
        return new RetentionApplyResult(plan, saved);
    }

    public String issueToken(
            String bearerToken,
            String requestId,
            String targetPrincipalId,
            Duration lifetime
    ) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.TENANT_ADMIN,
                "TOKEN_ISSUE", "PRINCIPAL", targetPrincipalId);
        String target = HostedPrincipal.safeId(targetPrincipalId, "targetPrincipalId");
        if (context.state().members().stream().noneMatch(member -> member.principalId().equals(target))) {
            throw new IllegalArgumentException("token target is not a tenant member");
        }
        HostedTenantState saved = saveAllowed(context, context.state().members(), context.state().workspaces(),
                context.state().retentionPolicy(), context.state().keyId(), context.state().auditAnchorHash(),
                context.state().auditEvents(), context.state().auditSequence(), "TOKEN_ISSUE", "PRINCIPAL", target);
        return identities.issue(saved.tenantId(), target, saved.keyId(), clock.instant(), lifetime, UUID.randomUUID().toString());
    }

    public RotateKeyResult rotateKey(
            String bearerToken,
            String requestId,
            String newKeyId,
            Duration replacementTokenLifetime
    ) throws IOException {
        MutationContext context = authorizeMutation(bearerToken, requestId, HostedPermission.KEY_ROTATE,
                "KEY_ROTATE", "TENANT", "key");
        String safeNewKeyId = HostedPrincipal.safeId(newKeyId, "newKeyId");
        if (context.state().keyId().equals(safeNewKeyId)) throw new IllegalArgumentException("new keyId matches current keyId");
        requireKeys(context.state().tenantId(), safeNewKeyId);
        HostedTenantState saved = saveAllowed(context, context.state().members(), context.state().workspaces(),
                context.state().retentionPolicy(), safeNewKeyId, context.state().auditAnchorHash(),
                context.state().auditEvents(), context.state().auditSequence(), "KEY_ROTATE", "TENANT", "key");
        String replacement = identities.issue(saved.tenantId(), context.claims().principalId(), safeNewKeyId,
                clock.instant(), replacementTokenLifetime, UUID.randomUUID().toString());
        return new RotateKeyResult(saved, replacement);
    }

    private Authorization authorizeRead(String bearerToken, HostedPermission permission) throws IOException {
        HostedAccessClaims claims = identities.authenticate(bearerToken, clock.instant());
        HostedTenantState state = loadVerified(claims.tenantId());
        if (!claims.keyId().equals(state.keyId())) throw new SecurityException("hosted bearer token uses an inactive key");
        HostedPrincipal principal = member(state, claims.principalId())
                .orElseThrow(() -> new SecurityException("authenticated principal is not a tenant member"));
        if (!principal.role().allows(permission)) throw new SecurityException("hosted permission denied: " + permission);
        return new Authorization(claims, state, principal);
    }

    private MutationContext authorizeMutation(
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
        Optional<HostedPrincipal> membership = member(state, claims.principalId());
        boolean allowed = claims.keyId().equals(state.keyId()) && membership.isPresent()
                && membership.orElseThrow().role().allows(permission);
        if (!allowed) {
            HostedTenantState denied = appendAudit(state, claims.principalId(), action, resourceType, resourceId,
                    HostedAuditEvent.Outcome.DENIED, safeRequestId, state.keyId(), state.version() + 1);
            store.save(denied, state.version());
            throw new SecurityException("hosted permission denied: " + permission);
        }
        return new MutationContext(claims, state, membership.orElseThrow(), safeRequestId);
    }

    private HostedTenantState saveAllowed(
            MutationContext context,
            List<HostedPrincipal> members,
            List<SharedWorkspace> workspaces,
            HostedRetentionPolicy retention,
            String keyId,
            String auditAnchor,
            List<HostedAuditEvent> audit,
            long auditSequence,
            String action,
            String resourceType,
            String resourceId
    ) throws IOException {
        HostedTenantState business = new HostedTenantState(context.state().tenantId(), context.state().name(), keyId,
                context.state().version(), context.state().createdAt(), clock.instant(), retention, members, workspaces,
                auditSequence, auditAnchor, audit);
        HostedTenantState updated = appendAudit(business, context.claims().principalId(), action, resourceType, resourceId,
                HostedAuditEvent.Outcome.ALLOWED, context.requestId(), keyId, context.state().version() + 1);
        store.save(updated, context.state().version());
        return updated;
    }

    private HostedTenantState appendAudit(
            HostedTenantState state,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            HostedAuditEvent.Outcome outcome,
            String requestId,
            String keyId,
            long targetVersion
    ) {
        if (state.auditEvents().size() >= HostedRetentionPolicy.MAX_AUDIT_EVENTS) {
            throw new IllegalStateException("hosted audit hard capacity reached; apply retention explicitly");
        }
        long sequence = state.auditSequence() + 1;
        String previous = state.auditEvents().isEmpty()
                ? state.auditAnchorHash() : state.auditEvents().getLast().hash();
        Instant occurredAt = clock.instant();
        String hash = auditHash(state.tenantId(), sequence, occurredAt, principalId, action, resourceType,
                resourceId, outcome, requestId, keyId, previous);
        HostedAuditEvent event = new HostedAuditEvent(sequence, state.tenantId(), occurredAt, principalId, action,
                resourceType, resourceId, outcome, requestId, keyId, previous, hash);
        return new HostedTenantState(state.tenantId(), state.name(), keyId, targetVersion,
                state.createdAt(), occurredAt, state.retentionPolicy(), state.members(), state.workspaces(),
                sequence, state.auditAnchorHash(), appended(state.auditEvents(), event));
    }

    private HostedTenantState loadVerified(UUID tenantId) throws IOException {
        HostedTenantState state = store.find(tenantId)
                .orElseThrow(() -> new SecurityException("authenticated hosted tenant does not exist"));
        verifyAudit(state);
        return state;
    }

    private void verifyAudit(HostedTenantState state) {
        String previous = state.auditAnchorHash();
        for (HostedAuditEvent event : state.auditEvents()) {
            if (!previous.equals(event.previousHash())) throw new SecurityException("hosted audit chain is broken");
            String expected = auditHash(event.tenantId(), event.sequence(), event.occurredAt(), event.principalId(),
                    event.action(), event.resourceType(), event.resourceId(), event.outcome(), event.requestId(),
                    event.keyId(), event.previousHash());
            if (!java.security.MessageDigest.isEqual(
                    expected.getBytes(StandardCharsets.US_ASCII), event.hash().getBytes(StandardCharsets.US_ASCII))) {
                throw new SecurityException("hosted audit event authentication failed");
            }
            previous = event.hash();
        }
    }

    private String auditHash(
            UUID tenantId,
            long sequence,
            Instant occurredAt,
            String principalId,
            String action,
            String resourceType,
            String resourceId,
            HostedAuditEvent.Outcome outcome,
            String requestId,
            String keyId,
            String previous
    ) {
        String canonical = sequence + "\0" + tenantId + "\0" + occurredAt.getEpochSecond() + "\0"
                + occurredAt.getNano() + "\0" + principalId + "\0" + action + "\0" + resourceType + "\0"
                + resourceId + "\0" + outcome + "\0" + requestId + "\0" + keyId + "\0" + previous;
        try {
            SecretKey key = keys.resolve(tenantId, keyId, HostedTenantKeyProvider.Purpose.AUDIT_CHAIN);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | IllegalStateException exception) {
            throw new SecurityException("hosted audit cryptographic operation failed", exception);
        }
    }

    private void requireKeys(UUID tenantId, String keyId) {
        for (HostedTenantKeyProvider.Purpose purpose : HostedTenantKeyProvider.Purpose.values()) {
            SecretKey key = keys.resolve(tenantId, keyId, purpose);
            if (key.getEncoded() == null || key.getEncoded().length != 32) {
                throw new IllegalStateException("hosted derived key must contain 256 bits");
            }
        }
    }

    private static HostedRetentionPlan retentionPlan(HostedTenantState state, Instant now) {
        Instant auditCutoff = now.minus(state.retentionPolicy().auditRetentionDays(), ChronoUnit.DAYS);
        int byAge = 0;
        while (byAge < state.auditEvents().size()
                && state.auditEvents().get(byAge).occurredAt().isBefore(auditCutoff)) byAge++;
        int byCount = Math.max(0, state.auditEvents().size() - state.retentionPolicy().maxAuditEvents());
        int auditToRemove = Math.max(byAge, byCount);
        Instant workspaceCutoff = now.minus(state.retentionPolicy().archivedWorkspaceRetentionDays(), ChronoUnit.DAYS);
        List<UUID> workspaces = state.workspaces().stream()
                .filter(value -> value.status() == SharedWorkspace.Status.ARCHIVED)
                .filter(value -> value.archivedAt().isBefore(workspaceCutoff))
                .map(SharedWorkspace::workspaceId).sorted().toList();
        return new HostedRetentionPlan(state.tenantId(), now, auditToRemove, workspaces);
    }

    private static Optional<HostedPrincipal> member(HostedTenantState state, String principalId) {
        return state.members().stream().filter(value -> value.principalId().equals(principalId)).findFirst();
    }

    private static int indexOfPrincipal(List<HostedPrincipal> values, String principalId) {
        for (int index = 0; index < values.size(); index++) {
            if (values.get(index).principalId().equals(principalId)) return index;
        }
        return -1;
    }

    private static void requireOwner(List<HostedPrincipal> members) {
        if (members.stream().noneMatch(value -> value.role() == HostedRole.OWNER)) {
            throw new IllegalArgumentException("cannot remove or demote the last tenant owner");
        }
    }

    private static SharedWorkspace requireWorkspace(HostedTenantState state, UUID workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        return state.workspaces().stream().filter(value -> value.workspaceId().equals(workspaceId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("workspace not found in authenticated tenant"));
    }

    private static SharedWorkspace requireActiveWorkspace(HostedTenantState state, UUID workspaceId) {
        SharedWorkspace workspace = requireWorkspace(state, workspaceId);
        if (workspace.status() != SharedWorkspace.Status.ACTIVE) {
            throw new IllegalStateException("archived workspace cannot be mutated");
        }
        return workspace;
    }

    private static List<SharedWorkspace> replaceWorkspace(List<SharedWorkspace> values, SharedWorkspace replacement) {
        List<SharedWorkspace> updated = new ArrayList<>(values.size());
        for (SharedWorkspace value : values) {
            updated.add(value.workspaceId().equals(replacement.workspaceId()) ? replacement : value);
        }
        return List.copyOf(updated);
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> updated = new ArrayList<>(values.size() + 1);
        updated.addAll(values);
        updated.add(value);
        return List.copyOf(updated);
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
            replacementBearerToken = HostedPrincipal.text(replacementBearerToken, "replacementBearerToken", 8192);
        }
    }

    public record RetentionApplyResult(HostedRetentionPlan plan, HostedTenantState state) {
        public RetentionApplyResult {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(state, "state");
        }
    }

    private record Authorization(HostedAccessClaims claims, HostedTenantState state, HostedPrincipal principal) { }

    private record MutationContext(
            HostedAccessClaims claims,
            HostedTenantState state,
            HostedPrincipal principal,
            String requestId
    ) { }
}
