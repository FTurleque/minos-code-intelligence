package com.minos.app;

import com.minos.application.MinosApplication;
import com.minos.cli.ExplorerLaunchRoute;
import com.minos.cli.command.FindSymbolCommand;
import com.minos.explorer.ExplorerSession;
import com.minos.io.PrivateLocalStorage;
import com.minos.storage.MinosRuntimeSettings;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

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

    /** The provider {@code ServiceLoader} instantiates. */
    public ExplorerLaunchRouteProvider() {
        this(MinosApplication::open, ExplorerSession::serve);
    }

    ExplorerLaunchRouteProvider(ApplicationOpener opener, Surface surface) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.surface = Objects.requireNonNull(surface, "surface");
    }

    @Override
    public int run(Path home, int port, Appendable error) throws Exception {
        Path validatedHome = PrivateLocalStorage.ensurePrivateDirectory(Objects.requireNonNull(home, "home"));
        if (new McpBackendConfigurationStore(validatedHome).loadOrMigrate().backend() == McpBackend.DOCKER) {
            return refuse(error, DOCKER_BACKEND_REFUSED);
        }
        String teamMode = MinosRuntimeSettings.load(validatedHome)
                .value(MinosApplication.HOSTED_MODE_PROPERTY, MinosApplication.HOSTED_MODE_ENV);
        if (teamMode != null && !"disabled".equals(teamMode.toLowerCase(Locale.ROOT))) {
            return refuse(error, TEAM_MODE_REFUSED);
        }
        try (MinosApplication application = opener.open(validatedHome)) {
            return surface.serve(application, port, error);
        }
    }

    private static int refuse(Appendable error, String reason) throws IOException {
        error.append("error: ").append(reason).append(System.lineSeparator());
        return FindSymbolCommand.USAGE_ERROR;
    }
}
