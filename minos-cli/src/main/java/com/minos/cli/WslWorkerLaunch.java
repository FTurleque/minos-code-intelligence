package com.minos.cli;

import com.minos.application.WslIndexingWorker;
import com.minos.cli.command.FindSymbolCommand;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;

/**
 * The internal command {@code internal wsl-worker <request>} of the WSL2 route (protocol {@code minos-wsl-worker-v1}).
 *
 * <p>It is reserved to MINOS: the Windows installation starts it in a distribution with the same jar, so it is not
 * listed by {@code --help} and is not a public surface. It never opens the application of MINOS_HOME.</p>
 */
final class WslWorkerLaunch {

    static final String GROUP = "internal";
    static final String COMMAND = "wsl-worker";

    private WslWorkerLaunch() {
    }

    static boolean isWorkerCommand(String[] arguments) {
        return arguments.length >= 2 && GROUP.equals(arguments[0]) && COMMAND.equals(arguments[1]);
    }

    /** Runs the worker; usage errors and an unavailable worker are reported on {@code err} without a path. */
    static int run(String[] arguments, Function<Path, Optional<WslIndexingWorker>> workers, Path home,
                   PrintStream out, PrintStream err)
            throws IOException {
        if (arguments.length != 3 || arguments[2].isBlank()) {
            err.println("error: internal wsl-worker expects exactly one request file");
            return FindSymbolCommand.USAGE_ERROR;
        }
        Path request;
        try {
            request = Path.of(arguments[2]);
        } catch (InvalidPathException invalid) {
            err.println("error: internal wsl-worker request is not a valid path");
            return FindSymbolCommand.USAGE_ERROR;
        }
        Optional<WslIndexingWorker> worker = workers.apply(home);
        if (worker.isEmpty()) {
            err.println("error: the WSL worker runs only inside a Linux distribution");
            return FindSymbolCommand.EXECUTION_ERROR;
        }
        StringBuilder status = new StringBuilder();
        int code = worker.orElseThrow().run(request, status);
        out.print(status);
        out.flush();
        return code;
    }
}
