package com.minos.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import static com.minos.domain.Preconditions.requireText;

/**
 * The run a project offers for resume (ADR 0039 sec. 6), as the surfaces read it: identity, phase it was interrupted
 * in, last checkpoint and the number of reusable targets. No path is exposed. Returned by
 * {@link ProjectInspectionService#resumableRun(String)}.
 */
public record ResumableRunStatus(
        UUID runId,
        String phase,
        Instant checkpointAt,
        int resumableTargets,
        int completedExecutions
) {
    public ResumableRunStatus {
        Objects.requireNonNull(runId, "runId");
        phase = requireText(phase, "phase");
        Objects.requireNonNull(checkpointAt, "checkpointAt");
        if (resumableTargets < 0 || completedExecutions < resumableTargets) {
            throw new IllegalArgumentException("resumableTargets must be within completedExecutions");
        }
    }

    /** Age of the last checkpoint, never negative. */
    public long checkpointAgeSeconds(Instant now) {
        Duration age = Duration.between(checkpointAt, Objects.requireNonNull(now, "now"));
        return age.isNegative() ? 0L : age.toSeconds();
    }
}
