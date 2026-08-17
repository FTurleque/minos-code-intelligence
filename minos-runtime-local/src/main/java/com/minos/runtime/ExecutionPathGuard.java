package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.ExecutionPathAuthorization;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Runtime-owned strong path authorization rechecked immediately before provider spawn.
 *
 * <p>Most filesystem providers expose a stable Java {@code fileKey()}, in which case the engine's
 * captured authorization is reused. Windows can legitimately omit that key; there the local runtime
 * captures the equivalent Win32 volume/file-index identity before any provider plan transformation.
 * If neither mechanism can establish strong identity the launch fails closed.</p>
 */
@FunctionalInterface
interface ExecutionPathGuard {

    void verifyCurrent(Path registeredProjectRoot, Path projectRoot) throws IOException;

    static ExecutionPathGuard capture(Path minosHome, IndexingExecutionRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        if (request.pathAuthorization().isPresent()) {
            ExecutionPathAuthorization authorization = request.pathAuthorization().orElseThrow();
            return authorization::verifyCurrent;
        }
        if (WorkerSandboxQualification.currentPlatform() == WorkerSandboxQualification.Platform.WINDOWS) {
            WindowsPathIdentity authorization = WindowsPathIdentity.capture(
                    minosHome, request.registeredProjectRoot(), request.projectRoot());
            return authorization::verifyCurrent;
        }
        throw new IOException(
                "provider execution path has no stable filesystem identity; refusing local process launch");
    }
}
