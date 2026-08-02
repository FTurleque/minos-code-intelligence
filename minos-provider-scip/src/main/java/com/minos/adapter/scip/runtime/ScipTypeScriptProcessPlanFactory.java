package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Plan d'exécution du provider scip-typescript. */
public final class ScipTypeScriptProcessPlanFactory implements IndexerProcessPlanFactory {

    private final Path executable;

    public ScipTypeScriptProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws java.io.IOException {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (!Files.isRegularFile(root.resolve("tsconfig.json"))
                && !Files.isRegularFile(root.resolve("package.json"))) {
            throw new IllegalArgumentException("scip-typescript requires tsconfig.json or package.json: " + root);
        }
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("scip-typescript executable is missing: " + executable);
        }
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-typescript incremental execution is not qualified");
        }

        Path output = runDirectory.toAbsolutePath().normalize().resolve("index.scip");
        Files.createDirectories(output.getParent());
        List<String> arguments = new ArrayList<>();
        arguments.add("index");
        arguments.add("--output");
        arguments.add(output.toString());
        if (!Files.isRegularFile(root.resolve("tsconfig.json")) && Files.isRegularFile(root.resolve("package.json"))) {
            arguments.add("--infer-tsconfig");
        }
        return new IndexerProcessPlan(
                CommandLocator.invocation(executable, arguments.toArray(String[]::new)),
                root,
                Map.of(),
                output,
                Duration.ofMinutes(30)
        );
    }
}
