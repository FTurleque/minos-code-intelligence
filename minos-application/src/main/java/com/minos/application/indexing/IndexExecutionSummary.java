package com.minos.application.indexing;

import java.util.Objects;
import java.util.Optional;

import static com.minos.domain.Preconditions.requireText;

/**
 * The outcome of an indexing that did not fail, as the surfaces show it (AUD-ARC-01): the plan it executed, the run
 * identity (empty when nothing had to be indexed), the status ({@code NO_CHANGES} or the run status), the active
 * snapshot, whether the fingerprint baseline was promoted, the public diagnostic and the resume considered.
 */
public record IndexExecutionSummary(
        IndexPlanSummary plan,
        Optional<String> runId,
        String status,
        Optional<String> activeSnapshotId,
        boolean fingerprintPromoted,
        Optional<String> diagnostic,
        Optional<ResumeTraceSummary> resumed
) {
    public IndexExecutionSummary {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(runId, "runId");
        requireText(status, "status");
        Objects.requireNonNull(activeSnapshotId, "activeSnapshotId");
        Objects.requireNonNull(diagnostic, "diagnostic");
        Objects.requireNonNull(resumed, "resumed");
    }

    static IndexExecutionSummary of(ProjectIndexingUseCase.Outcome outcome) {
        return new IndexExecutionSummary(IndexPlanSummary.of(outcome.preparation()),
                outcome.run().map(run -> run.id().toString()), outcome.status(), outcome.activeSnapshotId(),
                outcome.fingerprintPromoted(), outcome.diagnostic(),
                outcome.run().flatMap(run -> run.resume()).map(ResumeTraceSummary::of));
    }
}
