package com.minos.application.indexing;

import com.minos.orchestration.IndexingRun;

import java.util.Objects;
import java.util.Optional;

/**
 * The resume considered for a run (ADR 0039 sec. 6): attempt number, reused and re-executed targets, and the public
 * reason when the resume was considered then refused.
 */
public record ResumeTraceSummary(int attempt, int reusedTargets, int reexecutedTargets, Optional<String> refusalReason) {
    public ResumeTraceSummary {
        if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
        if (reusedTargets < 0 || reexecutedTargets < 0) {
            throw new IllegalArgumentException("target counts must not be negative");
        }
        Objects.requireNonNull(refusalReason, "refusalReason");
    }

    static ResumeTraceSummary of(IndexingRun.ResumeTrace trace) {
        return new ResumeTraceSummary(trace.attempt(), trace.reusedTargets(), trace.reexecutedTargets(),
                trace.refusalReason());
    }
}
