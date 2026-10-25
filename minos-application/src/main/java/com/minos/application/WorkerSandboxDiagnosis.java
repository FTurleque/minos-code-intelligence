package com.minos.application;

import com.minos.runtime.WorkerSandboxProbe;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the host says about worker sandboxes, for a diagnostic: backend identifiers and machine-readable reason codes
 * only, never a path, a user name or a cgroup name.
 *
 * @param managedLocalBackend     backend selected for managed local providers
 * @param managedLocalAvailable   whether that backend honours the managed-local contract here
 * @param untrustedCodeBackend    backend the strict (remote) selector ends on
 * @param remoteIndexingAvailable whether untrusted remote execution is qualified here
 * @param cause                   selection cause name: why it is (un)available
 * @param rejectedBackend         OS backend discovered but rejected for untrusted code, if any
 * @param reasons                 exact unmet dimension codes (decision) or missing prerequisite codes
 * @param reason                  single-line, path-free explanation of the refusal ("" when available)
 */
public record WorkerSandboxDiagnosis(
        String managedLocalBackend,
        boolean managedLocalAvailable,
        String untrustedCodeBackend,
        boolean remoteIndexingAvailable,
        String cause,
        Optional<String> rejectedBackend,
        List<String> reasons,
        String reason
) {

    /** The cause of a selection closed by decision (ADR 0041), as {@link #cause()} reports it. */
    public static final String CAUSE_REJECTED_BY_DECISION = WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION;

    public WorkerSandboxDiagnosis {
        Objects.requireNonNull(managedLocalBackend, "managedLocalBackend");
        Objects.requireNonNull(untrustedCodeBackend, "untrustedCodeBackend");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(rejectedBackend, "rejectedBackend");
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        reason = reason == null ? "" : reason;
    }

    /** Whether remote indexing is closed by the ADR 0041 decision, as opposed to a missing prerequisite. */
    public boolean closedByDecision() {
        return CAUSE_REJECTED_BY_DECISION.equals(cause);
    }
}
