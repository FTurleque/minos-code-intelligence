package com.minos.hosted;

/**
 * Operator transport/TLS boundary for a future network-served hosted deployment.
 *
 * <p>The embedded local-first control plane has no network listener and therefore does not provide
 * or claim this port. A transport adapter must fail closed until peer authentication and TLS policy
 * have been qualified.</p>
 */
public interface HostedTransportSecurityPort {

    void requireQualifiedTransport();
}
