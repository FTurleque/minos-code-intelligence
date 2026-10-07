package com.minos.hosted;

import com.minos.io.CommitUncertainException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B03 : un refus lève toujours un refus d'autorisation et laisse toujours une trace (chaînée ou, à
 * défaut, non chaînée), même quand l'identifiant fourni est invalide ou que la persistance du refus échoue.
 */
class HostedRefusalTraceTest {

    @Test
    void aRefusalWithAnOversizedResourceIdIsStillAnAuthorizationFailureAndLeavesATrace() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        int before = harness.state().auditEvents().size();

        assertThrows(SecurityException.class,
                () -> harness.service().revokeMember(viewer, "r3", "a".repeat(5000)));

        boolean traced = harness.state().auditEvents().size() > before || !harness.sink().unchained.isEmpty();
        assertTrue(traced, "the refusal must leave a chained or an unchained trace");
    }

    @Test
    void aVersionConflictWhileSavingTheRefusalStillRefusesAndLeavesAnUnchainedTrace() throws Exception {
        assertSaveFailureIsStillATracedRefusal(() -> new IOException("hosted tenant concurrent modification"));
    }

    @Test
    void anIoFailureWhileSavingTheRefusalStillRefusesAndLeavesAnUnchainedTrace() throws Exception {
        assertSaveFailureIsStillATracedRefusal(() -> new IOException("disk full"));
    }

    @Test
    void anUnresolvedCommitUncertaintyWhileSavingTheRefusalStillRefusesAndLeavesATrace() throws Exception {
        assertSaveFailureIsStillATracedRefusal(() -> new CommitUncertainException("durability not acknowledged"));
    }

    @Test
    void aRealConcurrentWriterBetweenLoadAndSaveStillYieldsATracedRefusal() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        FailingSaveStore store = new FailingSaveStore(harness.store());
        HostedControlPlaneService service = serviceOver(store, harness);
        HostedControlPlaneService otherProcess = harness.anotherProcess();
        store.beforeSave = () -> {
            store.beforeSave = null;
            try {
                otherProcess.createWorkspace(owner, "concurrent-write", "Concurrent");
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        };

        assertThrows(SecurityException.class, () -> service.createWorkspace(viewer, "denied-1", "Forbidden"));

        assertEquals(1, harness.sink().unchained.size(), "the refusal must be delivered unchained");
        assertTrue(harness.state().auditEvents().stream()
                        .anyMatch(event -> event.action().equals("WORKSPACE_CREATE")
                                && event.outcome() == HostedAuditEvent.Outcome.ALLOWED),
                "the concurrent authorized mutation must have been kept");
    }

    private static void assertSaveFailureIsStillATracedRefusal(Supplier<IOException> failure) throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        FailingSaveStore store = new FailingSaveStore(harness.store());
        HostedControlPlaneService service = serviceOver(store, harness);
        long versionBefore = harness.state().version();
        store.failure = failure;

        assertThrows(SecurityException.class, () -> service.createWorkspace(viewer, "denied-1", "Forbidden"));

        assertEquals(1, harness.sink().unchained.size(), "the refusal must leave an unchained trace");
        assertEquals(HostedAuditEvent.Outcome.DENIED, harness.sink().unchained.getFirst().outcome());
        assertEquals(versionBefore, harness.state().version(), "the failed refusal write must not change the tenant");
    }

    private static HostedControlPlaneService serviceOver(
            HostedControlPlaneStore store, HostedControlPlaneTestSupport.Harness harness) {
        return new HostedControlPlaneService(
                store, new HmacHostedIdentityProvider(harness.keys()), harness.keys(),
                (project, snapshot) -> { }, harness.sink(), harness.clock());
    }

    /** Delegates to the in-memory store but fails (or first runs a hook before) every {@code save}. */
    private static final class FailingSaveStore implements HostedControlPlaneStore {
        private final HostedControlPlaneStore delegate;
        private volatile Supplier<IOException> failure;
        private volatile Runnable beforeSave;

        private FailingSaveStore(HostedControlPlaneStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public void create(HostedTenantState state) throws IOException {
            delegate.create(state);
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) throws IOException {
            return delegate.find(tenantId);
        }

        @Override
        public void save(HostedTenantState state, long expectedVersion) throws IOException {
            Runnable hook = beforeSave;
            if (hook != null) {
                hook.run();
            }
            Supplier<IOException> current = failure;
            if (current != null) {
                throw current.get();
            }
            delegate.save(state, expectedVersion);
        }
    }
}
