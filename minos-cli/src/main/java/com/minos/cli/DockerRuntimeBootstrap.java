package com.minos.cli;

import com.minos.cli.support.CliExitCodes;
import com.minos.application.MinosHome;
import com.minos.application.ProjectPathMappingService;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.domain.registry.ProjectPathMapping;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

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
            System.err.println("error: Docker runtime bootstrap failed: " + failureMessage(exception));
            exitCode = CliExitCodes.EXECUTION_ERROR;
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
            return CliExitCodes.USAGE_ERROR;
        }

        final ProjectPathMapping desired;
        try {
            desired = new ProjectPathMapping(arguments[1], arguments[2]);
        } catch (IllegalArgumentException exception) {
            error.append("error: invalid project path mapping: ")
                    .append(exception.getMessage())
                    .append('\n');
            return CliExitCodes.USAGE_ERROR;
        }

        // ADR 0042: the store comes from the composition root, only once the arguments are valid.
        ProjectPathMappingService.Outcome outcome = ProjectPathMappingService.forHome(home).configure(desired);
        if (outcome == ProjectPathMappingService.Outcome.CONFLICT) {
            error.append("error: refusing to replace an existing project path mapping implicitly\n");
            return CliExitCodes.EXECUTION_ERROR;
        }
        if (outcome == ProjectPathMappingService.Outcome.CREATED) {
            output.append("MINOS Docker project path mapping created: ")
                    .append(desired.hostRoot())
                    .append(" <-> ")
                    .append(desired.containerRoot())
                    .append('\n');
        } else {
            output.append("MINOS Docker project path mapping already matches requested roots.\n");
        }
        return CliExitCodes.SUCCESS;
    }

    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }
}
