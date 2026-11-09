package com.minos.application;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Port of the Linux side of the WSL2 route: the internal worker command that the jar runs in the distribution for
 * one request of the Windows installation. It runs one provider and writes the verified-transport bundle; it never
 * orchestrates, promotes or retains an index. Its probe mode reports the scip-java prerequisites of the distribution
 * to the prerequisite probe of the Windows installation.
 */
public interface WslIndexingWorker {

    /** Argument of the internal worker command that selects the probe mode instead of a request file. */
    String PROBE_OPTION = "--probe";

    /**
     * Runs one worker request and writes its single status line on {@code output}.
     *
     * @return the process exit code: {@code 0} on success, {@code 1} on a refusal or failure, {@code 2} for an
     *     invalid request
     */
    int run(Path request, Appendable output) throws IOException;

    /**
     * Applies the scip-java checks of the distribution (Maven, project JDK, designated Maven repository) and writes
     * one probe line per check on {@code output}, without running Maven nor a provider.
     *
     * @return the process exit code: {@code 0} once the lines are written
     */
    int probe(Appendable output) throws IOException;

    /**
     * The worker this host offers for {@code home}, resolved through the composition root without opening the
     * application; empty outside a Linux host.
     */
    static Optional<WslIndexingWorker> forHome(Path home) {
        Objects.requireNonNull(home, "home");
        return MinosApplicationComposers.resolve().wslIndexingWorker(home);
    }
}
