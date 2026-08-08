package com.minos.mcp;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * SDK-backed end-to-end handshake probe for an installed/packaged MINOS launcher.
 *
 * <p>This deliberately uses the same MCP Java SDK client as MINOS integration
 * tests instead of maintaining a second hand-written JSON-RPC implementation in
 * release PowerShell. It still launches the real packaged executable as a child
 * process, so runtime/JPackage/STDIO regressions remain observable.</p>
 */
public final class MinosMcpHandshakeProbe {

    private static final List<String> REQUIRED_TOOLS = List.of(
            "minos_search_code",
            "minos_impact"
    );
    private static final int SHUTDOWN_GRACE_SECONDS = 10;

    private MinosMcpHandshakeProbe() {
    }

    public static void main(String[] args) {
        if (args.length < 2 || args.length > 3) {
            throw new IllegalArgumentException(
                    "usage: MinosMcpHandshakeProbe <launcher> <minos-home> [timeout-seconds]"
            );
        }

        Path launcher = Path.of(args[0]).toAbsolutePath().normalize();
        Path home = Path.of(args[1]).toAbsolutePath().normalize();
        int timeoutSeconds = args.length == 3 ? Integer.parseInt(args[2]) : 20;
        if (timeoutSeconds < 1 || timeoutSeconds > 120) {
            throw new IllegalArgumentException("timeout-seconds must be between 1 and 120");
        }
        if (!Files.isRegularFile(launcher)) {
            throw new IllegalArgumentException("MINOS launcher is missing: " + launcher);
        }

        try {
            Files.createDirectories(home);
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to create MINOS_HOME: " + home, exception);
        }

        // The MCP SDK has request-level timeouts, but release qualification must
        // also remain bounded if transport startup or graceful close itself stalls.
        // Run the whole lifecycle behind an independent wall-clock deadline and
        // terminate this short-lived probe JVM on timeout so CI cannot hang until
        // the workflow's 75-minute job timeout.
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> handshake = executor.submit(() -> runHandshake(launcher, home, timeoutSeconds));
        try {
            handshake.get(timeoutSeconds + SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            handshake.cancel(true);
            System.err.printf(
                    "MINOS MCP SDK HANDSHAKE TIMEOUT launcher=%s home=%s deadline=%ds%n",
                    launcher,
                    home,
                    timeoutSeconds + SHUTDOWN_GRACE_SECONDS
            );
            System.exit(124);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MINOS MCP SDK handshake probe was interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("MINOS MCP SDK handshake failed", cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static void runHandshake(Path launcher, Path home, int timeoutSeconds) {
        ServerParameters parameters = serverParameters(launcher, home);
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(timeoutSeconds))
                .build();

        try {
            client.initialize();
            var listed = client.listTools();
            List<String> names = listed.tools().stream().map(tool -> tool.name()).toList();
            for (String required : REQUIRED_TOOLS) {
                if (!names.contains(required)) {
                    throw new IllegalStateException("MCP tools/list response is missing " + required);
                }
            }
            System.out.printf(
                    "MINOS MCP SDK HANDSHAKE SUCCESS launcher=%s tools=%d%n",
                    launcher,
                    names.size()
            );
        } finally {
            client.closeGracefully();
        }
    }

    private static ServerParameters serverParameters(Path launcher, Path home) {
        Map<String, String> environment = Map.of("MINOS_HOME", home.toString());
        String extension = extension(launcher);
        if (isWindows() && (".cmd".equals(extension) || ".bat".equals(extension))) {
            String command = System.getenv("ComSpec");
            if (command == null || command.isBlank()) {
                command = "cmd.exe";
            }
            String commandLine = "\"\"" + launcher + "\" mcp\"";
            return ServerParameters.builder(command)
                    .args("/d", "/s", "/c", commandLine)
                    .env(environment)
                    .build();
        }
        return ServerParameters.builder(launcher.toString())
                .args("mcp")
                .env(environment)
                .build();
    }

    private static String extension(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
