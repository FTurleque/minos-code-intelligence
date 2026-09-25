package com.minos.hosted;

import java.io.IOException;

/** Durable/operational audit export port invoked only after the tenant state mutation is persisted. */
@FunctionalInterface
public interface HostedAuditSink {

    void publish(HostedAuditEvent event) throws IOException;

    /**
     * Receives a refusal that was authenticated with the tenant key but deliberately not appended
     * to the durable audit chain (chained-refusal capacity reserve or per-principal refusal budget
     * exhausted). Such an event is not replayable from the tenant state: its sequence is the next
     * sequence the chain would have used and may later be reused by a chained event. Sinks that
     * must distinguish both kinds override this method; by default it is delivered like any event.
     */
    default void publishUnchained(HostedAuditEvent event) throws IOException {
        publish(event);
    }

    static HostedAuditSink embeddedNoop() {
        return event -> { };
    }
}
