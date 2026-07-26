package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Process plan for the managed scip-python provider. */
public final class ScipPythonProcessPlanFactory implements IndexerProcessPlanFactory {
    private final Path executable;

    public ScipPythonProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws Exception {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!containsPythonSource(root)) {
            throw new IllegalArgumentException("scip-python requires at least one .py source: " + root);
        }
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("scip-python executable is missing: " + executable);
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-python incremental execution is not qualified by MINOS M17");
        }
        String projectName = root.getFileName() == null ? "minos-python-project" : root.getFileName().toString();
        return new IndexerProcessPlan(
                CommandLocator.invocation(
                        executable,
                        "index", ".",
                        "--project-name", projectName,
                        "--project-version", "_"
                ),
                root,
                Map.of(),
                root.resolve("index.scip"),
                Duration.ofMinutes(30)
        );
    }

    private static boolean containsPythonSource(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .anyMatch(path -> path.getFileName().toString().toLowerCase().endsWith(".py"));
        }
    }
}
