package com.minos.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of choosing a worker sandbox for untrusted (remote) code on this host.
 *
 * <p>Besides the backend that was finally retained, the selection records <em>why</em> untrusted
 * execution is (or is not) possible, as one of three distinct causes that an operator must never
 * confuse:</p>
 *
 * <ul>
 *   <li>{@link Cause#NO_OS_BACKEND_AVAILABLE} — no OS sandbox backend could be discovered here:
 *       either a prerequisite is missing (bubblewrap, prlimit, delegated cgroup v2 root, PowerShell,
 *       or the capability probe failed), which is an operator action that makes the backend
 *       available to managed local providers only, or the platform has no integrated backend at all
 *       (nothing to install);</li>
 *   <li>{@link Cause#REJECTED_BY_DECISION} — an OS backend exists and is qualified for managed local
 *       providers, but its write quota is supervised rather than OS-enforced; it is rejected for
 *       untrusted code <strong>by decision</strong> (ADR 0041). No operator action reopens it;</li>
 *   <li>{@link Cause#EXECUTOR_NOT_SANDBOX_CAPABLE} — the provider executor does not expose
 *       {@link ProcessSandboxCapableIndexerExecutor}, so no OS sandbox can wrap it at all.</li>
 * </ul>
 *
 * <p>The reasons are machine-readable codes such as
 * {@code FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL} or
 * {@code LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING}; they never carry a filesystem path, a user name
 * or a cgroup name, so they can be logged and shown by {@code minos doctor} as they are.</p>
 */
public record WorkerSandboxSelection(
        WorkerSandboxBackend backend,
        Cause cause,
        Optional<String> rejectedBackendId,
        List<String> rejectionReasons
) {

    /** Machine-readable code carried by {@link Cause#EXECUTOR_NOT_SANDBOX_CAPABLE} selections. */
    public static final String EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE = "EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE";

    /**
     * Suffix of the {@link Cause#NO_OS_BACKEND_AVAILABLE} code naming a platform without any
     * integrated OS backend ({@code PLATFORM_<name>_HAS_NO_OS_SANDBOX_BACKEND}): nothing to install.
     */
    public static final String UNSUPPORTED_PLATFORM_SUFFIX = "_HAS_NO_OS_SANDBOX_BACKEND";

    public WorkerSandboxSelection {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(rejectedBackendId, "rejectedBackendId");
        rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
        if (cause == Cause.QUALIFIED && !backend.supportsUntrustedCode()) {
            throw new IllegalArgumentException("a QUALIFIED selection requires a backend qualified for untrusted code");
        }
        if (cause == Cause.REJECTED_BY_DECISION && rejectedBackendId.isEmpty()) {
            throw new IllegalArgumentException("a rejection by decision names the rejected OS backend");
        }
    }

    /**
     * A selection that takes {@code backend} as given: {@link Cause#QUALIFIED} when it supports
     * untrusted code, {@link Cause#NOT_QUALIFIED} (with its own unmet dimensions) otherwise.
     */
    public static WorkerSandboxSelection of(WorkerSandboxBackend backend) {
        Objects.requireNonNull(backend, "backend");
        if (backend.supportsUntrustedCode()) {
            return new WorkerSandboxSelection(backend, Cause.QUALIFIED, Optional.empty(), List.of());
        }
        return new WorkerSandboxSelection(
                backend, Cause.NOT_QUALIFIED, Optional.empty(),
                backend.qualification().containment().unmetRequirements());
    }

    /** The provider executor cannot be wrapped by any OS sandbox; only the native fallback remains. */
    public static WorkerSandboxSelection executorNotSandboxCapable() {
        return new WorkerSandboxSelection(
                WorkerSandboxBackend.nativeEphemeralWorkspace(),
                Cause.EXECUTOR_NOT_SANDBOX_CAPABLE,
                Optional.empty(),
                List.of(EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE));
    }

    public boolean supportsUntrustedCode() {
        return cause == Cause.QUALIFIED && backend.supportsUntrustedCode();
    }

    /** True only for the cause that no operator action can change: the ADR 0041 decision. */
    public boolean closedByDecision() {
        return cause == Cause.REJECTED_BY_DECISION;
    }

    /**
     * Path-free, single-line description of why untrusted execution is refused here, worded per
     * cause so that a missing prerequisite is never presented as a decision, nor the reverse.
     * Empty when the selection is qualified. Safe to log and to print by {@code minos doctor}.
     */
    public String refusalReport() {
        String reasons = String.join(", ", rejectionReasons);
        return switch (cause) {
            case QUALIFIED -> "";
            case NO_OS_BACKEND_AVAILABLE -> unsupportedPlatform()
                    ? "no OS sandbox backend exists for this platform (" + reasons + ")"
                            + "; untrusted remote execution stays fail-closed and there is nothing to install"
                    : "no OS sandbox backend is available on this host"
                            + (reasons.isEmpty() ? "" : " (missing prerequisite: " + reasons + ")")
                            + "; untrusted remote execution stays fail-closed: providing it makes the OS backend"
                            + " available to managed local providers only, not to untrusted code";
            case REJECTED_BY_DECISION -> "untrusted remote execution is fail-closed by decision (ADR 0041)"
                    + "; OS sandbox backend " + rejectedBackendId.orElseThrow()
                    + " was rejected for untrusted code: " + reasons;
            case EXECUTOR_NOT_SANDBOX_CAPABLE -> "the provider executor exposes no process-sandbox capability ("
                    + EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE + "), so no OS sandbox can contain it"
                    + "; untrusted remote execution stays fail-closed";
            case NOT_QUALIFIED -> "sandbox backend " + backend.id() + " is not qualified for untrusted remote code"
                    + (reasons.isEmpty() ? "" : ": " + reasons);
        };
    }

    private boolean unsupportedPlatform() {
        return rejectionReasons.stream().anyMatch(reason -> reason.endsWith(UNSUPPORTED_PLATFORM_SUFFIX));
    }

    public enum Cause {
        /** The retained backend is qualified for untrusted code on this host. */
        QUALIFIED,
        /** No OS backend could be discovered: an operator prerequisite is missing, or the platform has none. */
        NO_OS_BACKEND_AVAILABLE,
        /** An OS backend exists but is rejected for untrusted code by decision (ADR 0041). */
        REJECTED_BY_DECISION,
        /** The provider executor cannot be wrapped by any OS sandbox. */
        EXECUTOR_NOT_SANDBOX_CAPABLE,
        /** A backend handed in as-is that does not support untrusted code (no discovery involved). */
        NOT_QUALIFIED
    }
}
