package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.mcp.MinosMcpServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Routes the stable {@code minos mcp} entry point before any backend-specific application is opened. */
final class McpBackendRouter {

    interface NativeMcpRunner {
        void run(Path home) throws IOException;
    }

    private final NativeMcpRunner nativeRunner;
    private final DockerMcpTransport dockerTransport;

    McpBackendRouter() {
        this(home -> MinosMcpServer.run(MinosApplication.open(home)), new DockerMcpTransport());
    }

    McpBackendRouter(NativeMcpRunner nativeRunner, DockerMcpTransport dockerTransport) {
        this.nativeRunner = Objects.requireNonNull(nativeRunner, "nativeRunner");
        this.dockerTransport = Objects.requireNonNull(dockerTransport, "dockerTransport");
    }

    int run(Path home) throws IOException, InterruptedException {
        Objects.requireNonNull(home, "home");
        McpBackendConfiguration configuration = new McpBackendConfigurationStore(home).loadOrMigrate();
        return switch (configuration.backend()) {
            case NATIVE -> {
                nativeRunner.run(home);
                yield FindSymbolCommand.SUCCESS;
            }
            case DOCKER -> dockerTransport.run(configuration);
        };
    }
}
