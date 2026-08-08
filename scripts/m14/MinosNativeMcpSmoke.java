import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Smoke MCP natif utilisé par les qualifications Windows packagées.
 *
 * Usage:
 *   java MinosNativeMcpSmoke.java <minos-launcher> <minos-home>
 */
public final class MinosNativeMcpSmoke {

    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(20);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("expected <minos-launcher> <minos-home>");
        }
        Path launcher = Path.of(args[0]).toAbsolutePath().normalize();
        Path home = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isRegularFile(launcher)) {
            throw new IllegalArgumentException("MINOS launcher is missing: " + launcher);
        }
        Files.createDirectories(home);

        ProcessBuilder builder = new ProcessBuilder(command(launcher));
        builder.directory(launcher.getParent().toFile());
        builder.environment().put("MINOS_HOME", home.toString());
        Path stderr = Files.createTempFile("minos-native-mcp-", ".stderr.log");
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        String stderrText = "";
        Exception failure = null;
        try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader stdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
            write(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"minos-mcp-smoke\",\"version\":\"1\"}}}");
            String initialize = awaitResponse(stdout, "\"id\":1");
            requireContains(initialize, "minos-code-intelligence", "initialize server name");

            write(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
            write(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            String tools = awaitResponse(stdout, "\"id\":2");
            requireContains(tools, "minos_search_code", "tools/list search tool");
            requireContains(tools, "minos_impact", "tools/list impact tool");
        } catch (Exception exception) {
            failure = exception;
        } finally {
            stopProcessTree(process);
            if (Files.exists(stderr)) {
                stderrText = Files.readString(stderr, StandardCharsets.UTF_8);
            }
            deleteWithRetry(stderr);
        }

        if (failure != null) {
            String diagnostics = stderrText.isBlank()
                    ? "<empty>"
                    : stderrText;
            throw new IllegalStateException(
                    failure.getMessage() + System.lineSeparator()
                            + "packaged MCP stderr:" + System.lineSeparator()
                            + diagnostics,
                    failure
            );
        }
        requireStderrClean(stderrText);
        System.out.println("MINOS native MCP handshake SUCCESS");
    }

    private static void requireStderrClean(String stderr) {
        if (stderr.contains("NoClassDefFoundError")) {
            throw new IllegalStateException("packaged MCP emitted NoClassDefFoundError: " + stderr);
        }
        if (stderr.contains("SLF4J(W): No SLF4J providers were found")) {
            throw new IllegalStateException("packaged MCP has no SLF4J provider: " + stderr);
        }
        if (stderr.contains("Exception in thread \"main\"")) {
            throw new IllegalStateException("packaged MCP emitted an uncaught main-thread exception: " + stderr);
        }
    }

    private static void stopProcessTree(Process process) throws InterruptedException {
        List<ProcessHandle> descendants = process.descendants().toList();
        boolean parentExited = process.waitFor(5, TimeUnit.SECONDS);
        if (!parentExited || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            for (ProcessHandle descendant : descendants.reversed()) {
                if (descendant.isAlive()) {
                    descendant.destroy();
                }
            }
            if (process.isAlive()) {
                process.destroy();
            }
            awaitExit(descendants, Duration.ofSeconds(2));
        }
        for (ProcessHandle descendant : descendants.reversed()) {
            if (descendant.isAlive()) {
                descendant.destroyForcibly();
            }
        }
        if (process.isAlive()) {
            process.destroyForcibly();
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("MINOS MCP process did not terminate");
        }
        awaitExit(descendants, Duration.ofSeconds(5));
    }

    private static void awaitExit(List<ProcessHandle> processes, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (processes.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < deadline) {
            Thread.sleep(25L);
        }
    }

    private static void deleteWithRetry(Path path) throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(path);
                return;
            } catch (IOException failure) {
                lastFailure = failure;
                Thread.sleep(100L);
            }
        }
        throw lastFailure;
    }

    private static String[] command(Path launcher) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (!windows) {
            return new String[]{launcher.toString(), "mcp"};
        }
        String comspec = System.getenv("ComSpec");
        if (comspec == null || comspec.isBlank()) {
            comspec = "cmd.exe";
        }
        // cmd.exe /s /c requires an extra quote pair when the command itself starts
        // with a quoted executable path. Match the PowerShell M29 probe contract:
        //   /d /s /c ""C:\...\minos.cmd" mcp"
        String commandLine = "\"\"" + launcher + "\" mcp\"";
        return new String[]{comspec, "/d", "/s", "/c", commandLine};
    }

    private static void write(BufferedWriter writer, String json) throws IOException {
        writer.write(json);
        writer.newLine();
        writer.flush();
    }

    private static String awaitResponse(BufferedReader reader, String marker) throws Exception {
        long deadline = System.nanoTime() + RESPONSE_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (reader.ready()) {
                String line = reader.readLine();
                if (line == null) {
                    throw new IllegalStateException("MCP server closed stdout before response " + marker);
                }
                if (line.contains(marker)) {
                    return line;
                }
            } else {
                Thread.sleep(25L);
            }
        }
        throw new IllegalStateException("timed out waiting for MCP response " + marker);
    }

    private static void requireContains(String value, String expected, String label) {
        if (!value.contains(expected)) {
            throw new IllegalStateException(label + " missing `" + expected + "` in: " + value);
        }
    }
}
