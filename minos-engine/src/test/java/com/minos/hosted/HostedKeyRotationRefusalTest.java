package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-B04 : une mutation présentée avec un jeton signé par une clé retirée est un échec d'authentification,
 * identique à celui d'une lecture, et jamais un refus RBAC chaîné sous un nom que le jeton prétend porter.
 */
class HostedKeyRotationRefusalTest {

    @Test
    void aMutationWithATokenOfARetiredKeyIsAnAuthenticationFailureWithoutAnyChainedEvent() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String oldToken = harness.bootstrapOwner();
        harness.service().rotateKey(oldToken, "rotate-1", "rotated", Duration.ofHours(1));
        HostedTenantState before = harness.state();

        SecurityException failure = assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(oldToken, "after-rotation", "Shared"));

        assertTrue(failure.getMessage().contains("inactive key"), failure.getMessage());
        assertEquals(before, harness.state(), "no event and no version bump for an unauthenticated mutation");
    }

    @Test
    void aMutationAndAReadWithARetiredKeyFailWithTheSameMessage() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String oldToken = harness.bootstrapOwner();
        harness.service().rotateKey(oldToken, "rotate-1", "rotated", Duration.ofHours(1));

        SecurityException read = assertThrows(SecurityException.class, () -> harness.service().listWorkspaces(oldToken));
        SecurityException mutation = assertThrows(SecurityException.class,
                () -> harness.service().createWorkspace(oldToken, "after-rotation", "Shared"));

        assertEquals(read.getMessage(), mutation.getMessage());
    }

    @Test
    void fortyTokensForgedWithTheRetiredKeyNeverReachTheChainNorNameAnyPrincipalThere() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String oldToken = harness.bootstrapOwner();
        harness.service().rotateKey(oldToken, "rotate-1", "rotated", Duration.ofHours(1));
        HostedTenantState before = harness.state();
        HmacHostedIdentityProvider identities = new HmacHostedIdentityProvider(harness.keys());

        for (int index = 0; index < 40; index++) {
            String forged = identities.issue(
                    harness.tenant(), "ghost" + index, "primary", harness.clock().instant(),
                    Duration.ofHours(1), "forged-" + index);
            String requestId = "forged-request-" + index;
            assertThrows(SecurityException.class, () -> harness.service().createWorkspace(forged, requestId, "X"));
        }

        assertEquals(before, harness.state());
        assertFalse(harness.state().auditEvents().stream().anyMatch(event -> event.principalId().startsWith("ghost")));
        assertTrue(harness.sink().unchained.isEmpty(), "an unauthenticated mutation is not an audited refusal either");
    }

    @Test
    void theJournalOfRetiredKeyTokensIsBoundedAndNamesNeitherTheTokenNorAPrincipal() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String oldToken = harness.bootstrapOwner();
        harness.service().rotateKey(oldToken, "rotate-1", "rotated", Duration.ofHours(1));
        HmacHostedIdentityProvider identities = new HmacHostedIdentityProvider(harness.keys());
        List<String> forgedTokens = new ArrayList<>();
        for (int index = 0; index < 40; index++) {
            forgedTokens.add(identities.issue(
                    harness.tenant(), "ghost" + index, "primary", harness.clock().instant(),
                    Duration.ofHours(1), "forged-" + index));
        }

        List<LogRecord> records = capture(() -> {
            for (String forged : forgedTokens) {
                assertThrows(SecurityException.class, () -> harness.service().createWorkspace(forged, "forged", "X"));
            }
        });

        List<LogRecord> journal = records.stream()
                .filter(record -> record.getLevel() == Level.WARNING && record.getMessage().contains("inactive key"))
                .toList();
        assertFalse(journal.isEmpty(), "retired-key refusals leave a journal trace");
        assertTrue(journal.size() <= HostedDenialThrottle.DEFAULT_MAX_PER_WINDOW,
                "journal entries per window are bounded: " + journal.size());
        for (LogRecord record : journal) {
            assertTrue(record.getMessage().contains("keyId=primary"));
            assertFalse(record.getMessage().contains("ghost"));
            assertTrue(forgedTokens.stream().noneMatch(token -> record.getMessage().contains(token)));
        }
    }

    @Test
    void aMemberWithoutRightsHoldingATokenOfTheActiveKeyStillGetsAChainedRbacRefusal() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String owner = harness.bootstrapOwner();
        String viewer = harness.grantAndIssue(owner, "viewer", HostedRole.VIEWER);
        int before = harness.state().auditEvents().size();

        assertThrows(SecurityException.class, () -> harness.service().createWorkspace(viewer, "denied-1", "X"));

        assertEquals(before + 1, harness.state().auditEvents().size());
        HostedAuditEvent denial = harness.state().auditEvents().getLast();
        assertEquals(HostedAuditEvent.Outcome.DENIED, denial.outcome());
        assertEquals("viewer", denial.principalId());
    }

    @Test
    void eventsAuthenticatedBeforeTheRotationStayVerifiableWithTheRetiredKey() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        String oldToken = harness.bootstrapOwner();
        harness.service().createWorkspace(oldToken, "before-rotation", "Shared");
        var rotated = harness.service().rotateKey(oldToken, "rotate-1", "rotated", Duration.ofHours(1));

        assertDoesNotThrow(() -> harness.service().tenant(rotated.replacementBearerToken()));
        assertDoesNotThrow(() -> new HostedAuditChain(harness.keys(), Clock.systemUTC()).verify(harness.state()));
        assertTrue(harness.state().auditEvents().stream().anyMatch(event -> event.keyId().equals("primary")));
        assertDoesNotThrow(() -> harness.service().createWorkspace(
                rotated.replacementBearerToken(), "after-rotation", "Shared 2"));
    }

    private static List<LogRecord> capture(Action action) throws Exception {
        Logger logger = Logger.getLogger(HostedAuthorizationService.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }
}
