package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Gestion locale des runtimes SCIP qualifiés par MINOS.
 *
 * <p>Les installations sont confinées à {@code MINOS_HOME/tools}. Aucun PATH
 * utilisateur et aucune installation npm globale ne sont modifiés.</p>
 */
public final class ManagedScipProviderRuntimeManager implements ProviderRuntimeManager {

    public static final String SCIP_TYPESCRIPT_ID = "scip-typescript";
    public static final String SCIP_TYPESCRIPT_VERSION = "0.4.0";
    public static final String SCIP_JAVA_ID = "scip-java";
    public static final String SCIP_JAVA_VERSION = "0.12.3";
    public static final String SCIP_JAVA_COORDINATE =
            "com.sourcegraph:scip-java_2.13:" + SCIP_JAVA_VERSION;

    private static final URI COURSIER_WINDOWS_URI = URI.create(
            "https://github.com/coursier/coursier/releases/download/v2.1.25-M26/cs-x86_64-pc-win32.exe");

    private final Path home;
    private final Path toolsRoot;

    public ManagedScipProviderRuntimeManager(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.toolsRoot = home.resolve("tools");
    }

    @Override
    public List<ProviderRuntimeStatus> list() {
        return List.of(inspect(SCIP_JAVA_ID), inspect(SCIP_TYPESCRIPT_ID));
    }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        return switch (providerId) {
            case SCIP_TYPESCRIPT_ID -> inspectTypeScript();
            case SCIP_JAVA_ID -> inspectJava();
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        return switch (providerId) {
            case SCIP_TYPESCRIPT_ID -> installTypeScript();
            case SCIP_JAVA_ID -> installJava();
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        ProviderRuntimeStatus status = inspect(providerId);
        if (!status.ready() || status.executable().isEmpty()) {
            throw new IllegalStateException("provider runtime is not ready: " + providerId + " — "
                    + String.join("; ", status.diagnostics()));
        }
        return switch (providerId) {
            case SCIP_TYPESCRIPT_ID -> new ProcessIndexerExecutor(
                    providerId, home, new ScipTypeScriptProcessPlanFactory(status.executable().orElseThrow()));
            case SCIP_JAVA_ID -> new ProcessIndexerExecutor(
                    providerId, home, new ScipJavaProcessPlanFactory(
                            status.executable().orElseThrow(), SCIP_JAVA_COORDINATE));
            default -> throw new IllegalArgumentException("unknown managed provider: " + providerId);
        };
    }

    private ProviderRuntimeStatus inspectTypeScript() {
        List<String> diagnostics = new ArrayList<>();
        Optional<Path> node = CommandLocator.find("node");
        Optional<Path> npm = CommandLocator.find("npm");
        if (node.isEmpty()) {
            diagnostics.add("Node.js is not available in PATH");
        }
        if (npm.isEmpty()) {
            diagnostics.add("npm is not available in PATH");
        }
        Path executable = typeScriptExecutable();
        if (!Files.isRegularFile(executable)) {
            diagnostics.add("managed scip-typescript " + SCIP_TYPESCRIPT_VERSION + " is not installed");
        }
        ProviderRuntimeStatus.State state = diagnostics.isEmpty()
                ? ProviderRuntimeStatus.State.READY
                : Files.isRegularFile(executable)
                    ? ProviderRuntimeStatus.State.BLOCKED
                    : ProviderRuntimeStatus.State.NOT_INSTALLED;
        return new ProviderRuntimeStatus(
                SCIP_TYPESCRIPT_ID,
                SCIP_TYPESCRIPT_VERSION,
                state,
                Files.isRegularFile(executable) ? Optional.of(executable) : Optional.empty(),
                diagnostics
        );
    }

    private ProviderRuntimeStatus inspectJava() {
        List<String> diagnostics = new ArrayList<>();
        Optional<Path> coursier = coursierExecutable();
        if (coursier.isEmpty()) {
            diagnostics.add("Coursier is not installed in MINOS_HOME/tools and was not found in PATH");
        }
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome == null || javaHome.isBlank()) {
            diagnostics.add("JAVA_HOME is not set to the project JDK");
        } else {
            boolean windows = CommandLocator.isWindows();
            Path javac = Path.of(javaHome).resolve("bin").resolve(windows ? "javac.exe" : "javac");
            if (!Files.isRegularFile(javac)) {
                diagnostics.add("JAVA_HOME does not contain javac: " + javaHome);
            }
        }
        ProviderRuntimeStatus.State state = diagnostics.isEmpty()
                ? ProviderRuntimeStatus.State.READY
                : coursier.isEmpty()
                    ? ProviderRuntimeStatus.State.NOT_INSTALLED
                    : ProviderRuntimeStatus.State.BLOCKED;
        return new ProviderRuntimeStatus(
                SCIP_JAVA_ID,
                SCIP_JAVA_VERSION,
                state,
                coursier,
                diagnostics
        );
    }

