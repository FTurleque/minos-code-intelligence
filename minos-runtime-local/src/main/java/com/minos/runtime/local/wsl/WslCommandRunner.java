package com.minos.runtime.local.wsl;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/** Runs one {@code wsl.exe} command; injected so that the probe can be tested with fake outputs. */
@FunctionalInterface
public interface WslCommandRunner {

    /**
     * Runs {@code command} (absolute {@code wsl.exe} first) and returns its exit code and standard output.
     *
     * @throws IOException when the process cannot be started, exceeds {@code timeout} or its output bound
     */
    WslCommandResult run(List<String> command, Duration timeout) throws IOException;
}
