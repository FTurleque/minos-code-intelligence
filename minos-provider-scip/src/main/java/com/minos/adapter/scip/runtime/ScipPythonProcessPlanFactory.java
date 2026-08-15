package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Process plan for the managed scip-python provider. */
public final class ScipPythonProcessPlanFactory implements IndexerProcessPlanFactory {
    private final List<String> launcher;

    public ScipPythonProcessPlanFactory(Path executable) {
        this(CommandLocator.invocation(Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize()));
    }

    ScipPythonProcessPlanFactory(List<String> launcher) {
        Objects.requireNonNull(launcher, "launcher");
        if (launcher.isEmpty() || launcher.stream().anyMatch(value -> value == null || value.isBlank())) {
            throw new IllegalArgumentException("launcher must contain non-blank arguments");
        }
        this.launcher = List.copyOf(launcher);
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws Exception {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!containsPythonSource(root)) {
            throw new IllegalArgumentException("scip-python requires at least one .py source: " + root);
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-python incremental execution is not qualified by MINOS M17");
        }
        Path output = runDirectory.toAbsolutePath().normalize().resolve("index.scip");
        Files.createDirectories(output.getParent());
        String projectName = root.getFileName() == null ? "minos-python-project" : root.getFileName().toString();
        List<String> command = new ArrayList<>(launcher);
        command.addAll(List.of(
                "index",
                "--cwd", root.toString(),
                "--output", output.toString(),
                "--project-name", projectName,
                "--project-version", "_",
                "--quiet"
        ));
        return new IndexerProcessPlan(
                command,
                root,
                Map.of(),
                output,
                Duration.ofMinutes(30)
        );
    }

    private static boolean containsPythonSource(Path root) throws IOException {
        return BoundedProviderSourceProbe.contains(
                root,
                Integer.MAX_VALUE,
                "scip-python source preflight",
                name -> name.toLowerCase(Locale.ROOT).endsWith(".py")
        );
    }
}
