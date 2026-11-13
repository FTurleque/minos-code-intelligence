package com.minos.app;

import com.minos.application.MinosApplication;
import com.minos.cli.ExplorerLaunchRoute;
import com.minos.cli.command.FindSymbolCommand;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.explorer.ExplorerSession;
import com.minos.io.PrivateLocalStorage;
import com.minos.storage.MinosRuntimeSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Gives {@code com.minos.cli.MinosLauncher} the {@code minos explorer serve} route of the final assembly,
 * registered by {@code META-INF/services/com.minos.cli.ExplorerLaunchRoute}.
 *
 * <p>The route decides whether the surface may start on this home before it opens anything: a home whose
 * backend is the Docker runtime is not opened on the host, and a home in team mode gets no exploration
 * surface. Only then is the application opened, for as long as the surface serves.</p>
 */
public final class ExplorerLaunchRouteProvider implements ExplorerLaunchRoute {

    static final String DOCKER_BACKEND_REFUSED = "this MINOS home runs on the Docker backend, whose data is not"
            + " opened on the host: start the optional explorer service of the Docker runtime instead (Compose"
            + " profile explorer), or switch the home to the native backend";
    static final String TEAM_MODE_REFUSED = "minos explorer serve is not available when the team mode is enabled"
            + " (MINOS_HOSTED_MODE / minos.hosted.mode): the exploration surface has no per-project authorization."
            + " Disable the team mode for this MINOS_HOME to explore its semantic index on this machine";

    /** Opens the application of a validated MINOS home; in production {@link MinosApplication#open}. */
    @FunctionalInterface
    interface ApplicationOpener {
        MinosApplication open(Path home) throws IOException;
    }

    /** Serves the surface on an opened application until it is asked to stop. */
    @FunctionalInterface
    interface Surface {
        int serve(MinosApplication application, int port, Appendable error);
    }

    private final ApplicationOpener opener;
    private final Surface surface;
    private final Supplier<ProjectPathMapping.RuntimeLocation> runtimeLocation;

    /** The provider {@code ServiceLoader} instantiates. */
    public ExplorerLaunchRouteProvider() {
        this(MinosApplication::open, ExplorerSession::serve,
                () -> ProjectPathMapping.resolveRuntimeLocation(System.getenv(), System.getProperties()));
    }

    ExplorerLaunchRouteProvider(ApplicationOpener opener, Surface surface,
                                Supplier<ProjectPathMapping.RuntimeLocation> runtimeLocation) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.surface = Objects.requireNonNull(surface, "surface");
        this.runtimeLocation = Objects.requireNonNull(runtimeLocation, "runtimeLocation");
    }

    @Override
    public int run(Path home, int port, Appendable error) throws Exception {
        Objects.requireNonNull(home, "home");
        // On a workstation the home is made private before anything is read from it, as for minos mcp. In the
        // Docker runtime the data is mounted read-only: nothing is hardened here, the application validates the
        // home when it opens it, and the backend switch, which concerns the host launcher only, is not read.
        boolean workstation = runtimeLocation.get() == ProjectPathMapping.RuntimeLocation.NATIVE;
        Path validatedHome = workstation ? PrivateLocalStorage.ensurePrivateDirectory(home)
                : home.toAbsolutePath().normalize();
        if (workstation && dockerBackend(validatedHome)) {
            return refuse(error, DOCKER_BACKEND_REFUSED);
        }
        MinosRuntimeSettings settings = MinosRuntimeSettings.load(validatedHome);
        String teamMode = settings.value(MinosApplication.HOSTED_MODE_PROPERTY, MinosApplication.HOSTED_MODE_ENV);
        if (teamMode != null && !"disabled".equals(teamMode.toLowerCase(Locale.ROOT))) {
            return refuse(error, TEAM_MODE_REFUSED);
        }
        try (MinosApplication application = opener.open(validatedHome)) {
            return surface.serve(application, port, error);
        }
    }

    /**
     * Whether the host launcher of this home is switched to the Docker backend. The configuration is read when
     * it exists and never created: a home without it is native. Reading it re-applies the owner-only
     * permissions of the home, of its runtime directory and of the file; no content is written.
     * Inside the Docker runtime the question does not arise: the switch concerns the host launcher only.
     */
    private static boolean dockerBackend(Path validatedHome) throws IOException {
        McpBackendConfigurationStore store = new McpBackendConfigurationStore(validatedHome);
        return Files.exists(store.file(), LinkOption.NOFOLLOW_LINKS)
                && store.loadOrMigrate().backend() == McpBackend.DOCKER;
    }

    private static int refuse(Appendable error, String reason) throws IOException {
        error.append("error: ").append(reason).append(System.lineSeparator());
        return FindSymbolCommand.USAGE_ERROR;
    }
}
