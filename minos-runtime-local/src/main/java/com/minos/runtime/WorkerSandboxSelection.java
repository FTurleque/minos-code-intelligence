package com.minos.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of choosing a worker sandbox for untrusted (remote) code on this host.
 *
 * <p>Besides the backend that was finally retained, the selection records which OS backend was
 * discovered but rejected and the machine-readable reasons for that rejection. The reasons are
 * dimension codes such as {@code FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL};
 * they never carry a filesystem path, a user name or a cgroup name, so they can be logged and shown
 * by {@code minos doctor} as they are.</p>
 *
 * <p>Untrusted remote execution is closed by decision (ADR 0041) on every OS the integrated
 * backends support; this record is what makes that refusal explicit instead of silent.</p>
 */
public record WorkerSandboxSelection(
        WorkerSandboxBackend backend,
        Optional<String> rejectedBackendId,
        List<String> rejectionReasons
) {

    public WorkerSandboxSelection {
        Objects.requireNonNull(backend, "backend");
        rejectedBackendId = rejectedBackendId == null ? Optional.empty() : rejectedBackendId;
        rejectionReasons = rejectionReasons == null ? List.of() : List.copyOf(rejectionReasons);
    }

    /** A selection that retained {@code backend} without rejecting any other backend. */
    public static WorkerSandboxSelection of(WorkerSandboxBackend backend) {
        return new WorkerSandboxSelection(backend, Optional.empty(), List.of());
    }

    public boolean supportsUntrustedCode() {
        return backend.supportsUntrustedCode();
    }

    /**
     * Path-free, single-line description of why untrusted execution is refused here: the decision
     * marker first, then the rejected OS backend (if one was discovered) and its exact unmet
     * dimension codes. Safe to log and to print by {@code minos doctor} as is.
     */
    public String refusalReport() {
        StringBuilder report = new StringBuilder(
                "untrusted remote execution is fail-closed by decision (ADR 0041)");
        if (rejectedBackendId.isPresent()) {
            report.append("; OS sandbox backend ").append(rejectedBackendId.get())
                    .append(" was rejected for untrusted code: ")
                    .append(String.join(", ", rejectionReasons));
        } else if (!rejectionReasons.isEmpty()) {
            report.append("; ").append(String.join(", ", rejectionReasons));
        }
        return report.toString();
    }
}
