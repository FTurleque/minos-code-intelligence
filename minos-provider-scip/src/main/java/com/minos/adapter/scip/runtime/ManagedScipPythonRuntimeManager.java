package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Managed runtime extension for @sourcegraph/scip-python. */
public final class ManagedScipPythonRuntimeManager implements ProviderRuntimeManager {
    public static final String PROVIDER_ID = "scip-python";
    public static final String VERSION = ScipIndexerCatalog.SCIP_PYTHON_VERSION;

    private final Path home;
    private final Path toolsRoot;

    public ManagedScipPythonRuntimeManager(Path minosHome) {
        this.home = Objects.requireNonNull(minosHome, "minosHome").toAbsolutePath().normalize();
        this.toolsRoot = home.resolve("tools");
    }

    @Override
    public List<ProviderRuntimeStatus> list() {
        return List.of(inspect(PROVIDER_ID));
    }

    @Override
    public ProviderRuntimeStatus inspect(String providerId) {
        requireProvider(providerId);
        List<String> diagnostics = new ArrayList<>();
        if (CommandLocator.find("node").isEmpty()) {
            diagnostics.add("Node.js 16+ is required by scip-python");
        }
        if (CommandLocator.find("npm").isEmpty()) {
            diagnostics.add("npm is required to install scip-python");
        }
        if (pythonExecutable().isEmpty()) {
            diagnostics.add("Python 3.10+ is required in PATH by scip-python");
        }
        Path executable = executable();
        boolean installed = Files.isRegularFile(executable);
        if (!installed) {
            diagnostics.add("managed scip-python " + VERSION + " is not installed");
        }
        ProviderRuntimeStatus.State state = diagnostics.isEmpty()
                ? ProviderRuntimeStatus.State.READY
                : installed ? ProviderRuntimeStatus.State.BLOCKED : ProviderRuntimeStatus.State.NOT_INSTALLED;
        return new ProviderRuntimeStatus(
                PROVIDER_ID,
                VERSION,
                state,
                installed ? Optional.of(executable) : Optional.empty(),
                diagnostics,
                false
        );
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        requireProvider(providerId);
        Path npm = CommandLocator.find("npm")
                .orElseThrow(() -> new IllegalStateException("npm is required to install scip-python"));
        CommandLocator.find("node")
                .orElseThrow(() -> new IllegalStateException("Node.js 16+ is required to run scip-python"));
        pythonExecutable()
                .orElseThrow(() -> new IllegalStateException("Python 3.10+ is required in PATH to run scip-python"));

        Files.createDirectories(toolsRoot);
        Path destination = root();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        try {
            run(CommandLocator.invocation(
                    npm,
                    "install",
                    "--prefix", partial.toString(),
                    "--no-audit", "--no-fund",
                    "@sourcegraph/scip-python@" + VERSION
            ), home, toolsRoot.resolve("scip-python-install.log"), Duration.ofMinutes(10));
            Path installed = partial.resolve("node_modules").resolve(".bin")
                    .resolve(CommandLocator.isWindows() ? "scip-python.cmd" : "scip-python");
            if (!Files.isRegularFile(installed)) {
                throw new IllegalStateException("scip-python executable was not created: " + installed);
            }
            deleteRecursively(destination);
            move(partial, destination);
        } finally {
            deleteRecursively(partial);
        }
        ProviderRuntimeStatus status = inspect(PROVIDER_ID);
        if (!status.ready()) {
            throw new IllegalStateException("scip-python installation is incomplete: "
                    + String.join("; ", status.diagnostics()));
        }
        return status;
    }

    @Override
    public IndexerExecutor executor(String providerId) {
        ProviderRuntimeStatus status = inspect(providerId);
        if (!status.ready() || status.executable().isEmpty()) {
            throw new IllegalStateException("provider runtime is not ready: " + providerId + " — "
                    + String.join("; ", status.diagnostics()));
        }
        return new ProcessIndexerExecutor(
                providerId,
                home,
                new ScipPythonProcessPlanFactory(status.executable().orElseThrow())
        );
    }

    private Path root() {
        return toolsRoot.resolve(PROVIDER_ID).resolve(VERSION);
    }

    private Path executable() {
        return root().resolve("node_modules").resolve(".bin")
                .resolve(CommandLocator.isWindows() ? "scip-python.cmd" : "scip-python");
    }

    private static Optional<Path> pythonExecutable() {
        Optional<Path> python = CommandLocator.find("python");
        if (python.isPresent()) {
            return python;
        }
        return CommandLocator.find("python3");
    }

    private static void requireProvider(String providerId) {
        if (!PROVIDER_ID.equals(providerId)) {
            throw new IllegalArgumentException("unknown managed provider: " + providerId);
        }
    }

    private static void run(List<String> command, Path workingDirectory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().normalize().getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
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
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
