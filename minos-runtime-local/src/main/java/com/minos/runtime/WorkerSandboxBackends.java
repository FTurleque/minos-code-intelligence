package com.minos.runtime;

import java.nio.file.Path;
import java.util.Objects;

/** Platform-aware selector for qualified worker and managed-local-provider sandboxes. */
public final class WorkerSandboxBackends {
    private WorkerSandboxBackends() {
    }

    /**
     * Strict selector for remote/hostile worker execution. A backend with only supervised
     * filesystem quotas is deliberately rejected here.
     */
    public static WorkerSandboxBackend strongestAvailable(Path minosHome) {
        Path home = normalizedHome(minosHome);
        return switch (WorkerSandboxQualification.currentPlatform()) {
            case LINUX -> LinuxBubblewrapWorkerSandboxBackend.discover(home)
                    .filter(WorkerSandboxBackend::supportsUntrustedCode)
                    .<WorkerSandboxBackend>map(value -> value)
                    .orElseGet(WorkerSandboxBackend::nativeEphemeralWorkspace);
            case WINDOWS -> WindowsAppContainerWorkerSandboxBackend.discover(home)
                    .filter(WorkerSandboxBackend::supportsUntrustedCode)
                    .<WorkerSandboxBackend>map(value -> value)
                    .orElseGet(WorkerSandboxBackend::nativeEphemeralWorkspace);
            case OTHER -> WorkerSandboxBackend.nativeEphemeralWorkspace();
        };
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