    private ProviderRuntimeStatus installTypeScript() throws Exception {
        Path npm = CommandLocator.find("npm")
                .orElseThrow(() -> new IllegalStateException("npm is required to install scip-typescript"));
        CommandLocator.find("node")
                .orElseThrow(() -> new IllegalStateException("Node.js is required to run scip-typescript"));

        Files.createDirectories(toolsRoot);
        Path destination = typeScriptRoot();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        try {
            run(CommandLocator.invocation(
                    npm,
                    "install",
                    "--prefix", partial.toString(),
                    "--no-audit", "--no-fund", "--ignore-scripts",
                    "@sourcegraph/scip-typescript@" + SCIP_TYPESCRIPT_VERSION
            ), home, toolsRoot.resolve("scip-typescript-install.log"), Duration.ofMinutes(10));
            Path installed = partial.resolve("node_modules").resolve(".bin")
                    .resolve(CommandLocator.isWindows() ? "scip-typescript.cmd" : "scip-typescript");
            if (!Files.isRegularFile(installed)) {
                throw new IllegalStateException("scip-typescript executable was not created: " + installed);
            }
            deleteRecursively(destination);
            move(partial, destination);
        } finally {
            deleteRecursively(partial);
        }
        ProviderRuntimeStatus status = inspectTypeScript();
        if (!status.ready()) {
            throw new IllegalStateException("scip-typescript installation is incomplete: "
                    + String.join("; ", status.diagnostics()));
        }
        return status;
    }

    private ProviderRuntimeStatus installJava() throws Exception {
        Files.createDirectories(toolsRoot);
        Path coursier = ensureCoursier();
        run(List.of(coursier.toString(), "--help"), home,
                toolsRoot.resolve("coursier-verify.log"), Duration.ofMinutes(1));
        run(List.of(coursier.toString(), "launch", SCIP_JAVA_COORDINATE, "--", "--help"), home,
                toolsRoot.resolve("scip-java-install.log"), Duration.ofMinutes(10));

        ProviderRuntimeStatus status = inspectJava();
        if (status.executable().isEmpty()) {
            throw new IllegalStateException("Coursier installation is not visible after scip-java bootstrap");
        }
        return status;
    }

    private Path ensureCoursier() throws Exception {
        Optional<Path> existing = coursierExecutable();
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        if (!CommandLocator.isWindows()) {
            throw new IllegalStateException(
                    "automatic Coursier installation is currently packaged for Windows x64; install `cs` in PATH");
        }
        Path directory = toolsRoot.resolve("coursier").resolve("2.1.25-M26");
        Files.createDirectories(directory);
        Path destination = directory.resolve("cs.exe");
        Path partial = directory.resolve("cs.partial.exe");

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        HttpRequest request = HttpRequest.newBuilder(COURSIER_WINDOWS_URI)
                .timeout(Duration.ofMinutes(2))
                .header("User-Agent", "MINOS-Code-Intelligence")
                .build();
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(partial));
        if (response.statusCode() < 200 || response.statusCode() >= 300 || Files.size(partial) == 0L) {
            Files.deleteIfExists(partial);
            throw new IllegalStateException("Coursier download failed with HTTP " + response.statusCode());
        }
        move(partial, destination);
        return destination;
    }

    private Optional<Path> coursierExecutable() {
        Path managed = toolsRoot.resolve("coursier").resolve("2.1.25-M26")
                .resolve(CommandLocator.isWindows() ? "cs.exe" : "cs");
        if (Files.isRegularFile(managed)) {
            return Optional.of(managed);
        }
        return CommandLocator.find("cs");
    }

    private Path typeScriptRoot() {
        return toolsRoot.resolve("scip-typescript").resolve(SCIP_TYPESCRIPT_VERSION);
    }

    private Path typeScriptExecutable() {
        return typeScriptRoot().resolve("node_modules").resolve(".bin")
                .resolve(CommandLocator.isWindows() ? "scip-typescript.cmd" : "scip-typescript");
    }

    private static void run(List<String> command, Path workingDirectory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().normalize().getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean completed = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        if (!completed) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("tool command timed out; see " + log);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("tool command failed with code " + process.exitValue() + "; see " + log);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            for (Path current : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
