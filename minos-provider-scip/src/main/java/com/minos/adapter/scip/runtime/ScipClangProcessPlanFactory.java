package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Fail-closed process plan for scip-clang. */
public final class ScipClangProcessPlanFactory extends AbstractScipProcessPlanFactory {

    public ScipClangProcessPlanFactory(Path executable) {
        super(executable, "scip-clang", "scip-clang incremental execution is not qualified by MINOS M24");
    }

    @Override
    protected void validateProject(Path root) {
        // scip-clang validates its compilation database after executable validation in command().
    }

    @Override
    protected List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) {
        Path compilationDatabase = compilationDatabase(root);
        return CommandLocator.invocation(
                executable(),
                "--compdb-path=" + compilationDatabase,
                "--index-output-path=" + output);
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
