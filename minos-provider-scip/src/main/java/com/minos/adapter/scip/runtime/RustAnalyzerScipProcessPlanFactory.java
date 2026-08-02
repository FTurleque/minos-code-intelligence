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

/** Fail-closed process plan for rust-analyzer's native SCIP command. */
public final class RustAnalyzerScipProcessPlanFactory implements IndexerProcessPlanFactory {
    private final Path executable;

    public RustAnalyzerScipProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws IOException {
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
        Path runRoot = runDirectory.toAbsolutePath().normalize();
        Path output = runRoot.resolve("index.scip");
        Path cargoTarget = runRoot.resolve("cargo-target");
        Files.createDirectories(output.getParent());
        Files.createDirectories(cargoTarget);
        return new IndexerProcessPlan(
                CommandLocator.invocation(executable, "scip", ".", "--output", output.toString()),
                root,
                Map.of("CARGO_TARGET_DIR", cargoTarget.toString()),
                output,
                Duration.ofMinutes(30)
        );
    }
}
