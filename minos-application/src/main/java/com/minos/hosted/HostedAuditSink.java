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
     * sequence the chain would have used and will be reused by the next chained event, so it must
     * never travel through {@link #publish} where it would collide with a committed event. The
     * control plane journals every unchained refusal before calling this method; by default the
     * event is only reported as not exported. Sinks that export it override this method.
     */
    default void publishUnchained(HostedAuditEvent event) throws IOException {
        System.getLogger(HostedAuditSink.class.getName()).log(
                System.Logger.Level.WARNING,
                "Hosted audit sink does not export unchained refusals; the refusal is traced by the journal only"
                        + " (tenant=" + event.tenantId() + ", principal=" + event.principalId()
                        + ", action=" + event.action() + ", requestId=" + event.requestId() + ")");
    }

    /**
     * Sink of the embedded, local-first control plane: nothing is exported. Chained events remain
     * replayable from the tenant audit chain; the trace of unchained refusals then relies solely on
     * the WARNING journal written by the control plane before delivery.
     */
    static HostedAuditSink embeddedNoop() {
        return new HostedAuditSink() {
            @Override public void publish(HostedAuditEvent event) { }
            @Override public void publishUnchained(HostedAuditEvent event) { }
        };
    }
}
