package com.minos.hosted;

import java.io.IOException;
import java.util.Objects;

/**
 * Post-commit delivery policy for external hosted audit sinks.
 *
 * <p>The authenticated audit chain persisted inside {@link HostedTenantState} is the durable source
 * of truth. Once a tenant mutation is committed, an external export failure must not turn that
 * committed mutation into an apparent caller failure: doing so can make retries unsafe and, for key
 * rotation/bootstrap, can strand the caller without a usable credential. External sinks can replay
 * the durable tenant audit chain after recovery.</p>
 */
final class HostedAuditDelivery {

    private static final System.Logger LOGGER = System.getLogger(HostedAuditDelivery.class.getName());

    private HostedAuditDelivery() {
    }

    static void publishAfterCommit(HostedAuditSink sink, HostedAuditEvent event) {
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(event, "event");
        try {
            sink.publish(event);
        } catch (IOException exception) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "Hosted audit export failed after durable commit; event remains replayable from the tenant audit chain"
                            + " (tenant=" + event.tenantId() + ", sequence=" + event.sequence() + ")",
                    exception);
        }
    }
}
