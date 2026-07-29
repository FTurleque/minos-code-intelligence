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

/** Fail-closed process plan for scip-clang. */
public final class ScipClangProcessPlanFactory implements IndexerProcessPlanFactory {
    private final Path executable;

    public ScipClangProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-clang incremental execution is not qualified by MINOS M24");
        }
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("scip-clang executable is missing: " + executable);
        }
        Path compilationDatabase = compilationDatabase(root);
        return new IndexerProcessPlan(
                CommandLocator.invocation(executable, "--compdb-path=" + compilationDatabase),
                root,
                Map.of(),
                root.resolve("index.scip"),
                Duration.ofMinutes(30)
        );
    }

    static Path compilationDatabase(Path root) {
        for (Path candidate : List.of(
                root.resolve("compile_commands.json"),
                root.resolve("build").resolve("compile_commands.json")
        )) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        throw new IllegalArgumentException(
                "scip-clang requires compile_commands.json at project root or build/compile_commands.json: " + root);
    }
}
