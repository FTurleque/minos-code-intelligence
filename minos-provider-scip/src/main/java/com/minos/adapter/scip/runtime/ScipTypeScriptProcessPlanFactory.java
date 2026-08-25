package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Plan d'exécution du provider scip-typescript. */
public final class ScipTypeScriptProcessPlanFactory extends AbstractScipProcessPlanFactory {

    public ScipTypeScriptProcessPlanFactory(Path executable) {
        super(executable, "scip-typescript", "scip-typescript incremental execution is not qualified");
    }

    @Override
    protected void validateProject(Path root) {
        if (!Files.isRegularFile(root.resolve("tsconfig.json"))
                && !Files.isRegularFile(root.resolve("package.json"))) {
            throw new IllegalArgumentException("scip-typescript requires tsconfig.json or package.json: " + root);
        }
    }

    @Override
    protected List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) throws IOException {
        List<String> arguments = new ArrayList<>();
        arguments.add("index");
        arguments.add("--output");
        arguments.add(output.toString());
        if (!Files.isRegularFile(root.resolve("tsconfig.json")) && Files.isRegularFile(root.resolve("package.json"))) {
            arguments.add("--infer-tsconfig");
        }
        return CommandLocator.invocation(executable(), arguments.toArray(String[]::new));
    }
}
