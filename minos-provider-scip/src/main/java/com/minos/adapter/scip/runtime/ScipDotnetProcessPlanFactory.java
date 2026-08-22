package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.CommandLocator;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/** Fail-closed process plan for the managed scip-dotnet provider. */
public final class ScipDotnetProcessPlanFactory extends AbstractScipProcessPlanFactory {

    public ScipDotnetProcessPlanFactory(Path executable) {
        super(executable, "scip-dotnet", "scip-dotnet incremental execution is not qualified by MINOS M24");
    }

    @Override
    protected void validateProject(Path root) throws IOException {
        if (!containsDotnetProject(root)) {
            throw new IllegalArgumentException("scip-dotnet requires a .csproj or .sln project: " + root);
        }
    }

    @Override
    protected List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) {
        return CommandLocator.invocation(executable(), "index", "--output", output.toString());
    }

    private static boolean containsDotnetProject(Path root) throws IOException {
        return BoundedProviderSourceProbe.contains(
                root,
                3,
                "scip-dotnet project preflight",
                name -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".csproj") || lower.endsWith(".sln");
                }
        );
    }
}
