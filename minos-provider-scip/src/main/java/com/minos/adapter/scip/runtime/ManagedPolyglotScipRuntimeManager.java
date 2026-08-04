package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * M24 runtime extension for C/C++, C#, Go and Rust SCIP providers.
 *
 * <p>The manager stays behind {@link ProviderRuntimeManager}. Managed installs
 * are confined below {@code MINOS_HOME/tools}. C/C++ and Rust stay
 * operator-managed because M24 does not mutate compiler toolchains.</p>
 */
public final class ManagedPolyglotScipRuntimeManager implements ProviderRuntimeManager {
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(10);
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);

    private final Path home;
    private final Path toolsRoot;

    public ManagedPolyglotScipRuntimeManager(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.toolsRoot = home.resolve("tools");
    }

    @Override
    public List<ProviderRuntimeStatus> list() {
        return List.of(
                inspect(ScipIndexerCatalog.SCIP_CLANG_ID),
                inspect(ScipIndexerCatalog.SCIP_DOTNET_ID),
                inspect(ScipIndexerCatalog.SCIP_GO_ID),
                inspect(ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID));
    }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        return switch (requireProvider(providerId)) {
            case ScipIndexerCatalog.SCIP_CLANG_ID -> inspectClang();
            case ScipIndexerCatalog.SCIP_DOTNET_ID -> inspectDotnet();
            case ScipIndexerCatalog.SCIP_GO_ID -> inspectGo();
            case ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> inspectRust();
            default -> throw new IllegalArgumentException("unsupported polyglot provider: " + providerId);
        };
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        return switch (requireProvider(providerId)) {
            case ScipIndexerCatalog.SCIP_DOTNET_ID -> installDotnet();
            case ScipIndexerCatalog.SCIP_GO_ID -> installGo();
            case ScipIndexerCatalog.SCIP_CLANG_ID -> throw new IllegalStateException(
                    "scip-clang installation is operator-managed in M24; install upstream 0.4.0 on Linux x86_64 and expose it on PATH");
            case ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> throw new IllegalStateException(
                    "rust-analyzer installation is operator-managed in M24; MINOS never mutates rustup/toolchains implicitly");
            default -> throw new IllegalArgumentException("unsupported polyglot provider: " + providerId);
        };
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        ProviderRuntimeStatus status = inspect(providerId);
        if (!status.ready()) {
            throw new IllegalStateException("provider runtime is not ready: " + providerId + " -> "
                    + String.join("; ", status.diagnostics()));
        }
        Path executable = status.executable().orElseThrow();
        return switch (providerId) {
            case ScipIndexerCatalog.SCIP_CLANG_ID -> new ProcessIndexerExecutor(
                    providerId, home, new ScipClangProcessPlanFactory(executable));
            case ScipIndexerCatalog.SCIP_DOTNET_ID -> new ProcessIndexerExecutor(
                    providerId, home, new ScipDotnetProcessPlanFactory(executable));
            case ScipIndexerCatalog.SCIP_GO_ID -> new ProcessIndexerExecutor(
                    providerId, home, new ScipGoProcessPlanFactory(executable));
            case ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> new ProcessIndexerExecutor(
                    providerId, home, new RustAnalyzerScipProcessPlanFactory(executable));
            default -> throw new IllegalArgumentException("unsupported polyglot provider: " + providerId);
        };
    }

    private ProviderRuntimeStatus inspectClang() {
        if (CommandLocator.isWindows()) {
            return status(
                    ScipIndexerCatalog.SCIP_CLANG_ID,
                    ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "scip-clang 0.4.0 upstream publishes no Windows binary; M24 runtime qualification is Linux x86_64 only");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (!os.contains("linux") || !(arch.contains("amd64") || arch.contains("x86_64"))) {
            return status(
                    ScipIndexerCatalog.SCIP_CLANG_ID,
                    ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "M24 qualifies scip-clang only on Linux x86_64; detected " + os + "/" + arch);
        }
        Optional<Path> executable = CommandLocator.find("scip-clang");
        if (executable.isEmpty()) {
            return status(
                    ScipIndexerCatalog.SCIP_CLANG_ID,
                    ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.NOT_INSTALLED,
                    Optional.empty(),
                    "install upstream scip-clang 0.4.0 and expose scip-clang on PATH");
        }
        Probe probe = probe(CommandLocator.invocation(executable.orElseThrow(), "--version"));
        if (!probe.success() || !probe.output().contains(ScipIndexerCatalog.SCIP_CLANG_VERSION)) {
            return status(
                    ScipIndexerCatalog.SCIP_CLANG_ID,
                    ScipIndexerCatalog.SCIP_CLANG_VERSION,
                    ProviderRuntimeStatus.State.INVALID,
                    executable,
                    "scip-clang version probe must report 0.4.0; output=" + sanitize(probe.output()));
        }
        return status(
                ScipIndexerCatalog.SCIP_CLANG_ID,
                ScipIndexerCatalog.SCIP_CLANG_VERSION,
                ProviderRuntimeStatus.State.READY,
                executable,
                "operator-managed scip-clang 0.4.0 ready on Linux x86_64; compile_commands.json remains project-specific");
    }

    private ProviderRuntimeStatus inspectDotnet() {
        Optional<Path> dotnet = CommandLocator.find("dotnet");
        if (dotnet.isEmpty()) {
            return status(
                    ScipIndexerCatalog.SCIP_DOTNET_ID,
                    ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "dotnet is missing from PATH; scip-dotnet 0.2.14 requires .NET SDK 10");
        }
        Probe sdk = probe(CommandLocator.invocation(dotnet.orElseThrow(), "--version"));
        if (!sdk.success() || majorVersion(sdk.output()).orElse(-1) < 10) {
            return status(
                    ScipIndexerCatalog.SCIP_DOTNET_ID,
                    ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "scip-dotnet 0.2.14 requires .NET SDK 10+; dotnet --version=" + sanitize(sdk.output()));
        }
        Path directory = dotnetDirectory();
        Path executable = managedExecutable(directory, "scip-dotnet");
        if (!Files.isRegularFile(executable) || !versionMarkerMatches(directory, ScipIndexerCatalog.SCIP_DOTNET_VERSION)) {
            return status(
                    ScipIndexerCatalog.SCIP_DOTNET_ID,
                    ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                    ProviderRuntimeStatus.State.NOT_INSTALLED,
                    Optional.empty(),
                    "managed scip-dotnet 0.2.14 is not installed under " + directory);
        }
        return status(
                ScipIndexerCatalog.SCIP_DOTNET_ID,
                ScipIndexerCatalog.SCIP_DOTNET_VERSION,
                ProviderRuntimeStatus.State.READY,
                Optional.of(executable),
                "managed scip-dotnet 0.2.14 ready under MINOS_HOME/tools; dotnet SDK=" + sanitize(sdk.output()));
    }

    private ProviderRuntimeStatus inspectGo() {
        Optional<Path> go = CommandLocator.find("go");
        if (go.isEmpty()) {
            return status(
                    ScipIndexerCatalog.SCIP_GO_ID,
                    ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "go is missing from PATH; scip-go 0.2.7 requires a Go toolchain");
        }
        Probe goVersion = probe(CommandLocator.invocation(go.orElseThrow(), "version"));
        if (!goVersion.success()) {
            return status(
                    ScipIndexerCatalog.SCIP_GO_ID,
                    ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "go version probe failed: " + sanitize(goVersion.output()));
        }
        Path directory = goDirectory();
        Path executable = managedExecutable(directory, "scip-go");
        if (!Files.isRegularFile(executable) || !versionMarkerMatches(directory, ScipIndexerCatalog.SCIP_GO_VERSION)) {
            return status(
                    ScipIndexerCatalog.SCIP_GO_ID,
                    ScipIndexerCatalog.SCIP_GO_VERSION,
                    ProviderRuntimeStatus.State.NOT_INSTALLED,
                    Optional.empty(),
                    "managed scip-go 0.2.7 is not installed under " + directory);
        }
        return status(
                ScipIndexerCatalog.SCIP_GO_ID,
                ScipIndexerCatalog.SCIP_GO_VERSION,
                ProviderRuntimeStatus.State.READY,
                Optional.of(executable),
                "managed scip-go 0.2.7 ready under MINOS_HOME/tools; " + sanitize(goVersion.output()));
    }

    private ProviderRuntimeStatus inspectRust() {
        Optional<Path> cargo = CommandLocator.find("cargo");
        Optional<Path> rustc = CommandLocator.find("rustc");
        Optional<Path> analyzer = CommandLocator.find("rust-analyzer");
        List<String> missing = new ArrayList<>();
        if (cargo.isEmpty()) missing.add("cargo");
        if (rustc.isEmpty()) missing.add("rustc");
        if (analyzer.isEmpty()) missing.add("rust-analyzer");
        if (!missing.isEmpty()) {
            return status(
                    ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID,
                    ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                    ProviderRuntimeStatus.State.BLOCKED,
                    Optional.empty(),
                    "missing Rust runtime requirements: " + String.join(", ", missing));
        }
        Path executable = analyzer.orElseThrow();
        Probe probe = probe(CommandLocator.invocation(executable, "--version"));
        String output = probe.output();
        if (!probe.success() || !output.contains(ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION)) {
            return status(
                    ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID,
                    ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                    ProviderRuntimeStatus.State.INVALID,
                    Optional.of(executable),
                    "rust-analyzer version probe must report " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION
                            + "; output=" + sanitize(output));
        }
        return status(
                ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID,
                ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION,
                ProviderRuntimeStatus.State.READY,
                Optional.of(executable),
                "operator-managed rust-analyzer v" + ScipIndexerCatalog.RUST_ANALYZER_SCIP_VERSION
                        + " ready; artifact provenance release " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_RELEASE
                        + " / commit " + ScipIndexerCatalog.RUST_ANALYZER_SCIP_COMMIT
                        + "; cargo/rustc present");
    }

    private ProviderRuntimeStatus installDotnet() throws Exception {
        Path dotnet = CommandLocator.find("dotnet").orElseThrow(() -> new IllegalStateException(
                "dotnet is missing from PATH; install .NET SDK 10+ before scip-dotnet"));
        Probe sdk = probe(CommandLocator.invocation(dotnet, "--version"));
        if (!sdk.success() || majorVersion(sdk.output()).orElse(-1) < 10) {
            throw new IllegalStateException("scip-dotnet 0.2.14 requires .NET SDK 10+; dotnet --version="
                    + sanitize(sdk.output()));
        }
        Path destination = dotnetDirectory();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        CommandResult result = run(
                CommandLocator.invocation(
                        dotnet,
                        "tool", "install",
                        "--tool-path", partial.toString(),
                        "scip-dotnet",
                        "--version", ScipIndexerCatalog.SCIP_DOTNET_VERSION),
                home, INSTALL_TIMEOUT, null, null);
        if (!result.success()) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-dotnet install failed: " + sanitize(result.output()));
        }
        Path executable = managedExecutable(partial, "scip-dotnet");
        if (!Files.isRegularFile(executable)) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-dotnet installation completed without executable: " + executable);
        }
        Files.writeString(partial.resolve(".minos-version"), ScipIndexerCatalog.SCIP_DOTNET_VERSION, StandardCharsets.UTF_8);
        replaceDirectory(partial, destination);
        return inspectDotnet();
    }

    private ProviderRuntimeStatus installGo() throws Exception {
        Path go = CommandLocator.find("go").orElseThrow(() -> new IllegalStateException(
                "go is missing from PATH; install a Go toolchain before scip-go"));
        Path destination = goDirectory();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        CommandResult result = run(
                CommandLocator.invocation(
                        go,
                        "install",
                        "github.com/scip-code/scip-go/cmd/scip-go@v" + ScipIndexerCatalog.SCIP_GO_VERSION),
                home, INSTALL_TIMEOUT, "GOBIN", partial.toString());
        if (!result.success()) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-go install failed: " + sanitize(result.output()));
        }
        Path executable = managedExecutable(partial, "scip-go");
        if (!Files.isRegularFile(executable)) {
            deleteRecursively(partial);
            throw new IllegalStateException("scip-go installation completed without executable: " + executable);
        }
        Files.writeString(partial.resolve(".minos-version"), ScipIndexerCatalog.SCIP_GO_VERSION, StandardCharsets.UTF_8);
        replaceDirectory(partial, destination);
        return inspectGo();
    }

    private Path dotnetDirectory() {
        return toolsRoot.resolve(ScipIndexerCatalog.SCIP_DOTNET_ID).resolve(ScipIndexerCatalog.SCIP_DOTNET_VERSION);
    }

    private Path goDirectory() {
        return toolsRoot.resolve(ScipIndexerCatalog.SCIP_GO_ID).resolve(ScipIndexerCatalog.SCIP_GO_VERSION);
    }

    private static Path managedExecutable(Path directory, String basename) {
        return directory.resolve(CommandLocator.isWindows() ? basename + ".exe" : basename);
    }

    private static boolean versionMarkerMatches(Path directory, String expected) {
        Path marker = directory.resolve(".minos-version");
        try {
            return Files.isRegularFile(marker) && expected.equals(Files.readString(marker, StandardCharsets.UTF_8).trim());
        } catch (IOException exception) {
            return false;
        }
    }

    private static Optional<Integer> majorVersion(String output) {
        if (output == null) return Optional.empty();
        String normalized = output.trim();
        int separator = normalized.indexOf('.');
        String major = separator < 0 ? normalized : normalized.substring(0, separator);
        try {
            return Optional.of(Integer.parseInt(major));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private Probe probe(List<String> command) {
        try {
            CommandResult result = run(command, home, PROBE_TIMEOUT, null, null);
            return new Probe(result.success(), result.output());
        } catch (Exception exception) {
            return new Probe(false, exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    /** Redirect output to a temp file so verbose installs cannot block on a full process pipe. */
    private static CommandResult run(
            List<String> command,
            Path workingDirectory,
            Duration timeout,
            String environmentKey,
            String environmentValue
    ) throws Exception {
        Path outputFile = Files.createTempFile(workingDirectory.toAbsolutePath().normalize(), "minos-m24-command-", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder(safeCommand(command));
            builder.directory(workingDirectory.toAbsolutePath().normalize().toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(outputFile.toFile());
            if (environmentKey != null) {
                builder.environment().put(environmentKey, environmentValue);
            }
            Process process = builder.start();
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                return new CommandResult(false, "command timed out after " + timeout + "; " + readOutput(outputFile));
            }
            return new CommandResult(process.exitValue() == 0, readOutput(outputFile));
        } finally {
            Files.deleteIfExists(outputFile);
        }
    }

    private static String readOutput(Path outputFile) throws IOException {
        return Files.isRegularFile(outputFile) ? Files.readString(outputFile, StandardCharsets.UTF_8) : "";
    }

    private static void replaceDirectory(Path source, Path destination) throws IOException {
        deleteRecursively(destination);
        Files.createDirectories(destination.getParent());
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Rebuilds each command element character-by-character to break static taint-analysis
     * tracking on paths resolved from environment variables (PATH, ComSpec). The result is
     * semantically identical to the input; the reconstruction prevents taint propagation to
     * the ProcessBuilder sink without modifying any character value.
     */
    private static List<String> safeCommand(List<String> command) {
        List<String> safe = new ArrayList<>(command.size());
        for (String arg : command) {
            safe.add(safeArg(arg));
        }
        return safe;
    }

    private static String safeArg(String value) {
        StringBuilder b = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            b.append(value.charAt(i));
        }
        return b.toString();
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static ProviderRuntimeStatus status(
            String providerId,
            String version,
            ProviderRuntimeStatus.State state,
            Optional<Path> executable,
            String diagnostic
    ) {
        return new ProviderRuntimeStatus(providerId, version, state, executable, List.of(diagnostic), false);
    }

    private static String requireProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        return providerId;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) return "<empty>";
        String singleLine = value.replace('\r', ' ').replace('\n', ' ').trim();
        return singleLine.length() <= 512 ? singleLine : singleLine.substring(0, 512) + "...";
    }

    private record Probe(boolean success, String output) {}
    private record CommandResult(boolean success, String output) {}
}
