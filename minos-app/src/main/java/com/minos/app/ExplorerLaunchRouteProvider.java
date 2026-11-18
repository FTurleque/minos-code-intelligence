package com.minos.app;

import com.minos.application.HostedMode;
import com.minos.application.MinosApplication;
import com.minos.cli.ExplorerLaunchRoute;
import com.minos.cli.support.CliExitCodes;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.explorer.ExplorerLaunchPolicy;
import com.minos.explorer.ExplorerSession;
import com.minos.io.PrivateLocalStorage;
import com.minos.storage.MinosRuntimeSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Gives {@code com.minos.cli.MinosLauncher} the {@code minos explorer serve} route of the final assembly,
 * registered by {@code META-INF/services/com.minos.cli.ExplorerLaunchRoute}.
 *
 * <p>The route reads the backend and the team mode of the home before it opens anything, and asks
 * {@link ExplorerLaunchPolicy} whether the surface may start: a home whose backend is the Docker runtime is not
 * opened on the host, and a home in team mode gets no exploration surface. The team mode is read by
 * {@link HostedMode#from}, the rule of the application. Only then is the application opened, for as long as the
 * surface serves.</p>
 */
public final class ExplorerLaunchRouteProvider implements ExplorerLaunchRoute {

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
        Optional<String> refusal = ExplorerLaunchPolicy.backendRefusal(runtimeLocation.get(),
                workstation && dockerBackend(validatedHome));
        if (refusal.isPresent()) {
            return refuse(error, refusal.get());
        }
        HostedMode teamMode;
        try {
            teamMode = HostedMode.from(MinosRuntimeSettings.load(validatedHome));
        } catch (IllegalArgumentException unsupported) {
            return refuse(error, unsupported.getMessage());
        }
        refusal = ExplorerLaunchPolicy.teamModeRefusal(teamMode);
        if (refusal.isPresent()) {
            return refuse(error, refusal.get());
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
        return CliExitCodes.USAGE_ERROR;
    }
}
