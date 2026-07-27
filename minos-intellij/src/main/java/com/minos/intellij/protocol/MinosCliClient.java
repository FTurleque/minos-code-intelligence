package com.minos.intellij.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.project.Project;
import com.minos.intellij.settings.MinosSettingsState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service(Service.Level.PROJECT)
public final class MinosCliClient {

    public static final String PROTOCOL_ID = "minos-ide";
    public static final String PROTOCOL_VERSION = "1";

    private final Project project;
    private volatile String verifiedConfiguration;

    public MinosCliClient(Project project) {
        this.project = project;
    }

    public static MinosCliClient getInstance(Project project) {
        return project.getService(MinosCliClient.class);
    }

    public JsonObject handshake() throws MinosProtocolException {
        JsonObject result = MinosProtocolHandshake.validate(
                runJsonRaw(List.of("ide", "handshake", "--format", "json")));
        verifiedConfiguration = configurationKey();
        return result;
    }

    public JsonObject resolveProject() throws MinosProtocolException {
        ensureHandshake();
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new MinosProtocolException("IntelliJ project has no local base path");
        }
        String expected = normalizePath(basePath);
        JsonObject root = runJsonRaw(List.of("project", "list", "--format", "json"));
        JsonArray projects = root.has("projects") ? root.getAsJsonArray("projects") : new JsonArray();
        for (JsonElement element : projects) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject candidate = element.getAsJsonObject();
            String rootPath = nullableString(candidate, "rootPath");
            if (rootPath != null && pathsEqual(expected, normalizePath(rootPath))) {
                return candidate;
            }
        }
        throw new MinosProtocolException(
                "This IntelliJ project is not registered in MINOS. Run `minos project add \"" + basePath + "\"` first.");
    }

    public JsonObject registerProject() throws MinosProtocolException {
        ensureHandshake();
        String basePath = project.getBasePath();
        if (basePath == null || basePath.isBlank()) {
            throw new MinosProtocolException("IntelliJ project has no local base path");
        }
        return runJsonRaw(List.of("project", "add", basePath, "--format", "json"));
    }

    public JsonObject indexStatus(String projectId) throws MinosProtocolException {
        return executeJson(List.of("index-status", projectId, "--format", "json"));
    }

    public JsonObject findSymbols(String projectId, String text, int limit) throws MinosProtocolException {
        return executeJson(List.of(
                "find-symbol", projectId, requireText(text, "symbol"),
                "--limit", Integer.toString(Math.max(1, Math.min(limit, 1000))),
                "--format", "json"));
    }

    public JsonObject findUsages(String projectId, String symbolId, int limit) throws MinosProtocolException {
        return executeJson(List.of(
                "find-usages", projectId, requireText(symbolId, "symbolId"),
                "--limit", Integer.toString(Math.max(1, Math.min(limit, 1000))),
                "--format", "json"));
    }

    public JsonObject relationships(String command, String projectId, String symbolId, int limit)
            throws MinosProtocolException {
        if (!List.of("find-implementations", "dependencies", "dependents", "related-tests",
                "find-callers", "find-callees").contains(command)) {
            throw new IllegalArgumentException("Unsupported relationship command: " + command);
        }
        return executeJson(List.of(
                command, projectId, requireText(symbolId, "symbolId"),
                "--limit", Integer.toString(Math.max(1, Math.min(limit, 1000))),
                "--format", "json"));
    }

    public JsonObject architecture(String projectId) throws MinosProtocolException {
        return executeJson(List.of("architecture", projectId, "--format", "json"));
    }

    public JsonObject impact(String projectId, String symbolId) throws MinosProtocolException {
        return executeJson(List.of("impact", projectId, requireText(symbolId, "symbolId"), "--format", "json"));
    }

    public JsonObject index(String projectId, boolean forceFull, boolean dryRun) throws MinosProtocolException {
        List<String> arguments = new ArrayList<>();
        arguments.add("index");
        arguments.add(projectId);
        if (forceFull) {
            arguments.add("--force-full");
        }
        if (dryRun) {
            arguments.add("--dry-run");
        }
        arguments.add("--format");
        arguments.add("json");
        return executeJson(arguments);
    }

    public JsonObject doctor() throws MinosProtocolException {
        return executeJson(List.of("doctor", "--format", "json"));
    }

    public JsonObject gitActivity(String projectId) throws MinosProtocolException {
        return executeJson(List.of(
                "git-activity", projectId,
                "--days", "30",
                "--max-commits", "500",
                "--max-files", "500",
                "--zone-depth", "2",
                "--format", "json"));
    }

    public JsonObject executeJson(List<String> arguments) throws MinosProtocolException {
        ensureHandshake();
        return runJsonRaw(arguments);
    }

    private void ensureHandshake() throws MinosProtocolException {
        String key = configurationKey();
        if (!Objects.equals(verifiedConfiguration, key)) {
            handshake();
        }
    }

    private JsonObject runJsonRaw(List<String> arguments) throws MinosProtocolException {
        ProcessResult result = run(arguments);
        if (result.exitCode() != 0) {
            String diagnostic = result.stderr().isBlank() ? result.stdout() : result.stderr();
            throw new MinosProtocolException(
                    "MINOS command failed (exit " + result.exitCode() + "): " + diagnostic.trim());
        }
        try {
            JsonElement parsed = JsonParser.parseString(result.stdout().trim());
            if (!parsed.isJsonObject()) {
                throw new MinosProtocolException("MINOS command did not return a JSON object");
            }
            return parsed.getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new MinosProtocolException("Invalid JSON returned by MINOS: " + abbreviate(result.stdout()), exception);
        }
    }

    private ProcessResult run(List<String> arguments) throws MinosProtocolException {
        MinosSettingsState.Settings settings = MinosSettingsState.getInstance(project).value();
        List<String> command = MinosCommandLine.build(settings.executable, arguments, System.getProperty("os.name", ""));
        ProcessBuilder builder = new ProcessBuilder(command);
        String basePath = project.getBasePath();
        if (basePath != null) {
            try {
                Path directory = Path.of(basePath);
                if (Files.isDirectory(directory)) {
                    builder.directory(directory.toFile());
                }
            } catch (RuntimeException ignored) {
                // ProcessBuilder will use the IDE working directory if the project path cannot be represented locally.
            }
        }
        if (!settings.minosHome.isBlank()) {
            builder.environment().put("MINOS_HOME", settings.minosHome);
        }
        try {
            Process process = builder.start();
            AtomicReference<byte[]> stdout = new AtomicReference<>(new byte[0]);
            AtomicReference<byte[]> stderr = new AtomicReference<>(new byte[0]);
            AtomicReference<IOException> readFailure = new AtomicReference<>();
            Thread outReader = Thread.ofVirtual().name("minos-stdout").start(() -> read(process, true, stdout, readFailure));
            Thread errReader = Thread.ofVirtual().name("minos-stderr").start(() -> read(process, false, stderr, readFailure));

            boolean completed = process.waitFor(settings.timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            outReader.join();
            errReader.join();
            if (readFailure.get() != null) {
                throw readFailure.get();
            }
            if (!completed) {
                throw new MinosProtocolException("MINOS command timed out after " + settings.timeoutSeconds + " seconds");
            }
            return new ProcessResult(
                    process.exitValue(),
                    new String(stdout.get(), StandardCharsets.UTF_8),
                    new String(stderr.get(), StandardCharsets.UTF_8));
        } catch (MinosProtocolException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MinosProtocolException("MINOS command was interrupted", exception);
        } catch (IOException exception) {
            throw new MinosProtocolException(
                    "Cannot start MINOS executable `" + settings.executable + "`: " + exception.getMessage(), exception);
        }
    }

    private static void read(
            Process process,
            boolean standardOutput,
            AtomicReference<byte[]> target,
            AtomicReference<IOException> failure
    ) {
        try {
            target.set((standardOutput ? process.getInputStream() : process.getErrorStream()).readAllBytes());
        } catch (IOException exception) {
            failure.compareAndSet(null, exception);
        }
    }

    private String configurationKey() {
        MinosSettingsState.Settings settings = MinosSettingsState.getInstance(project).value();
        return settings.executable + "\n" + settings.minosHome;
    }

    private static String nullableString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static String normalizePath(String value) {
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (Files.exists(path)) {
                path = path.toRealPath();
            }
            return path.toString();
        } catch (IOException | RuntimeException ignored) {
            return value.replace('\\', '/');
        }
    }

    private static boolean pathsEqual(String left, String right) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return windows ? left.equalsIgnoreCase(right) : left.equals(right);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String abbreviate(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
        return normalized.length() <= 240 ? normalized : normalized.substring(0, 240) + "…";
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
