package com.minos.cli;

import com.minos.cli.support.CliExitCodes;
import com.minos.application.MinosHome;
import com.minos.application.ProjectPathMappingService;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.output.json.DeterministicJson;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Narrow bootstrap entry point used by the packaged Docker administration plane before MINOS opens any business
 * state. It keeps the runtime-only host/container project mapping of the Docker data home:
 *
 * <ul>
 *   <li>{@code init-project-mounts <container-root>} saves an empty mount table when no mapping exists;</li>
 *   <li>{@code add-project-mount <name> <host-folder>} and {@code remove-project-mount <name>} change the table;</li>
 *   <li>{@code list-project-mounts --format json} prints the saved mapping for the lifecycle script;</li>
 *   <li>{@code migrate-project-mounts [--dry-run] [--skip <path>]...} replaces a single root mapping by one mount per
 *   registered project, and prints what it did as JSON;</li>
 *   <li>{@code configure-project-paths [--replace] <host-root> <container-root>} keeps a single root mapping: it
 *   creates it exactly once and replaces it only with {@code --replace}; both are refused on a mount table.</li>
 * </ul>
 *
 * Refusals name no host path: a message that would carry one is replaced by a generic one.
 */
public final class DockerRuntimeBootstrap {

    private static final String CONFIGURE_PROJECT_PATHS = "configure-project-paths";
    private static final String INIT_PROJECT_MOUNTS = "init-project-mounts";
    private static final String ADD_PROJECT_MOUNT = "add-project-mount";
    private static final String REMOVE_PROJECT_MOUNT = "remove-project-mount";
    private static final String LIST_PROJECT_MOUNTS = "list-project-mounts";
    private static final String MIGRATE_PROJECT_MOUNTS = "migrate-project-mounts";
    private static final String REPLACE = "--replace";
    private static final String DRY_RUN = "--dry-run";
    private static final String SKIP = "--skip";
    private static final String MOUNTS = "mounts";
    private static final String USAGE = "error: expected one of: " + INIT_PROJECT_MOUNTS + " <container-root>; "
            + ADD_PROJECT_MOUNT + " <name> <host-folder>; " + REMOVE_PROJECT_MOUNT + " <name>; "
            + LIST_PROJECT_MOUNTS + " --format json; " + MIGRATE_PROJECT_MOUNTS + " [--dry-run] [--skip <path>]...; "
            + "expected configure-project-paths [--replace] <host-root> <container-root>\n";

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
        String command = arguments.length == 0 ? "" : arguments[0];
        String[] rest = arguments.length == 0 ? arguments : Arrays.copyOfRange(arguments, 1, arguments.length);
        return switch (command) {
            case CONFIGURE_PROJECT_PATHS -> configure(home, rest, output, error);
            case INIT_PROJECT_MOUNTS -> mountCommand(error, () -> initialize(home, rest, output, error));
            case ADD_PROJECT_MOUNT -> mountCommand(error, () -> add(home, rest, output, error));
            case REMOVE_PROJECT_MOUNT -> mountCommand(error, () -> remove(home, rest, output, error));
            case LIST_PROJECT_MOUNTS -> mountCommand(error, () -> list(home, rest, output, error));
            case MIGRATE_PROJECT_MOUNTS -> mountCommand(error, () -> migrate(home, rest, output, error));
            default -> usage(error);
        };
    }

    @FunctionalInterface
    private interface MountCommand {
        int run() throws IOException;
    }

    /** Runs a mount command: an invalid table is a usage error, a refused operation an execution error. */
    private static int mountCommand(Appendable error, MountCommand command) throws IOException {
        try {
            return command.run();
        } catch (IllegalArgumentException exception) {
            error.append("error: ").append(publicMessage(exception, "invalid project mount")).append('\n');
            return CliExitCodes.USAGE_ERROR;
        } catch (IOException exception) {
            error.append("error: ").append(publicMessage(exception, "project mount operation refused")).append('\n');
            return CliExitCodes.EXECUTION_ERROR;
        }
    }

    private static int initialize(Path home, String[] arguments, Appendable output, Appendable error)
            throws IOException {
        if (arguments.length != 1) return usage(error);
        String containerRoot = ProjectPathMapping.mounted(arguments[0], List.of()).containerRoot();
        ProjectPathMappingService.Outcome outcome = ProjectPathMappingService.forHome(home).initialize(containerRoot);
        if (outcome == ProjectPathMappingService.Outcome.CONFLICT) {
            error.append("error: a project mount table of another container root is saved\n");
            return CliExitCodes.EXECUTION_ERROR;
        }
        output.append(outcome == ProjectPathMappingService.Outcome.CREATED
                ? "MINOS Docker project mount table created (no project exposed).\n"
                : "MINOS Docker project mount table already present.\n");
        return CliExitCodes.SUCCESS;
    }

    private static int add(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length != 2) return usage(error);
        ProjectPathMapping table = ProjectPathMappingService.forHome(home).addMount(arguments[0], arguments[1]);
        output.append("MINOS Docker project mount added: ").append(arguments[0])
                .append(" (").append(Integer.toString(table.mounts().size())).append(" of ")
                .append(Integer.toString(ProjectPathMapping.MAX_MOUNTS)).append(" mounts)\n");
        return CliExitCodes.SUCCESS;
    }

    private static int remove(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length != 1) return usage(error);
        ProjectPathMappingService service = ProjectPathMappingService.forHome(home);
        String name = arguments[0];
        service.removeMount(name);
        output.append("MINOS Docker project mount removed: ").append(name).append('\n');
        List<String> projects;
        try {
            projects = service.projectsUnder(name);
        } catch (IOException _) {
            // The registry is only named in the report: the mount is already removed.
            projects = List.of();
        }
        for (String project : projects) {
            output.append("Project still registered with its index, reported rootAvailable: false: ")
                    .append(project).append('\n');
        }
        return CliExitCodes.SUCCESS;
    }

    private static int list(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        if (arguments.length != 2 || !"--format".equals(arguments[0]) || !"json".equals(arguments[1])) {
            return usage(error);
        }
        Optional<ProjectPathMapping> saved = ProjectPathMappingService.forHome(home).load();
        Map<String, Object> listing;
        if (saved.isEmpty()) {
            listing = DeterministicJson.object("formatVersion", 0, "containerRoot", null, MOUNTS, List.of());
        } else {
            ProjectPathMapping mapping = saved.orElseThrow();
            listing = DeterministicJson.object(
                    "formatVersion", mapping.mountTable() ? 2 : 1,
                    "containerRoot", mapping.containerRoot(),
                    MOUNTS, mounts(mapping.mounts()));
        }
        output.append(DeterministicJson.render(listing)).append('\n');
        return CliExitCodes.SUCCESS;
    }

    private static int migrate(Path home, String[] arguments, Appendable output, Appendable error) throws IOException {
        boolean dryRun = false;
        Set<String> skipped = new LinkedHashSet<>();
        int index = 0;
        while (index < arguments.length) {
            if (DRY_RUN.equals(arguments[index])) {
                dryRun = true;
                index += 1;
            } else if (SKIP.equals(arguments[index]) && index + 1 < arguments.length) {
                skipped.add(arguments[index + 1]);
                index += 2;
            } else {
                return usage(error);
            }
        }
        ProjectPathMappingService.Migration migration = ProjectPathMappingService.forHome(home).migrate(skipped, dryRun);
        List<Object> skippedProjects = new ArrayList<>();
        for (ProjectPathMappingService.Skipped entry : migration.skipped()) {
            skippedProjects.add(DeterministicJson.object(
                    "project", entry.displayName(), "reason", entry.reason().name()));
        }
        output.append(DeterministicJson.render(DeterministicJson.object(
                "outcome", migration.outcome().name(),
                MOUNTS, mounts(migration.table().mounts()),
                "skipped", skippedProjects))).append('\n');
        return CliExitCodes.SUCCESS;
    }

    /** The mounts as the lifecycle script reads them: name, container path and host folder. */
    private static List<Object> mounts(List<ProjectMount> mounts) {
        List<Object> rendered = new ArrayList<>();
        for (ProjectMount mount : mounts) {
            rendered.add(DeterministicJson.object(
                    "name", mount.path(), "path", mount.path(), "hostRoot", mount.hostRoot()));
        }
        return rendered;
    }

    private static int configure(Path home, String[] arguments, Appendable output, Appendable error)
            throws IOException {
        boolean replace = arguments.length == 3 && REPLACE.equals(arguments[0]);
        if (!(arguments.length == 2 || replace)) {
            error.append("error: expected configure-project-paths [--replace] <host-root> <container-root>\n");
            return CliExitCodes.USAGE_ERROR;
        }
        int firstRoot = replace ? 1 : 0;

        final ProjectPathMapping desired;
        try {
            desired = ProjectPathMapping.singleRoot(arguments[firstRoot], arguments[firstRoot + 1]);
        } catch (IllegalArgumentException exception) {
            error.append("error: invalid project path mapping: ")
                    .append(publicMessage(exception, "the roots must be absolute Windows or POSIX paths"))
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
            if (service.load().map(ProjectPathMapping::mountTable).orElse(false)) {
                error.append("error: the Docker runtime exposes one mount per project and has no project root; ")
                        .append("expose a project with mcp-lifecycle.ps1 -Action AddProject\n");
                return CliExitCodes.EXECUTION_ERROR;
            }
            error.append("error: refusing to replace an existing project path mapping implicitly; run ")
                    .append("configure-project-paths --replace to replace it (projects outside the new root stay ")
                    .append("registered with their index and are reported rootAvailable: false)\n");
            return CliExitCodes.EXECUTION_ERROR;
        }
        reportUnreplaced(outcome, desired, output);
        return CliExitCodes.SUCCESS;
    }

    private static int usage(Appendable error) throws IOException {
        error.append(USAGE);
        return CliExitCodes.USAGE_ERROR;
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

    private static String publicMessage(Exception exception, String fallback) {
        return PublicErrorMessages.sanitize(exception.getMessage(), fallback);
    }

    static String failureMessage(Exception exception) {
        return PublicErrorMessages.sanitize(exception.getMessage(), exception.getClass().getSimpleName());
    }
}
