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

/** Fail-closed process plan for the managed scip-dotnet provider. */
public final class ScipDotnetProcessPlanFactory implements IndexerProcessPlanFactory {
    private final Path executable;

    public ScipDotnetProcessPlanFactory(Path executable) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
    }

    @Override
    public IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws IOException {
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException("scip-dotnet incremental execution is not qualified by MINOS M24");
        }
        if (!containsDotnetProject(root)) {
            throw new IllegalArgumentException("scip-dotnet requires a .csproj or .sln project: " + root);
        }
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException("scip-dotnet executable is missing: " + executable);
        }
        Path output = runDirectory.toAbsolutePath().normalize().resolve("index.scip");
        Files.createDirectories(output.getParent());
        return new IndexerProcessPlan(
                CommandLocator.invocation(executable, "index", "--output", output.toString()),
                root,
                Map.of(),
                output,
                Duration.ofMinutes(30)
        );
    }

    private static boolean containsDotnetProject(Path root) throws IOException {
        try (var paths = Files.walk(root, 3)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT))
                    .anyMatch(name -> name.endsWith(".csproj") || name.endsWith(".sln"));
        }
    }
}
