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
import java.util.Map;
import java.util.Objects;

/** Fail-closed process plan for the managed scip-go provider. */
public final class ScipGoProcessPlanFactory implements IndexerProcessPlanFactory {
    private final Path executable;

    public ScipGoProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws IOException {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-go incremental execution is not qualified by MINOS M24");
        }
        if (!Files.isRegularFile(root.resolve("go.mod"))) {
            throw new IllegalArgumentException("scip-go M24 qualification requires a canonical go.mod project: " + root);
        }
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("scip-go executable is missing: " + executable);
        }
        Path output = runDirectory.toAbsolutePath().normalize().resolve("index.scip");
        Files.createDirectories(output.getParent());
        return new IndexerProcessPlan(
                CommandLocator.invocation(executable, "--output", output.toString()),
                root,
                Map.of(),
                output,
                Duration.ofMinutes(30)
        );
    }
}
