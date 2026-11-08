package com.minos.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Port of the Linux side of the WSL2 route: the internal worker command that the jar runs in the distribution for
 * one request of the Windows installation. It runs one provider and writes the verified-transport bundle; it never
 * orchestrates, promotes or retains an index.
 */
@FunctionalInterface
public interface WslIndexingWorker {

    /**
     * Runs one worker request and writes its single status line on {@code output}.
     *
     * @return the process exit code: {@code 0} on success, {@code 1} on a refusal or failure, {@code 2} for an
     *     invalid request
     */
    int run(Path request, Appendable output) throws IOException;

    /**
     * The worker this host offers for {@code home}, resolved through the composition root without opening the
     * application; empty outside a Linux host.
     */
    static Optional<WslIndexingWorker> forHome(Path home) {
        Objects.requireNonNull(home, "home");
        return MinosApplicationComposers.resolve().wslIndexingWorker(home);
    }
}
