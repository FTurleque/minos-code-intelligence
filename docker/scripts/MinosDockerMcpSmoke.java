import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

public final class MinosDockerMcpSmoke {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    private MinosDockerMcpSmoke() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 && arguments.length != 3) {
            throw new IllegalArgumentException(
                    "usage: MinosDockerMcpSmoke <compose-file> <env-file> [running-container]"
            );
        }

        Path composeFile = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path environmentFile = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(composeFile) || !Files.isRegularFile(environmentFile)) {
            throw new IllegalArgumentException("installed Compose runtime is incomplete");
        }

        boolean runningContainer = arguments.length == 3;
        String containerName = runningContainer
                ? arguments[2]
                : "minos-mcp-prod-smoke-" + ProcessHandle.current().pid();
        List<String> command = runningContainer
                ? List.of(
                        "docker", "exec", "-i", containerName,
                        "java", "-cp", "/opt/minos/minos.jar", "com.minos.mcp.MinosMcpServer"
                )
                : List.of(
                        "docker", "compose",
                        "--project-directory", composeFile.getParent().toString(),
                        "--env-file", environmentFile.toString(),
                        "-f", composeFile.toString(),
                        "run", "--rm", "-T", "--name", containerName,
                        "--entrypoint", "java", "minos-mcp",
                        "-cp", "/opt/minos/minos.jar", "com.minos.mcp.MinosMcpServer"
                );

        Process process = new ProcessBuilder(command).start();
        CompletableFuture<String> standardError = CompletableFuture.supplyAsync(
                () -> readAll(process.getErrorStream())
        );
        ExecutorService outputReader = Executors.newSingleThreadExecutor();
        try (var input = process.outputWriter(StandardCharsets.UTF_8);
             var output = process.inputReader(StandardCharsets.UTF_8)) {
            input.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},\"clientInfo\":{\"name\":\"minos-docker-smoke\",\"version\":\"1.0\"}}}\n");
            input.flush();
            String initialize = readResponse(outputReader, output, 1, process, standardError);
            require(initialize.contains("\"serverInfo\""), "initialize response has no serverInfo", initialize);

            input.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}\n");
            input.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n");
            input.flush();
            String tools = readResponse(outputReader, output, 2, process, standardError);
            for (String tool : List.of(
                    "minos_project_structure",
                    "minos_architecture",
                    "minos_architecture_graph",
                    "minos_impact"
            )) {
                require(tools.contains("\"name\":\"" + tool + "\""), "missing MCP tool " + tool, tools);
            }

            String mode = runningContainer ? "docker-exec" : "compose-run";
            System.out.println("MINOS Docker MCP smoke SUCCESS: initialize=OK, tools=16, mode=" + mode);
        } finally {
            outputReader.shutdownNow();
            if (process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            }
            if (!runningContainer) {
                removeContainer(containerName);
            }
        }
    }

    private static String readResponse(
            ExecutorService executor,
            BufferedReader output,
            int expectedId,
            Process process,
            CompletableFuture<String> standardError
    ) throws Exception {
        Pattern id = Pattern.compile("\\\"id\\\"\\s*:\\s*" + expectedId + "(?:\\D|$)");
        for (int lineNumber = 0; lineNumber < 20; lineNumber++) {
            Future<String> lineRead = executor.submit(output::readLine);
            String line;
            try {
                line = lineRead.get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException exception) {
                lineRead.cancel(true);
                throw new IllegalStateException("timeout waiting for MCP response id=" + expectedId + stderr(standardError), exception);
            }
            if (line == null) {
                int exitCode = process.waitFor();
                throw new IllegalStateException("MCP container exited with code " + exitCode + stderr(standardError));
            }
            if (id.matcher(line).find()) {
                require(!line.contains("\"error\""), "MCP response contains an error", line);
                return line;
            }
        }
        throw new IllegalStateException("MCP response id=" + expectedId + " was not received" + stderr(standardError));
    }

    private static void require(boolean condition, String message, String response) {
        if (!condition) {
            throw new IllegalStateException(message + ": " + response);
        }
    }

    private static String stderr(CompletableFuture<String> standardError) {
        if (!standardError.isDone()) {
            return "";
        }
        try {
            String value = standardError.get();
            return value.isBlank() ? "" : System.lineSeparator() + value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "";
        } catch (ExecutionException exception) {
            return "";
        }
    }

    private static String readAll(InputStream stream) {
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().reduce("", (left, right) -> left + System.lineSeparator() + right);
        } catch (IOException exception) {
            return "";
        }
    }

    private static void removeContainer(String containerName) {
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(10, TimeUnit.SECONDS);
        } catch (IOException exception) {
            // The container normally disappears through --rm.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
