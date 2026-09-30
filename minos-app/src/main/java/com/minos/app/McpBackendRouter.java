package com.minos.app;

import com.minos.application.MinosApplication;
import com.minos.cli.FindSymbolCommand;
import com.minos.io.PrivateLocalStorage;
import com.minos.mcp.MinosMcpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Routes the stable {@code minos mcp} entry point before any backend-specific application is opened. */
final class McpBackendRouter {

    interface NativeMcpRunner {
        void run(Path home) throws Exception;
    }

    @FunctionalInterface
    interface HomeValidator {
        Path validate(Path home) throws IOException;
    }

    /** Opens the application of a validated MINOS home; the production seam is {@link MinosApplication#open}. */
    @FunctionalInterface
    interface ApplicationOpener {
        MinosApplication open(Path home) throws IOException;
    }

    /** Serves one MCP session on an already-opened application; returns when the session ends. */
    @FunctionalInterface
    interface ServerRunner {
        void serve(MinosApplication application) throws Exception;
    }

    private final NativeMcpRunner nativeRunner;
    private final DockerMcpTransport dockerTransport;
    private final HomeValidator homeValidator;

    McpBackendRouter() {
        this(serving(MinosApplication::open, MinosMcpServer::run),
                new DockerMcpTransport(), PrivateLocalStorage::ensurePrivateDirectory);
    }

    /**
     * The native runner owns the application it opens: the application stays open for as long as the
     * MCP session is served (the server answers requests on it until standard input ends) and is closed
     * exactly once when the session returns or fails.
     */
    static NativeMcpRunner serving(ApplicationOpener opener, ServerRunner server) {
        return home -> {
            try (MinosApplication application = opener.open(home)) {
                server.serve(application);
            }
        };
    }

    McpBackendRouter(NativeMcpRunner nativeRunner, DockerMcpTransport dockerTransport) {
        this(nativeRunner, dockerTransport, PrivateLocalStorage::ensurePrivateDirectory);
    }

    McpBackendRouter(
            NativeMcpRunner nativeRunner,
            DockerMcpTransport dockerTransport,
            HomeValidator homeValidator
    ) {
        this.nativeRunner = Objects.requireNonNull(nativeRunner, "nativeRunner");
        this.dockerTransport = Objects.requireNonNull(dockerTransport, "dockerTransport");
        this.homeValidator = Objects.requireNonNull(homeValidator, "homeValidator");
    }

    int run(Path home) throws Exception {
        Path validatedHome = homeValidator.validate(Objects.requireNonNull(home, "home"));
        McpBackendConfiguration configuration = new McpBackendConfigurationStore(validatedHome).loadOrMigrate();
        return switch (configuration.backend()) {
            case NATIVE -> {
                nativeRunner.run(validatedHome);
                yield FindSymbolCommand.SUCCESS;
            }
            case DOCKER -> dockerTransport.run(configuration);
        };
    }
}
