package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
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
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) {
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

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.add("index");
        if (!Files.isRegularFile(root.resolve("tsconfig.json")) && Files.isRegularFile(root.resolve("package.json"))) {
            command.add("--infer-tsconfig");
        }
        return new IndexerProcessPlan(
                List.copyOf(command),
                root,
                Map.of(),
                root.resolve("index.scip"),
                Duration.ofMinutes(30)
        );
    }
}
