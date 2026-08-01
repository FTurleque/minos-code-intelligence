package com.minos.hosted;

import java.io.IOException;

/** Durable/operational audit export port invoked only after the tenant state mutation is persisted. */
@FunctionalInterface
public interface HostedAuditSink {

    void publish(HostedAuditEvent event) throws IOException;

    static HostedAuditSink embeddedNoop() {
        return event -> { };
    }
}
