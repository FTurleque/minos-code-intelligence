package com.minos.application.indexing;

import com.minos.orchestration.IndexingResumePolicy;

/**
 * The resume choice a surface passes to the indexing (ADR 0039 sec. 6), with one value per value of the engine
 * policy and the same name (AUD-ARC-01, D2); {@code IndexingResumeChoiceTest} fails on a value present on one side
 * only.
 */
public enum IndexingResumeChoice {
    /** Default: reopen the interrupted run when its checkpoints are valid, otherwise a complete run. */
    RESUME,
    /** Supersede the interrupted run explicitly and run a complete indexing. */
    NO_RESUME,
    /** Fail cleanly, without creating a run, when no resume is possible. */
    RESUME_ONLY;

    /** The engine policy of the same name. */
    IndexingResumePolicy policy() {
        return IndexingResumePolicy.valueOf(name());
    }
}
