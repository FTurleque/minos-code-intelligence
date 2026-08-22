package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Fail-closed process plan for rust-analyzer's native SCIP command. */
public final class RustAnalyzerScipProcessPlanFactory extends AbstractScipProcessPlanFactory {

    public RustAnalyzerScipProcessPlanFactory(Path executable) {
        super(executable, "rust-analyzer", "rust-analyzer SCIP incremental execution is not qualified by MINOS M24");
    }

    @Override
    protected void validateProject(Path root) {
        if (!Files.isRegularFile(root.resolve("Cargo.toml"))) {
            throw new IllegalArgumentException("rust-analyzer scip requires Cargo.toml: " + root);
        }
    }

    @Override
    protected Map<String, String> environment(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) throws IOException {
        Path cargoTarget = runRoot.resolve("cargo-target");
        Files.createDirectories(cargoTarget);
        return Map.of("CARGO_TARGET_DIR", cargoTarget.toString());
    }

    @Override
    protected List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) {
        return CommandLocator.invocation(executable(), "scip", ".", "--output", output.toString());
    }
}
