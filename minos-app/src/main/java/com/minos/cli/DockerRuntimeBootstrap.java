package com.minos.cli;

import com.minos.application.MinosHome;
import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappingStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Narrow bootstrap entry point used by the packaged Docker administration plane before MINOS opens
 * any business state. It creates the runtime-only host/container path mapping exactly once and
 * refuses to replace a conflicting mapping implicitly.
 */
public final class DockerRuntimeBootstrap {

    private static final String CONFIGURE_PROJECT_PATHS = "configure-project-paths";

    private DockerRuntimeBootstrap() {
    }

    public static void main(String[] arguments) {
        int exitCode;
        try {
            exitCode = run(MinosHome.resolve(System.getenv(), System.getProperties()), arguments, System.out, System.err);
        } catch (Exception exception) {
            String message = exception.getMessage();
            System.err.println("error: Docker runtime bootstrap failed: "
                    + (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
            exitCode = FindSymbolCommand.EXECUTION_ERROR;
        }
        System.exit(exitCode);
    }

    static int run(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        Objects.requireNonNull(home, "home");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(error, "error");

        if (arguments.length != 3 || !CONFIGURE_PROJECT_PATHS.equals(arguments[0])) {
            error.append("error: expected configure-project-paths <host-root> <container-root>\n");
            return FindSymbolCommand.USAGE_ERROR;
        }

        final ProjectPathMapping desired;
        try {
            desired = new ProjectPathMapping(arguments[1], arguments[2]);
        } catch (IllegalArgumentException exception) {
            error.append("error: invalid project path mapping: ")
                    .append(exception.getMessage())
                    .append('\n');
            return FindSymbolCommand.USAGE_ERROR;
        }

        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        Optional<ProjectPathMapping> existing = store.loadOptional();
        if (existing.isPresent() && !existing.orElseThrow().equals(desired)) {
            error.append("error: refusing to replace an existing project path mapping implicitly: ")
                    .append(store.file().toString())
                    .append('\n');
            return FindSymbolCommand.EXECUTION_ERROR;
        }
        if (existing.isEmpty()) {
            store.save(desired);
            output.append("MINOS Docker project path mapping created: ")
                    .append(desired.hostRoot())
                    .append(" <-> ")
                    .append(desired.containerRoot())
                    .append('\n');
        } else {
            output.append("MINOS Docker project path mapping already matches requested roots.\n");
        }
        return FindSymbolCommand.SUCCESS;
    }
}
