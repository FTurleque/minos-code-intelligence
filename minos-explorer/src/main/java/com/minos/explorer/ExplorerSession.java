package com.minos.explorer;

import com.minos.application.MinosApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One run of {@code minos explorer serve}: the surface starts when the command asks for it, serves until the
 * process is told to stop, and stops with it. Nothing else in MINOS starts it, and it starts nothing else.
 */
public final class ExplorerSession {

    public static final int SUCCESS = 0;
    public static final int EXECUTION_ERROR = 1;
    public static final int USAGE_ERROR = 2;

    static final String TEAM_MODE_REFUSED = "minos explorer serve is not available when the team mode is enabled"
            + " (MINOS_HOSTED_MODE): the exploration surface has no per-project authorization. Disable the team mode"
            + " for this MINOS_HOME to explore its semantic index on this machine";
    static final String BIND_FAILED = "the exploration surface could not listen on the requested port;"
            + " choose another one with --port, or omit it to let the system pick a free port";

    private ExplorerSession() {
    }

    /** Waits until the session is asked to stop. */
    @FunctionalInterface
    interface StopSignal {
        void await() throws InterruptedException;
    }

    /**
     * Serves the exploration surface on {@code application} until the process is asked to stop (Ctrl+C or a
     * termination signal), then closes it. {@code port} is 0 for a free port chosen by the system.
     *
     * @return {@link #SUCCESS} after a requested stop, {@link #USAGE_ERROR} when the surface refuses to start
     *         here, {@link #EXECUTION_ERROR} when the port cannot be bound
     */
    public static int serve(MinosApplication application, int port, Appendable error) {
        CountDownLatch stopRequested = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        Thread hook = new Thread(() -> {
            stopRequested.countDown();
            try {
                if (!stopped.await(5, TimeUnit.SECONDS)) Thread.currentThread().interrupt();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "minos-explorer-stop");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            return serve(application, port, System.getenv(), error, stopRequested::await);
        } finally {
            stopped.countDown();
        }
    }

    static int serve(MinosApplication application, int port, Map<String, String> environment, Appendable error,
                     StopSignal stop) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(error, "error");
        if (port < 0 || port > 65_535) {
            say(error, "error: --port must be between 0 and 65535");
            return USAGE_ERROR;
        }
        if (application.teamControlPlane().isPresent()) {
            say(error, "error: " + TEAM_MODE_REFUSED);
            return USAGE_ERROR;
        }
        ListeningPolicy.Scope scope;
        try {
            scope = ListeningPolicy.resolve(environment);
        } catch (IllegalArgumentException refused) {
            say(error, "error: " + refused.getMessage());
            return USAGE_ERROR;
        }
        try (ExplorerServer server = ExplorerServer.start(application.semanticCorpusQueryService(),
                application.semanticProjectionService(), port, scope,
                ExplorerServer.DEFAULT_REQUEST_TIMEOUT, line -> say(error, "explorer: " + line))) {
            say(error, "MINOS explorer is listening on port " + server.port() + " ("
                    + (scope == ListeningPolicy.Scope.LOOPBACK ? "loopback only" : "container interface")
                    + "), read-only. Stop it with Ctrl+C.");
            say(error, "Open this address in a browser of this machine; it carries the session token, do not share it:");
            say(error, server.accessUrl());
            stop.await();
            say(error, "MINOS explorer stopped.");
            return SUCCESS;
        } catch (IOException bindFailure) {
            say(error, "error: " + BIND_FAILED);
            return EXECUTION_ERROR;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SUCCESS;
        }
    }

    private static void say(Appendable error, String line) {
        try {
            error.append(line).append(System.lineSeparator());
        } catch (IOException closed) {
            throw new UncheckedIOException(closed);
        }
    }
}
