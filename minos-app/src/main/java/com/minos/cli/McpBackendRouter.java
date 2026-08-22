package com.minos.cli;

import com.minos.application.MinosApplication;
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

    private final NativeMcpRunner nativeRunner;
    private final DockerMcpTransport dockerTransport;
    private final HomeValidator homeValidator;

    McpBackendRouter() {
        this(home -> MinosMcpServer.run(MinosApplication.open(home)),
                new DockerMcpTransport(), PrivateLocalStorage::ensurePrivateDirectory);
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
