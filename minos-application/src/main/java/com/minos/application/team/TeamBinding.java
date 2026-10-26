package com.minos.application.team;

import com.minos.hosted.HostedProjectBinding;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Read model of the binding of a workspace to one exact project snapshot. */
public record TeamBinding(UUID projectId, String snapshotId, Instant boundAt, String boundBy) {
    public TeamBinding {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(snapshotId, "snapshotId");
        Objects.requireNonNull(boundAt, "boundAt");
        Objects.requireNonNull(boundBy, "boundBy");
    }

    static TeamBinding of(HostedProjectBinding value) {
        return new TeamBinding(value.projectId(), value.snapshotId(), value.boundAt(), value.boundBy());
    }
}
