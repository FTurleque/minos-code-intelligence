package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fail-closed process plan for the managed scip-go provider. */
public final class ScipGoProcessPlanFactory extends AbstractScipProcessPlanFactory {

    public ScipGoProcessPlanFactory(Path executable) {
        super(executable, "scip-go", "scip-go incremental execution is not qualified by MINOS M24");
    }

    @Override
    protected void validateProject(Path root) {
        if (!Files.isRegularFile(root.resolve("go.mod"))) {
            throw new IllegalArgumentException("scip-go M24 qualification requires a canonical go.mod project: " + root);
        }
    }

    @Override
    protected List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) {
        return CommandLocator.invocation(executable(), "--output", output.toString());
    }
}
