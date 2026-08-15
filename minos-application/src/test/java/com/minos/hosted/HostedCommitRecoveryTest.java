package com.minos.hosted;

import com.minos.io.CommitUncertainException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HostedCommitRecoveryTest {
    private static final Instant NOW = Instant.parse("2026-08-15T00:00:00Z");

    @Test
    void recoversCreateWhenTheExactTenantStateIsVisibleAfterLostAcknowledgement() {
        HostedTenantState expected = state(UUID.randomUUID(), 0);
        UncertainStore store = new UncertainStore(true);

        assertDoesNotThrow(() -> HostedCommitRecovery.create(store, expected));
        assertEquals(expected, store.find(expected.tenantId()).orElseThrow());
    }

    @Test
    void remainsFailClosedWhenUncertainCommitCannotBeReobserved() {
        HostedTenantState expected = state(UUID.randomUUID(), 0);
        UncertainStore store = new UncertainStore(false);

        assertThrows(CommitUncertainException.class, () -> HostedCommitRecovery.create(store, expected));
    }

    private static HostedTenantState state(UUID tenantId, long version) {
        HostedPrincipal owner = new HostedPrincipal("owner", "Owner", HostedRole.OWNER, NOW);
        return new HostedTenantState(
                tenantId, "Tenant", "primary", version, NOW, NOW,
                HostedRetentionPolicy.defaults(), List.of(owner), List.of(), 0,
                HostedAuditEvent.GENESIS_HASH, List.of());
    }

    private static final class UncertainStore implements HostedControlPlaneStore {
        private final boolean exposeCommit;
        private HostedTenantState state;

        private UncertainStore(boolean exposeCommit) {
            this.exposeCommit = exposeCommit;
        }

        @Override
        public void create(HostedTenantState next) throws IOException {
            if (exposeCommit) state = next;
            throw new CommitUncertainException("lost durability acknowledgement");
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) {
            return state != null && state.tenantId().equals(tenantId) ? Optional.of(state) : Optional.empty();
        }

        @Override
        public void save(HostedTenantState next, long expectedVersion) throws IOException {
            if (exposeCommit) state = next;
            throw new CommitUncertainException("lost durability acknowledgement");
        }
    }
}
