import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Smoke MCP natif utilisé par la qualification M14.
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
        builder.environment().put("MINOS_HOME", home.toString());
        Path stderr = Files.createTempFile("minos-native-mcp-", ".stderr.log");
        builder.redirectError(stderr.toFile());
        Process process = builder.start();
        try (BufferedWriter stdin = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
             BufferedReader stdout = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
            write(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"minos-m14-smoke\",\"version\":\"1\"}}}");
            String initialize = awaitResponse(stdout, "\"id\":1");
            requireContains(initialize, "minos-code-intelligence", "initialize server name");

            write(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}");
            write(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            String tools = awaitResponse(stdout, "\"id\":2");
            requireContains(tools, "minos_search_code", "tools/list search tool");
            requireContains(tools, "minos_impact", "tools/list impact tool");

            System.out.println("MINOS native MCP handshake SUCCESS");
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            if (process.exitValue() != 0 && process.exitValue() != 143) {
                String error = Files.exists(stderr) ? Files.readString(stderr) : "";
                if (!error.isBlank()) {
                    System.err.println(error);
                }
            }
            Files.deleteIfExists(stderr);
        }
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
        String commandLine = "\"" + launcher + "\" mcp";
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
