package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.io.FileTreeOperations;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.BoundedProcessOutput;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Managed runtime extension for @sourcegraph/scip-python. */
public final class ManagedScipPythonRuntimeManager implements ProviderRuntimeManager {
    public static final String PROVIDER_ID = "scip-python";
    public static final String VERSION = ScipIndexerCatalog.SCIP_PYTHON_VERSION;

    private static final String WINDOWS_COMPATIBILITY_PRELOAD = "minos-windows-regexp-compat.cjs";
    private static final String NPM_LOCK_RESOURCE = "scip-python-package-lock.json";
    private static final String NPM_INTEGRITY = "sha512-qoKL1Rggg0o5newAFbCFAKlS0AjWxG5MA+mC28BtgxOv0DhO4zdL8u7151FxEppDpXMVvm7+yXSjXotoVH9cMQ==";
    private static final String WINDOWS_COMPATIBILITY_SOURCE = """
            // MINOS compatibility shim for sourcegraph/scip-python#210 / PR #211.
            // scip-python 0.6.6 evaluates new RegExp(path.sep, 'g'); on Windows path.sep is a lone
            // backslash, which is not a valid regexp pattern. Intercept only that exact constructor
            // input and apply the escaping proposed upstream. Remove this shim when the pinned
            // upstream release contains the fix.
            const NativeRegExp = global.RegExp;
            const normalizeArgs = (args) => {
              if (args.length > 0 && typeof args[0] === 'string' && args[0].length === 1 && args[0].charCodeAt(0) === 92) {
                return [String.fromCharCode(92, 92), ...args.slice(1)];
              }
              return args;
            };
            global.RegExp = new Proxy(NativeRegExp, {
              apply(target, thisArg, args) {
                return Reflect.apply(target, thisArg, normalizeArgs(args));
              },
              construct(target, args, newTarget) {
                return Reflect.construct(target, normalizeArgs(args), newTarget);
              }
            });
            """;

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
        if (CommandLocator.find("node").isEmpty()) diagnostics.add("Node.js 16+ is required by scip-python");
        if (CommandLocator.find("npm").isEmpty()) diagnostics.add("npm is required to install scip-python");
        Optional<Path> python = pythonExecutable();
        if (python.isEmpty()) {
            diagnostics.add("Python 3.10+ is required in PATH by scip-python");
        } else if (pipExecutable(python.orElseThrow()).isEmpty()) {
            diagnostics.add("pip is required by scip-python and must be available with the selected Python runtime");
        }
        Path executable = executable();
        boolean installed = Files.isRegularFile(executable);
        if (!installed) {
            diagnostics.add("managed scip-python " + VERSION + " is not installed");
        } else if (CommandLocator.isWindows()) {
            if (!Files.isRegularFile(packageEntryPoint())) diagnostics.add("managed scip-python package entry point is missing");
            if (!Files.isRegularFile(windowsCompatibilityPreload())) diagnostics.add("managed scip-python Windows compatibility preload is missing");
        }
        ProviderRuntimeStatus.State state = diagnostics.isEmpty()
                ? ProviderRuntimeStatus.State.READY
                : installed ? ProviderRuntimeStatus.State.BLOCKED : ProviderRuntimeStatus.State.NOT_INSTALLED;
        return new ProviderRuntimeStatus(PROVIDER_ID, VERSION, state,
                installed ? Optional.of(executable) : Optional.empty(), diagnostics, false);
    }

    @Override
    public ProviderRuntimeStatus install(String providerId) throws Exception {
        requireProvider(providerId);
        Path npm = CommandLocator.find("npm")
                .orElseThrow(() -> new IllegalStateException("npm is required to install scip-python"));
        CommandLocator.find("node")
                .orElseThrow(() -> new IllegalStateException("Node.js 16+ is required to run scip-python"));
        Path python = pythonExecutable()
                .orElseThrow(() -> new IllegalStateException("Python 3.10+ is required in PATH to run scip-python"));
        pipExecutable(python)
                .orElseThrow(() -> new IllegalStateException(
                        "pip is required by scip-python and must be available with the selected Python runtime"));

        Files.createDirectories(toolsRoot);
        Path destination = root();
        Path partial = destination.resolveSibling(destination.getFileName() + ".partial");
        deleteRecursively(partial);
        Files.createDirectories(partial);
        try {
            LockedNpmPackage.prepare(
                    ManagedScipPythonRuntimeManager.class,
                    partial,
                    NPM_LOCK_RESOURCE,
                    "@sourcegraph/scip-python",
                    VERSION,
                    NPM_INTEGRITY);
            run(CommandLocator.invocation(
                    npm,
                    "ci",
                    "--prefix", partial.toString(),
                    "--no-audit", "--no-fund", "--ignore-scripts"
            ), home, toolsRoot.resolve("scip-python-install.log"), Duration.ofMinutes(10));
            Path installed = partial.resolve("node_modules").resolve(".bin")
                    .resolve(CommandLocator.isWindows() ? "scip-python.cmd" : "scip-python");
            if (!Files.isRegularFile(installed)) {
                throw new IllegalStateException("scip-python executable was not created: " + installed);
            }
            if (CommandLocator.isWindows()) {
                Path entryPoint = partial.resolve("node_modules").resolve("@sourcegraph")
                        .resolve("scip-python").resolve("index.js");
                if (!Files.isRegularFile(entryPoint)) {
                    throw new IllegalStateException("scip-python package entry point was not created: " + entryPoint);
                }
                Files.writeString(partial.resolve(WINDOWS_COMPATIBILITY_PRELOAD),
                        WINDOWS_COMPATIBILITY_SOURCE, StandardCharsets.UTF_8);
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
        Path python = pythonExecutable().orElseThrow();
        Path pip = pipExecutable(python).orElseThrow();
        Map<String, String> providerEnvironment = providerEnvironment(python, pip);
        ScipPythonProcessPlanFactory delegate;
        if (CommandLocator.isWindows()) {
            Path node = CommandLocator.find("node").orElseThrow();
            delegate = new ScipPythonProcessPlanFactory(List.of(
                    node.toAbsolutePath().normalize().toString(),
                    "--require", windowsCompatibilityPreload().toString(),
                    packageEntryPoint().toString()));
        } else {
            delegate = new ScipPythonProcessPlanFactory(status.executable().orElseThrow());
        }
        return new ProcessIndexerExecutor(providerId, home,
                (request, runDirectory) -> withEnvironment(delegate.create(request, runDirectory), providerEnvironment));
    }

    private Path root() {
        return toolsRoot.resolve(PROVIDER_ID).resolve(VERSION);
    }

    private Path executable() {
        return root().resolve("node_modules").resolve(".bin")
                .resolve(CommandLocator.isWindows() ? "scip-python.cmd" : "scip-python");
    }

    private Path packageEntryPoint() {
        return root().resolve("node_modules").resolve("@sourcegraph").resolve("scip-python").resolve("index.js");
    }

    private Path windowsCompatibilityPreload() {
        return root().resolve(WINDOWS_COMPATIBILITY_PRELOAD);
    }

    private static Optional<Path> pythonExecutable() {
        Optional<Path> python = CommandLocator.find("python");
        return python.isPresent() ? python : CommandLocator.find("python3");
    }

    private static Optional<Path> pipExecutable(Path python) {
        Optional<Path> pip = CommandLocator.find("pip3");
        if (pip.isPresent()) return pip;
        pip = CommandLocator.find("pip");
        if (pip.isPresent()) return pip;
        LinkedHashSet<Path> directories = new LinkedHashSet<>();
        Path pythonDirectory = python.toAbsolutePath().normalize().getParent();
        if (pythonDirectory != null) {
            directories.add(pythonDirectory);
            if (CommandLocator.isWindows()) directories.add(pythonDirectory.resolve("Scripts"));
        }
        List<String> names = CommandLocator.isWindows()
                ? List.of("pip3.exe", "pip.exe", "pip3.cmd", "pip.cmd", "pip3", "pip")
                : List.of("pip3", "pip");
        for (Path directory : directories) {
            for (String name : names) {
                Path candidate = directory.resolve(name).toAbsolutePath().normalize();
                if (Files.isRegularFile(candidate)) return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> providerEnvironment(Path python, Path pip) {
        LinkedHashSet<String> directories = new LinkedHashSet<>();
        if (pip.getParent() != null) directories.add(pip.getParent().toAbsolutePath().normalize().toString());
        if (python.getParent() != null) directories.add(python.getParent().toAbsolutePath().normalize().toString());
        String inheritedPath = System.getenv("PATH");
        String prefix = String.join(File.pathSeparator, directories);
        String effectivePath = inheritedPath == null || inheritedPath.isBlank()
                ? prefix : prefix + File.pathSeparator + inheritedPath;
        return Map.of("PATH", effectivePath);
    }

    private static IndexerProcessPlan withEnvironment(IndexerProcessPlan plan, Map<String, String> providerEnvironment) {
        Map<String, String> environment = new LinkedHashMap<>(plan.environment());
        environment.putAll(providerEnvironment);
        return new IndexerProcessPlan(plan.command(), plan.workingDirectory(), environment,
                plan.generatedArtifact(), plan.timeout());
    }

    private static void requireProvider(String providerId) {
        if (!PROVIDER_ID.equals(providerId)) throw new IllegalArgumentException("unknown managed provider: " + providerId);
    }

    private static void run(List<String> command, Path workingDirectory, Path log, Duration timeout)
            throws IOException, InterruptedException {
        Files.createDirectories(log.toAbsolutePath().normalize().getParent());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workingDirectory.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        BoundedProcessOutput.Capture capture = BoundedProcessOutput.capture(process, log, null);
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.descendants().toList().reversed().forEach(handle -> {
                if (handle.isAlive()) handle.destroyForcibly();
            });
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            capture.await();
            throw new IllegalStateException("tool command timed out; see " + log);
        }
        capture.await();
        if (process.exitValue() != 0) {
            throw new IllegalStateException("tool command failed with code " + process.exitValue() + "; see " + log);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        FileTreeOperations.deleteRecursively(path);
    }

    private static void move(Path source, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
