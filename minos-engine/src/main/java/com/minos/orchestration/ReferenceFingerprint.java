package com.minos.orchestration;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;

import java.util.Objects;
import java.util.Optional;

/**
 * The reference fingerprint the orchestration captured before the discovery (AUD-PERF-04), with the service that
 * captured it. The run reuses it as the fingerprint of the root scope, instead of hashing the whole tree a second time,
 * only when that service captures exactly like the one the resume verification uses: otherwise the persisted value
 * could differ from the one a resume recomputes, and the root scope is captured again (fail-closed, ADR 0039 sec. 3).
 */
public record ReferenceFingerprint(ProjectFingerprint fingerprint, ProjectFingerprintService capturedBy) {

    public ReferenceFingerprint {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(capturedBy, "capturedBy");
    }

    /** The root-scope fingerprint the run may persist, or empty when the equality of the rules is not proved. */
    Optional<String> reusableRootScopeFingerprint() {
        return ExecutionCheckpoints.capturesLikeCheckpoints(capturedBy)
                ? Optional.of(fingerprint.projectSha256())
                : Optional.empty();
    }
}
