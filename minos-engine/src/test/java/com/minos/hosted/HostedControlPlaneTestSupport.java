package com.minos.hosted;

import com.minos.testsupport.DerivedTenantKeys;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Shared in-memory harness for hosted control-plane security tests. */
final class HostedControlPlaneTestSupport {
    static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    private HostedControlPlaneTestSupport() {
    }

    /**
     * Extends the persisted chain with authentic filler events of the given outcome up to {@code size}
     * events, using a one-event window so that the build stays O(n) even at the hard capacity.
     */
    static HostedTenantState extendedTo(
            HostedTenantState state, HostedAuditChain chain, int size, HostedAuditEvent.Outcome outcome) {
        List<HostedAuditEvent> events = new ArrayList<>(state.auditEvents());
        HostedTenantState window = state;
        while (events.size() < size) {
            HostedAuditEvent last = window.auditEvents().getLast();
            HostedTenantState oneEvent = new HostedTenantState(
                    state.tenantId(), state.name(), state.keyId(), state.version(), state.createdAt(),
                    window.updatedAt(), state.retentionPolicy(), state.members(), state.workspaces(),
                    window.auditSequence(), last.previousHash(), List.of(last));
            window = chain.append(oneEvent, "owner", "AUDIT_FILL", "TENANT", "fill",
                    outcome, "fill-" + events.size(), state.keyId(), state.version());
            events.add(window.auditEvents().getLast());
        }
        return new HostedTenantState(
                state.tenantId(), state.name(), state.keyId(), state.version(), state.createdAt(),
                window.updatedAt(), state.retentionPolicy(), state.members(), state.workspaces(),
                events.getLast().sequence(), state.auditAnchorHash(), events);
    }

    static Harness harness() {
        InMemoryStore store = new InMemoryStore();
        HostedTenantKeyProvider keys = DerivedTenantKeys.provider();
        MutableClock clock = new MutableClock(NOW);
        RecordingSink sink = new RecordingSink();
        HostedBindingVerifier verifier = (project, snapshot) -> { };
        HostedControlPlaneService service = new HostedControlPlaneService(
                store, new HmacHostedIdentityProvider(keys), keys, verifier, sink, clock);
        return new Harness(UUID.randomUUID(), store, keys, clock, sink, service);
    }

    record Harness(
            UUID tenant,
            InMemoryStore store,
            HostedTenantKeyProvider keys,
            MutableClock clock,
            RecordingSink sink,
            HostedControlPlaneService service
    ) {
        /** Bootstraps the tenant with principal {@code owner} and returns the owner bearer token. */
        String bootstrapOwner() throws IOException {
            return service.bootstrap(
                    tenant, "Team", "primary", "owner", "Owner", Duration.ofHours(1), "bootstrap-1")
                    .bearerToken();
        }

        HostedTenantState state() {
            return store.values.get(tenant);
        }

        /** Grants {@code principalId} with {@code role} using {@code granter} and returns a token for it. */
        String grantAndIssue(String granter, String principalId, HostedRole role) throws IOException {
            service.grantMember(granter, "grant-" + principalId, principalId, principalId, role);
            return service.issueToken(granter, "token-" + principalId, principalId, Duration.ofHours(1));
        }

        /** A second service over the same store, keys, clock and sink: simulates another MINOS process. */
        HostedControlPlaneService anotherProcess() {
            return new HostedControlPlaneService(
                    store, new HmacHostedIdentityProvider(keys), keys, (project, snapshot) -> { }, sink, clock);
        }
    }

    static final class RecordingSink implements HostedAuditSink {
        /** Events published after a durable commit (chained). */
        final List<HostedAuditEvent> events = new ArrayList<>();
        /** Refusals delivered without being chained. */
        final List<HostedAuditEvent> unchained = new ArrayList<>();

        @Override
        public void publish(HostedAuditEvent event) {
            events.add(event);
        }

        @Override
        public void publishUnchained(HostedAuditEvent event) {
            unchained.add(event);
        }
    }

    static final class InMemoryStore implements HostedControlPlaneStore {
        final Map<UUID, HostedTenantState> values = new HashMap<>();

        @Override
        public void create(HostedTenantState state) throws IOException {
            if (values.putIfAbsent(state.tenantId(), state) != null) {
                throw new IOException("exists");
            }
        }

        @Override
        public Optional<HostedTenantState> find(UUID tenantId) {
            return Optional.ofNullable(values.get(tenantId));
        }

        @Override
        public void save(HostedTenantState state, long expectedVersion) throws IOException {
            HostedTenantState current = values.get(state.tenantId());
            if (current == null || current.version() != expectedVersion) {
                throw new IOException("concurrent modification");
            }
            values.put(state.tenantId(), state);
        }
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
