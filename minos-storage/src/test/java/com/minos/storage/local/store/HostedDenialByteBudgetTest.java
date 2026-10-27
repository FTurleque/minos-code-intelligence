package com.minos.storage.local.store;

import com.minos.hosted.HmacHostedIdentityProvider;
import com.minos.hosted.HostedAuditEvent;
import com.minos.hosted.HostedAuditSink;
import com.minos.hosted.HostedControlPlaneService;
import com.minos.hosted.HostedRetentionPolicy;
import com.minos.hosted.HostedRole;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.hosted.HostedTenantState;
import com.minos.testsupport.DerivedTenantKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B02 : les refus chaînés sont bornés en octets, de sorte qu'un membre sans droit ne puisse jamais
 * empêcher les mutations autorisées en saturant la limite du magasin chiffré, et un refus hors budget reste
 * appliqué, journalisé et livré en non chaîné sans modifier l'état persistant du tenant.
 */
class HostedDenialByteBudgetTest {
    private static final long LOW_LIMIT_BYTES = 64L * 1024L;
    /** Enough 7-second-apart refusals to overflow the whole limit if refusals were not byte-bounded. */
    private static final int BURST = 400;

    @Test
    void aViewerCannotExhaustTheByteLimitAndBlockAuthorizedMutations(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);

        for (int index = 0; index < BURST; index++) {
            fixture.clock.advance(Duration.ofSeconds(7));
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class,
                    () -> fixture.service.revokeMember(fixture.viewer, requestId, "a".repeat(4000)),
                    "refusal " + index + " must stay an authorization refusal");
        }

        fixture.clock.advance(Duration.ofSeconds(7));
        assertDoesNotThrow(() -> fixture.service.grantMember(fixture.owner, "grant-after-attack", "colleague",
                "Colleague", HostedRole.VIEWER), "an authorized mutation must still succeed after the refusal burst");
        long chainedDenials = fixture.service.audit(fixture.owner, 10_000).stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED).count();
        assertTrue(chainedDenials > 0, "refusals are chained while the byte budget lasts");
        assertTrue(chainedDenials < BURST, "the byte budget stops chaining before the burst ends");
    }

    @Test
    void aRefusalBeyondTheByteBudgetIsDeliveredUnchainedAndLeavesThePersistedTenantUntouched(
            @TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        int unchainedBefore;
        int index = 0;
        do {
            fixture.clock.advance(Duration.ofSeconds(7));
            unchainedBefore = fixture.sink.unchained.size();
            String requestId = "denied-" + index++;
            assertThrows(SecurityException.class,
                    () -> fixture.service.revokeMember(fixture.viewer, requestId, "a".repeat(4000)));
        } while (fixture.sink.unchained.size() == unchainedBefore && index < BURST);
        assertTrue(fixture.sink.unchained.size() > unchainedBefore, "the byte budget must eventually stop chaining");

        HostedTenantState persisted = fixture.store.find(fixture.tenant).orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(7));
        assertThrows(SecurityException.class,
                () -> fixture.service.revokeMember(fixture.viewer, "denied-beyond-budget", "a".repeat(4000)));

        assertEquals(persisted, fixture.store.find(fixture.tenant).orElseThrow(),
                "version and chain must not change once refusals are no longer chained");
        HostedAuditEvent trace = fixture.sink.unchained.getLast();
        assertEquals(HostedAuditEvent.Chaining.UNCHAINED, trace.chaining());
        assertEquals("denied-beyond-budget", trace.requestId());
        assertFalse(trace.resourceId().contains("aaaa"), "the raw caller value is never recorded");
    }

    @Test
    void retentionStaysPossibleAfterRefusalsHaveConsumedTheirByteBudget(@TempDir Path root) throws Exception {
        Fixture fixture = fixture(root);
        for (int index = 0; index < BURST; index++) {
            fixture.clock.advance(Duration.ofSeconds(7));
            String requestId = "denied-" + index;
            assertThrows(SecurityException.class,
                    () -> fixture.service.revokeMember(fixture.viewer, requestId, "a".repeat(4000)));
        }

        fixture.clock.advance(Duration.ofSeconds(7));
        assertDoesNotThrow(() -> fixture.service.setRetention(
                fixture.owner, "retention-set", new HostedRetentionPolicy(100, 365, 90)));
        fixture.clock.advance(Duration.ofSeconds(7));
        assertDoesNotThrow(() -> fixture.service.applyRetention(fixture.owner, "retention-apply"));
    }

    private static Fixture fixture(Path root) throws Exception {
        HostedTenantKeyProvider keys = DerivedTenantKeys.provider();
        FileHostedControlPlaneStore store = new FileHostedControlPlaneStore(
                root, keys, LOW_LIMIT_BYTES, new SecureRandom());
        AdvancingClock clock = new AdvancingClock(Instant.parse("2026-07-29T09:00:00Z"));
        RecordingSink sink = new RecordingSink();
        HostedControlPlaneService service = new HostedControlPlaneService(
                store, new HmacHostedIdentityProvider(keys), keys, (project, snapshot) -> { }, sink, clock);
        UUID tenant = UUID.randomUUID();
        String owner = service.bootstrap(
                tenant, "Team", "primary", "owner", "Owner", Duration.ofHours(1), "bootstrap-1").bearerToken();
        service.grantMember(owner, "grant-viewer", "viewer", "Viewer", HostedRole.VIEWER);
        String viewer = service.issueToken(owner, "token-viewer", "viewer", Duration.ofHours(1));
        return new Fixture(tenant, store, service, clock, sink, owner, viewer);
    }

    private record Fixture(
            UUID tenant,
            FileHostedControlPlaneStore store,
            HostedControlPlaneService service,
            AdvancingClock clock,
            RecordingSink sink,
            String owner,
            String viewer
    ) {
    }

    private static final class RecordingSink implements HostedAuditSink {
        private final List<HostedAuditEvent> unchained = new ArrayList<>();

        @Override
        public void publish(HostedAuditEvent event) throws IOException {
            // chained events are read back from the tenant audit chain
        }

        @Override
        public void publishUnchained(HostedAuditEvent event) {
            unchained.add(event);
        }
    }

    /** A clock that only moves when the test moves it, so that the per-principal refusal budget stays satisfied. */
    private static final class AdvancingClock extends Clock {
        private Instant instant;

        private AdvancingClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
