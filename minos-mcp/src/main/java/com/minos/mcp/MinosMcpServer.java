package com.minos.mcp;

import com.minos.cli.MinosCliRunner;
import com.minos.runtime.MinosVersion;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Serveur MCP local MINOS en transport STDIO. */
public final class MinosMcpServer {

    public static final String SERVER_NAME = "minos-code-intelligence";
    public static final String SERVER_VERSION = MinosVersion.current();

    private MinosMcpServer() {
    }

    public static void main(String[] arguments) {
        try {
            run(resolveHome(System.getenv(), System.getProperties()));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            String message = exception.getMessage();
            System.err.println("error: MINOS MCP bootstrap failed: " +
                    (message == null || message.isBlank() ? exception.getClass().getSimpleName() : message));
            System.exit(1);
        }
    }

    /** Lance une session MCP STDIO avec un home déjà résolu par le launcher. */
    public static void run(Path home) throws Exception {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        StdioServerTransportProvider transport =
                new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        McpSyncServer server = McpServer.sync(transport)
                .serverInfo(SERVER_NAME, SERVER_VERSION)
                .instructions("MINOS exposes read-only local code intelligence. Tool results are bounded JSON produced by the validated MINOS core.")
                .capabilities(ServerCapabilities.builder().tools(false).build())
                .tools(new MinosMcpTools(normalizedHome).specifications())
                .build();
        try {
            Thread.currentThread().join();
        } finally {
            server.close();
        }
    }

    static Path resolveHome(Map<String, String> environment, Properties properties) {
        return MinosCliRunner.resolveHome(environment, properties);
    }
}
