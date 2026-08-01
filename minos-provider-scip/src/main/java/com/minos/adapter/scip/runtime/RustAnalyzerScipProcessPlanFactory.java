package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Fail-closed process plan for rust-analyzer's native SCIP command. */
public final class RustAnalyzerScipProcessPlanFactory implements IndexerProcessPlanFactory {
    private final Path executable;

    public RustAnalyzerScipProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("rust-analyzer SCIP incremental execution is not qualified by MINOS M24");
        }
        if (!Files.isRegularFile(root.resolve("Cargo.toml"))) {
            throw new IllegalArgumentException("rust-analyzer scip requires Cargo.toml: " + root);
        }
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("rust-analyzer executable is missing: " + executable);
        }
        return new IndexerProcessPlan(
                CommandLocator.invocation(executable, "scip", "."),
                root,
                Map.of(),
                root.resolve("index.scip"),
                Duration.ofMinutes(30)
        );
    }
}
