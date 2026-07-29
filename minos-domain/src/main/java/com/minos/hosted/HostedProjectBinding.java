package com.minos.hosted;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Reproducible shared-knowledge binding to one exact project snapshot. */
public record HostedProjectBinding(UUID projectId, String snapshotId, Instant boundAt, String boundBy) {
    public HostedProjectBinding {
        Objects.requireNonNull(projectId, "projectId");
        snapshotId = HostedPrincipal.text(snapshotId, "snapshotId", 4096);
        Objects.requireNonNull(boundAt, "boundAt");
        boundBy = HostedPrincipal.safeId(boundBy, "boundBy");
    }
}
