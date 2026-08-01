package com.minos.hosted;

import java.util.List;
import java.util.Objects;

/** Explicit claim boundary for the embedded hosted control plane and future operated adapters. */
public record HostedProductionBoundary(
        Mode mode,
        PortDisposition identity,
        PortDisposition keyManagement,
        PortDisposition transportTls,
        PortDisposition backupAvailability,
        PortDisposition auditSink,
        List<String> limitations
) {
    public HostedProductionBoundary {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(keyManagement, "keyManagement");
        Objects.requireNonNull(transportTls, "transportTls");
        Objects.requireNonNull(backupAvailability, "backupAvailability");
        Objects.requireNonNull(auditSink, "auditSink");
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
        if (mode == Mode.EMBEDDED_LOCAL_FIRST
                && (transportTls == PortDisposition.QUALIFIED_OPERATED
                || backupAvailability == PortDisposition.QUALIFIED_OPERATED)) {
            throw new IllegalArgumentException("embedded hosted mode cannot claim operated transport or availability");
        }
    }

    public static HostedProductionBoundary embeddedLocalFirst(boolean externalAuditSink) {
        return new HostedProductionBoundary(
                Mode.EMBEDDED_LOCAL_FIRST,
                PortDisposition.EMBEDDED_REFERENCE,
                PortDisposition.EMBEDDED_REFERENCE,
                PortDisposition.NOT_PROVIDED,
                PortDisposition.NOT_PROVIDED,
                externalAuditSink ? PortDisposition.OPERATOR_ADAPTER : PortDisposition.EMBEDDED_NOOP,
                List.of(
                        "HOSTED_NETWORK_TRANSPORT_NOT_PROVIDED",
                        "HOSTED_BACKUP_AVAILABILITY_NOT_PROVIDED",
                        "HOSTED_SAAS_OPERATION_NOT_CLAIMED",
                        "HOSTED_PROCESS_ISOLATION_NOT_QUALIFIED"));
    }

    public enum Mode {
        EMBEDDED_LOCAL_FIRST,
        OPERATED_ADAPTER
    }

    public enum PortDisposition {
        EMBEDDED_REFERENCE,
        EMBEDDED_NOOP,
        OPERATOR_ADAPTER,
        QUALIFIED_OPERATED,
        NOT_PROVIDED
    }
}
