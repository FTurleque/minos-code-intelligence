package com.minos.runtime;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Platform-aware selector for qualified worker and managed-local-provider sandboxes.
 *
 * <p>Untrusted remote execution is closed by decision (ADR 0041): the integrated Linux and Windows
 * backends keep a supervised write quota, so none of them is qualified for hostile code and the
 * strict selector always ends on the native fallback, which then refuses to run anything. That
 * rejection is reported ({@link WorkerSandboxSelection}) and logged as a WARNING with the exact
 * unmet dimension codes, never silently.</p>
 */
public final class WorkerSandboxBackends {

    private static final System.Logger LOGGER = System.getLogger(WorkerSandboxBackends.class.getName());

    private WorkerSandboxBackends() {
    }

    /**
     * Strict selector for remote/hostile worker execution. A backend with only supervised
     * filesystem quotas is deliberately rejected here; see {@link #selectForUntrustedCode(Path)}
     * for the reported form of the same selection.
     */
    public static WorkerSandboxBackend strongestAvailable(Path minosHome) {
        return selectForUntrustedCode(minosHome).backend();
    }

    /**
     * Strict selection for remote/hostile worker execution, reported rather than silent: the
     * discovered OS backend that is not qualified for untrusted code is recorded with its exact
     * unmet dimensions and logged as a WARNING before the native fallback is returned.
     */
    public static WorkerSandboxSelection selectForUntrustedCode(Path minosHome) {
        Path home = normalizedHome(minosHome);
        Optional<WorkerSandboxBackend> discovered = switch (WorkerSandboxQualification.currentPlatform()) {
            case LINUX -> LinuxBubblewrapWorkerSandboxBackend.discover(home).map(value -> value);
            case WINDOWS -> WindowsAppContainerWorkerSandboxBackend.discover(home).map(value -> value);
            case OTHER -> Optional.empty();
        };
        return selectForUntrustedCode(discovered);
    }

    /** Package-private seam: applies the strict selection to an already discovered backend. */
    static WorkerSandboxSelection selectForUntrustedCode(Optional<WorkerSandboxBackend> discovered) {
        Objects.requireNonNull(discovered, "discovered");
        WorkerSandboxBackend fallback = WorkerSandboxBackend.nativeEphemeralWorkspace();
        if (discovered.isEmpty()) {
            // discover() already logged why no OS backend exists here; do not log it twice.
            return new WorkerSandboxSelection(fallback, Optional.empty(), List.of(
                    "NO_OS_SANDBOX_BACKEND_DISCOVERED_ON_" + WorkerSandboxQualification.currentPlatform().name()));
        }
        WorkerSandboxBackend candidate = discovered.get();
        if (candidate.supportsUntrustedCode()) {
            return WorkerSandboxSelection.of(candidate);
        }
        WorkerSandboxSelection selection = new WorkerSandboxSelection(
                fallback, Optional.of(candidate.id()), rejectionReasons(candidate));
        // Identifiers and dimension codes only: never a path, a user name or a cgroup name.
        LOGGER.log(System.Logger.Level.WARNING,
                "MINOS worker sandbox backend " + candidate.id()
                        + " is not qualified for untrusted remote code; " + selection.refusalReport());
        return selection;
    }

    /** Machine-readable reasons, most specific first: unmet containment dimensions, then dispositions. */
    private static List<String> rejectionReasons(WorkerSandboxBackend candidate) {
        WorkerSandboxQualification qualification = candidate.qualification();
        List<String> reasons = new ArrayList<>(qualification.containment().unmetRequirements());
        if (reasons.isEmpty()) {
            if (!qualification.qualifiedForCurrentPlatform()) {
                reasons.add("BACKEND_NOT_QUALIFIED_ON_" + WorkerSandboxQualification.currentPlatform().name());
            }
            if (qualification.networkDeny() != WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED) {
                reasons.add("NETWORK_DENY_NOT_QUALIFIED");
            }
            if (reasons.isEmpty()) {
                reasons.add("TRUST_DISPOSITION_IS_" + qualification.trustDisposition().name());
            }
        }
        return List.copyOf(reasons);
    }

    /**
     * Selector for production managed local providers.
     *
     * <p>The selected backend must keep network denial OS-enforced, own the aggregate descendant
     * job and enforce filesystem quotas during execution. It may use supervised hard-kill for the
     * filesystem dimensions; callers must not reinterpret this narrower contract as hostile-code
     * support.</p>
     */
    public static WorkerSandboxBackend strongestAvailableForManagedLocalProvider(Path minosHome) {
        Path home = normalizedHome(minosHome);
        return switch (WorkerSandboxQualification.currentPlatform()) {
            case LINUX -> LinuxBubblewrapWorkerSandboxBackend.discover(home)
                    .filter(WorkerSandboxBackend::supportsManagedLocalProvider)
                    .<WorkerSandboxBackend>map(value -> value)
                    .orElseGet(WorkerSandboxBackend::nativeEphemeralWorkspace);
            case WINDOWS -> WindowsAppContainerWorkerSandboxBackend.discover(home)
                    .filter(WorkerSandboxBackend::supportsManagedLocalProvider)
                    .<WorkerSandboxBackend>map(value -> value)
                    .orElseGet(WorkerSandboxBackend::nativeEphemeralWorkspace);
            case OTHER -> WorkerSandboxBackend.nativeEphemeralWorkspace();
        };
    }

    private static Path normalizedHome(Path minosHome) {
        return Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
    }
}
