package com.minos.hosted;

import java.io.IOException;
import java.util.UUID;

/** Verifies that a shared knowledge binding targets a real immutable project snapshot. */
public interface HostedBindingVerifier {
    void requireSnapshot(UUID projectId, String snapshotId) throws IOException;
}
