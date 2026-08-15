package com.minos.hosted;

import com.minos.io.CommitUncertainException;

import java.io.IOException;
import java.util.Objects;

/** Re-observes the durable tenant authority after a lost local durability acknowledgement. */
final class HostedCommitRecovery {
    private HostedCommitRecovery() {
    }

    static void create(HostedControlPlaneStore store, HostedTenantState expected) throws IOException {
        try {
            store.create(expected);
        } catch (CommitUncertainException uncertain) {
            recoverExact(store, expected, uncertain);
        }
    }

    static void save(HostedControlPlaneStore store, HostedTenantState expected, long previousVersion) throws IOException {
        try {
            store.save(expected, previousVersion);
        } catch (CommitUncertainException uncertain) {
            recoverExact(store, expected, uncertain);
        }
    }

    private static void recoverExact(
            HostedControlPlaneStore store,
            HostedTenantState expected,
            CommitUncertainException uncertain
    ) throws IOException {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(expected, "expected");
        try {
            HostedTenantState observed = store.find(expected.tenantId()).orElse(null);
            if (expected.equals(observed)) return;
        } catch (IOException | RuntimeException observationFailure) {
            uncertain.addSuppressed(observationFailure);
        }
        throw uncertain;
    }
}
