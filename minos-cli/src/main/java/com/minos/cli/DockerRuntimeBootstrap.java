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
 * refuses to replace a conflicting mapping implicitly: only {@code --replace} replaces it, rebasing the
 * registered projects under the new root and detaching the others (they keep their index).
 */
public final class DockerRuntimeBootstrap {

    private static final String CONFIGURE_PROJECT_PATHS = "configure-project-paths";
    private static final String REPLACE = "--replace";

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

        boolean replace = arguments.length == 4 && REPLACE.equals(arguments[1]);
        if (!(arguments.length == 3 || replace) || !CONFIGURE_PROJECT_PATHS.equals(arguments[0])) {
            error.append("error: expected configure-project-paths [--replace] <host-root> <container-root>\n");
            return CliExitCodes.USAGE_ERROR;
        }
        int firstRoot = replace ? 2 : 1;

        final ProjectPathMapping desired;
        try {
            desired = new ProjectPathMapping(arguments[firstRoot], arguments[firstRoot + 1]);
        } catch (IllegalArgumentException exception) {
            error.append("error: invalid project path mapping: ")
                    .append(exception.getMessage())
                    .append('\n');
            return CliExitCodes.USAGE_ERROR;
        }

        // ADR 0042: the store comes from the composition root, only once the arguments are valid.
        ProjectPathMappingService service = ProjectPathMappingService.forHome(home);
        if (replace) {
            return report(service.replace(desired), desired, output);
        }
        ProjectPathMappingService.Outcome outcome = service.configure(desired);
        if (outcome == ProjectPathMappingService.Outcome.CONFLICT) {
            error.append("error: refusing to replace an existing project path mapping implicitly; run ")
                    .append("configure-project-paths --replace to replace it (projects outside the new root stay ")
                    .append("registered with their index and are reported rootAvailable: false)\n");
            return CliExitCodes.EXECUTION_ERROR;
        }
        reportUnreplaced(outcome, desired, output);
        return CliExitCodes.SUCCESS;
    }

    private static int report(ProjectPathMappingService.Result result, ProjectPathMapping desired, Appendable output)
            throws IOException {
        if (result.outcome() != ProjectPathMappingService.Outcome.REPLACED) {
            reportUnreplaced(result.outcome(), desired, output);
            if (result.reattached() > 0) {
                output.append("MINOS Docker projects reattached under the project root: ")
                        .append(Integer.toString(result.reattached()))
                        .append('\n');
            }
            return CliExitCodes.SUCCESS;
        }
        output.append("MINOS Docker project path mapping replaced: ")
                .append(desired.hostRoot())
                .append(" <-> ")
                .append(desired.containerRoot())
                .append(" (rebased: ").append(Integer.toString(result.rebased()))
                .append(", reattached: ").append(Integer.toString(result.reattached()))
                .append(", detached: ").append(Integer.toString(result.detached()))
                .append(")\n");
        if (result.detached() > 0) {
            output.append("Detached projects stay registered with their index and are reported rootAvailable: false; ")
                    .append("register them again under the new root, or remove them.\n");
        }
        return CliExitCodes.SUCCESS;
    }

    private static void reportUnreplaced(
            ProjectPathMappingService.Outcome outcome, ProjectPathMapping desired, Appendable output) throws IOException {
        if (outcome == ProjectPathMappingService.Outcome.CREATED) {
            output.append("MINOS Docker project path mapping created: ")
                    .append(desired.hostRoot())
                    .append(" <-> ")
                    .append(desired.containerRoot())
                    .append('\n');
        } else {
            output.append("MINOS Docker project path mapping already matches requested roots.\n");
        }
    }

    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }
}
