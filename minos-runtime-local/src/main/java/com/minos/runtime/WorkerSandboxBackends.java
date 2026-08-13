package com.minos.runtime;

import java.nio.file.Path;
import java.util.Objects;

/** Platform-aware selector for the strongest locally qualified worker sandbox. */
public final class WorkerSandboxBackends {
    private WorkerSandboxBackends() {
    }

    public static WorkerSandboxBackend strongestAvailable(Path minosHome) {
        Path home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
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
}
