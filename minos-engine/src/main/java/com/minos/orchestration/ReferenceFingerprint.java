package com.minos.orchestration;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;

import java.util.Objects;
import java.util.Optional;

/**
 * The reference fingerprint the orchestration captured before the discovery (AUD-PERF-04), with the service that
 * captured it. The run reuses it as the fingerprint of the root scope, instead of hashing the whole tree a second time,
 * only when that service is the very instance the run writes its checkpoints and verifies a resume with (D2): the
 * persisted value is then, by construction, the one a resume recomputes. Any other instance makes the run capture the
 * root scope again (fail-closed, ADR 0039 sec. 3).
 */
public record ReferenceFingerprint(ProjectFingerprint fingerprint, ProjectFingerprintService capturedBy) {

    public ReferenceFingerprint {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(capturedBy, "capturedBy");
    }

    /** The root-scope fingerprint the run may persist, or empty when {@code runService} did not capture it. */
    Optional<String> reusableRootScopeFingerprint(ProjectFingerprintService runService) {
        Objects.requireNonNull(runService, "runService");
        return capturedBy == runService ? Optional.of(fingerprint.projectSha256()) : Optional.empty();
    }
}
