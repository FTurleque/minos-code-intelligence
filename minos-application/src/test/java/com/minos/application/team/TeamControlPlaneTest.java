package com.minos.application.team;

import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedAuditSink;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Clock;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The team facade delegates every operation to the engine service and projects its results without deciding. */
class TeamControlPlaneTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private HostedControlPlaneService service;
    private TeamControlPlane team;

    @BeforeEach
    void createFacade() {
        HostedTenantKeyProvider keys = (tenantId, keyId, purpose) -> {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, (byte) Objects.hash(tenantId, keyId, purpose));
            return new SecretKeySpec(bytes, purpose == HostedTenantKeyProvider.Purpose.ENCRYPTION ? "AES" : "HmacSHA256");
        };
        service = new HostedControlPlaneService(new MemoryStore(), new HmacHostedIdentityProvider(keys), keys,
                (project, snapshot) -> { }, HostedAuditSink.embeddedNoop(), Clock.systemUTC());
        team = new TeamControlPlane(service);
    }

    @Test
    void projectsTheBootstrappedTenant() throws Exception {
        TeamBootstrap bootstrap = team.bootstrap(TENANT, "Acme", "key-a", "alice", "Alice", Duration.ofHours(1), "req-1");
        TeamTenant tenant = bootstrap.tenant();
        assertEquals(TENANT, tenant.tenantId());
        assertEquals("Acme", tenant.name());
        assertEquals("key-a", tenant.keyId());
        assertEquals(1, tenant.memberCount());
        assertEquals(0, tenant.workspaceCount());
        assertEquals(new TeamRetentionPolicy(10_000, 365, 90), tenant.retentionPolicy());
        assertEquals(tenant, team.tenant(bootstrap.bearerToken()));
    }

    @Test
    void projectsWorkspacesAndTheirBindings() throws Exception {
        String token = ownerToken();
        TeamWorkspace created = team.createWorkspace(token, "req-2", "Platform");
        assertEquals("ACTIVE", created.status());
        assertNull(created.archivedAt());
        UUID project = UUID.randomUUID();
        TeamBinding binding = team.bindProject(token, "req-3", created.workspaceId(), project, "snap-1");
        assertEquals(project, binding.projectId());
        assertEquals("snap-1", binding.snapshotId());
        assertEquals("alice", binding.boundBy());
        assertEquals(List.of(binding), team.workspace(token, created.workspaceId()).bindings());
        team.unbindProject(token, "req-4", created.workspaceId(), project);
        assertTrue(team.workspace(token, created.workspaceId()).bindings().isEmpty());
        TeamWorkspace archived = team.archiveWorkspace(token, "req-5", created.workspaceId());
        assertEquals("ARCHIVED", archived.status());
        assertEquals(List.of(archived), team.listWorkspaces(token));
    }

    @Test
    void projectsMembersWithTheirRoleAndPermissions() throws Exception {
        String token = ownerToken();
        TeamMember viewer = team.grantMember(token, "req-6", "bob", "Bob", TeamRole.VIEWER);
        assertEquals(TeamRole.VIEWER, viewer.role());
        assertEquals(List.of("MEMBER_READ", "WORKSPACE_READ"), viewer.permissions());
        assertEquals(List.of("alice", "bob"), team.listMembers(token).stream().map(TeamMember::principalId).sorted().toList());
        team.revokeMember(token, "req-7", "bob");
        assertEquals(1, team.listMembers(token).size());
    }

    @Test
    void projectsTheRetentionAndTheAuditChain() throws Exception {
        String token = ownerToken();
        TeamRetentionPolicy policy = new TeamRetentionPolicy(200, 30, 7);
        assertEquals(policy, team.setRetention(token, "req-8", policy));
        assertEquals(TENANT, team.retentionPlan(token).tenantId());
        TeamRetentionApply applied = team.applyRetention(token, "req-9");
        assertEquals(TENANT, applied.plan().tenantId());
        TeamTenant current = team.tenant(token);
        assertEquals(current.version(), applied.tenantVersion());
        assertEquals(current.workspaceCount(), applied.remainingWorkspaces());

        List<TeamAuditEvent> audit = team.audit(token, TeamControlPlane.MAX_AUDIT_LIMIT);
        assertEquals(service.audit(token, TeamControlPlane.MAX_AUDIT_LIMIT).stream().map(TeamAuditEvent::of).toList(),
                audit);
        assertTrue(audit.stream().allMatch(event -> TENANT.equals(event.tenantId())));
        assertTrue(audit.stream().allMatch(event -> "ALLOWED".equals(event.outcome())));
        assertEquals(current.auditSequence(), audit.stream().mapToLong(TeamAuditEvent::sequence).max().orElseThrow());
    }

    @Test
    void issuesTokensAndRotatesTheKey() throws Exception {
        String token = ownerToken();
        String issued = team.issueToken(token, "req-10", "alice", Duration.ofHours(1));
        assertEquals(TENANT, team.tenant(issued).tenantId());
        TeamKeyRotation rotation = team.rotateKey(token, "req-11", "key-b", Duration.ofHours(1));
        assertEquals("key-b", rotation.tenant().keyId());
        assertEquals("key-b", team.tenant(rotation.replacementBearerToken()).keyId());
    }

    @Test
    void propagatesTheRefusalOfTheEngineUnchanged() throws Exception {
        team.bootstrap(TENANT, "Acme", "key-a", "alice", "Alice", Duration.ofHours(1), "req-1");
        Exception engine = assertThrows(Exception.class, () -> service.tenant("mht1.forged"));
        Exception facade = assertThrows(Exception.class, () -> team.tenant("mht1.forged"));
        assertSame(engine.getClass(), facade.getClass());
        assertEquals(engine.getMessage(), facade.getMessage());
    }

    @Test
    void letsTheEngineAuthorizeBeforeItRefusesAMissingInput() throws Exception {
        String owner = team.bootstrap(TENANT, "Acme", "key-a", "alice", "Alice", Duration.ofHours(1), "req-1")
                .bearerToken();
        team.grantMember(owner, "req-2", "bob", "Bob", TeamRole.VIEWER);
        String viewer = team.issueToken(owner, "req-3", "bob", Duration.ofHours(1));
        Exception engineRole = assertThrows(Exception.class,
                () -> service.grantMember(viewer, "req-4", "carol", "Carol", null));
        Exception facadeRole = assertThrows(Exception.class,
                () -> team.grantMember(viewer, "req-5", "carol", "Carol", null));
        assertSame(engineRole.getClass(), facadeRole.getClass());
        assertEquals(engineRole.getMessage(), facadeRole.getMessage());
        Exception enginePolicy = assertThrows(Exception.class, () -> service.setRetention(viewer, "req-6", null));
        Exception facadePolicy = assertThrows(Exception.class, () -> team.setRetention(viewer, "req-7", null));
        assertSame(enginePolicy.getClass(), facadePolicy.getClass());
        assertEquals(enginePolicy.getMessage(), facadePolicy.getMessage());
    }

    @Test
    void validatesTheRetentionPolicyWithTheRulesOfTheEngine() {
        IllegalArgumentException engine = assertThrows(IllegalArgumentException.class,
                () -> new HostedRetentionPolicy(99, 30, 7));
        IllegalArgumentException facade = assertThrows(IllegalArgumentException.class,
                () -> new TeamRetentionPolicy(99, 30, 7));
        assertEquals(engine.getMessage(), facade.getMessage());
        assertEquals(HostedRetentionPolicy.MIN_AUDIT_EVENTS, TeamRetentionPolicy.MIN_AUDIT_EVENTS);
        assertEquals(HostedRetentionPolicy.MAX_AUDIT_EVENTS, TeamRetentionPolicy.MAX_AUDIT_EVENTS);
        assertEquals(HostedRetentionPolicy.MIN_RETENTION_DAYS, TeamRetentionPolicy.MIN_RETENTION_DAYS);
        assertEquals(HostedRetentionPolicy.MAX_RETENTION_DAYS, TeamRetentionPolicy.MAX_RETENTION_DAYS);
        assertEquals(HostedControlPlaneService.MIN_AUDIT_LIMIT, TeamControlPlane.MIN_AUDIT_LIMIT);
        assertEquals(HostedControlPlaneService.MAX_AUDIT_LIMIT, TeamControlPlane.MAX_AUDIT_LIMIT);
    }

    @Test
    void everyEngineRoleHasItsTeamRoleAndConversely() {
        assertEquals(Arrays.stream(HostedRole.values()).map(Enum::name).toList(),
                Arrays.stream(TeamRole.values()).map(Enum::name).toList());
    }

    @Test
    void masksBearerTokensInTheirTextForm() throws Exception {
        TeamBootstrap bootstrap = team.bootstrap(TENANT, "Acme", "key-a", "alice", "Alice", Duration.ofHours(1), "req-1");
        assertFalse(bootstrap.toString().contains(bootstrap.bearerToken()));
        assertTrue(bootstrap.toString().contains("bearerToken=***"));
        TeamKeyRotation rotation = team.rotateKey(bootstrap.bearerToken(), "req-2", "key-b", Duration.ofHours(1));
        assertFalse(rotation.toString().contains(rotation.replacementBearerToken()));
        assertTrue(rotation.toString().contains("replacementBearerToken=***"));
    }

    @Test
    void refusesANullService() {
        assertThrows(NullPointerException.class, () -> new TeamControlPlane(null));
    }

    private String ownerToken() throws Exception {
        return team.bootstrap(TENANT, "Acme", "key-a", "alice", "Alice", Duration.ofHours(1), "req-1").bearerToken();
    }

    /** In-memory persistence port: the facade tests need no storage adapter. */
    private static final class MemoryStore implements HostedControlPlaneStore {
        private final Map<UUID, HostedTenantState> states = new HashMap<>();

        @Override public void create(HostedTenantState state) { states.put(state.tenantId(), state); }

        @Override public Optional<HostedTenantState> find(UUID tenantId) { return Optional.ofNullable(states.get(tenantId)); }

        @Override public void save(HostedTenantState state, long expectedVersion) { states.put(state.tenantId(), state); }
    }
}
