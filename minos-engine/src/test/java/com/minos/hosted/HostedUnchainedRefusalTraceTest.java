package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** S2 (V13/V15): a refusal kept off the chain always leaves a journal trace and never masquerades as a chained event. */
class HostedUnchainedRefusalTraceTest {
    private static final int CHAINED_REFUSALS_PER_WINDOW = 10;

    @Test
    void unchainedRefusalIsJournaledEvenWithTheEmbeddedNoopSink() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        HostedControlPlaneService production = new HostedControlPlaneService(
                harness.store(), new HmacHostedIdentityProvider(harness.keys()), harness.keys(),
                (project, snapshot) -> { }, harness.clock());
        String owner = production.bootstrap(
                harness.tenant(), "Team", "primary", "owner", "Owner", Duration.ofHours(1), "bootstrap-1")
                .bearerToken();
        production.grantMember(owner, "grant-viewer", "viewer", "Viewer", HostedRole.VIEWER);
        String viewer = production.issueToken(owner, "token-viewer", "viewer", Duration.ofHours(1));

        List<LogRecord> records = capture(HostedAuditDelivery.class.getName(), () -> {
            for (int index = 0; index <= CHAINED_REFUSALS_PER_WINDOW; index++) {
                String requestId = "denied-" + index;
                assertThrows(SecurityException.class,
                        () -> production.createWorkspace(viewer, requestId, "Forbidden"));
            }
        });

        List<LogRecord> traces = records.stream()
                .filter(record -> record.getLevel() == Level.WARNING && record.getMessage().contains("denied-"))
                .toList();
        assertEquals(1, traces.size(), "exactly the unchained refusal is journaled");
        String message = traces.getFirst().getMessage();
        assertTrue(message.contains(harness.tenant().toString()));
        assertTrue(message.contains("principal=viewer"));
        assertTrue(message.contains("action=WORKSPACE_CREATE"));
        assertTrue(message.contains("resourceType=WORKSPACE"));
        assertTrue(message.contains("requestId=denied-" + CHAINED_REFUSALS_PER_WINDOW));
        assertFalse(message.contains(viewer));
        assertFalse(message.contains(owner));
    }

    @Test
    void nonAdaptedSinkNeverReceivesUnchainedRefusalsThroughPublish() throws Exception {
        var harness = HostedControlPlaneTestSupport.harness();
        List<HostedAuditEvent> published = new ArrayList<>();
        HostedAuditSink legacySink = published::add;
        HostedControlPlaneService service = new HostedControlPlaneService(
                harness.store(), new HmacHostedIdentityProvider(harness.keys()), harness.keys(),
                (project, snapshot) -> { }, legacySink, harness.clock());
        String owner = service.bootstrap(
                harness.tenant(), "Team", "primary", "owner", "Owner", Duration.ofHours(1), "bootstrap-1")
                .bearerToken();
        service.grantMember(owner, "grant-viewer", "viewer", "Viewer", HostedRole.VIEWER);
        String viewer = service.issueToken(owner, "token-viewer", "viewer", Duration.ofHours(1));

        List<LogRecord> records = capture(HostedAuditSink.class.getName(), () -> {
            for (int index = 0; index <= CHAINED_REFUSALS_PER_WINDOW; index++) {
                String requestId = "denied-" + index;
                assertThrows(SecurityException.class,
                        () -> service.createWorkspace(viewer, requestId, "Forbidden"));
            }
        });
        service.createWorkspace(owner, "allowed-1", "Shared");

        long denied = published.stream()
                .filter(event -> event.outcome() == HostedAuditEvent.Outcome.DENIED).count();
        assertEquals(CHAINED_REFUSALS_PER_WINDOW, denied);
        assertEquals(published.size(), published.stream().map(HostedAuditEvent::sequence).distinct().count());
        assertEquals(harness.state().auditEvents(), published);
        assertEquals(1, records.stream().filter(record -> record.getLevel() == Level.WARNING).count());
    }

    private static List<LogRecord> capture(String loggerName, Action action) throws Exception {
        Logger logger = Logger.getLogger(loggerName);
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
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
