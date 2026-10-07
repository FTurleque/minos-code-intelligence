package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B02 / B03 : le champ « identifiant de ressource » d'un refus est ramené à une forme bornée et
 * déterministe avant l'audit ; la valeur brute fournie par l'appelant n'est jamais enregistrée.
 */
class HostedRefusalResourceIdTest {

    @Test
    void anOversizedIdentifierBecomesABoundedDeterministicStandIn() {
        String raw = "a".repeat(5000);

        String first = HostedAuthorizationService.refusalResourceId(raw);

        assertTrue(first.length() <= 128, "bounded to the identifier limit");
        assertTrue(first.startsWith(HostedAuthorizationService.INVALID_RESOURCE_PREFIX));
        assertEquals(first, HostedAuthorizationService.refusalResourceId(raw), "deterministic");
        assertFalse(first.contains("aaaa"), "the raw value is not recorded");
        assertDoesNotThrowAsSafeId(first);
    }

    @Test
    void controlCharactersInsideTheIdentifierNeverReachTheAudit() {
        for (String raw : List.of("bad\0id", "bad\nid", "bad\tid", "bad\rid", "bad id", "-leading-dash")) {
            String recorded = HostedAuthorizationService.refusalResourceId(raw);

            assertTrue(recorded.startsWith(HostedAuthorizationService.INVALID_RESOURCE_PREFIX), raw);
            assertFalse(recorded.contains("bad"), "the raw value is not recorded for " + raw);
            assertDoesNotThrowAsSafeId(recorded);
        }
    }

    @Test
    void missingOrBlankIdentifiersAreReplacedToo() {
        assertTrue(HostedAuthorizationService.refusalResourceId(null).startsWith("invalid:"));
        assertTrue(HostedAuthorizationService.refusalResourceId("").startsWith("invalid:"));
        assertTrue(HostedAuthorizationService.refusalResourceId("   ").startsWith("invalid:"));
    }

    @Test
    void aSafeIdentifierIsKeptAfterTrimmingItsEdges() {
        assertEquals("ghost", HostedAuthorizationService.refusalResourceId("ghost"));
        assertEquals("ghost", HostedAuthorizationService.refusalResourceId("  ghost\t"));
        assertEquals("a".repeat(128), HostedAuthorizationService.refusalResourceId("a".repeat(128)));
        assertTrue(HostedAuthorizationService.refusalResourceId("a".repeat(129)).startsWith("invalid:"));
    }

    @Test
    void twoDifferentInvalidValuesStayDistinguishable() {
        assertNotEquals(
                HostedAuthorizationService.refusalResourceId("bad\0one"),
                HostedAuthorizationService.refusalResourceId("bad\0two"));
    }

    @Test
    void aRefusedCallerCannotMakeTheRecordedRefusalGrowWithItsInput() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        String raw = "z".repeat(4000);

        List<LogRecord> journal = capture(() ->
                assertThrows(SecurityException.class, () -> harness.service().revokeMember(viewer, "r1", raw)));

        HostedAuditEvent denial = harness.state().auditEvents().getLast();
        assertEquals(HostedAuditEvent.Outcome.DENIED, denial.outcome());
        assertTrue(denial.resourceId().length() <= 128);
        assertFalse(denial.resourceId().contains("zzzz"));
        assertTrue(denial.estimatedEncodedBytes() < 1024, "an event is about a few hundred bytes at most");
        assertTrue(journal.stream().noneMatch(record -> record.getMessage().contains("zzzz")),
                "the raw value is not journaled either");
    }

    private static void assertDoesNotThrowAsSafeId(String value) {
        assertEquals(value, HostedPrincipal.safeId(value, "resourceId"));
    }

    private static List<LogRecord> capture(Action action) throws Exception {
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        List<Logger> loggers = List.of(
                Logger.getLogger(HostedAuthorizationService.class.getName()),
                Logger.getLogger(HostedAuditDelivery.class.getName()));
        loggers.forEach(logger -> {
            logger.addHandler(handler);
            logger.setLevel(Level.ALL);
        });
        try {
            action.run();
        } finally {
            loggers.forEach(logger -> logger.removeHandler(handler));
        }
        return records;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
