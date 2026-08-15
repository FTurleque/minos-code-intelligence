package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.runtime.IndexerProcessPlan;
import com.minos.runtime.IndexerProcessPlanFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared fail-closed skeleton for executable-backed SCIP process plans. */
abstract class AbstractScipProcessPlanFactory implements IndexerProcessPlanFactory {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(30);

    private final Path executable;
    private final String providerId;
    private final String incrementalDiagnostic;

    AbstractScipProcessPlanFactory(Path executable, String providerId, String incrementalDiagnostic) {
        this.executable = Objects.requireNonNull(executable, "executable").toAbsolutePath().normalize();
        this.providerId = requireText(providerId, "providerId");
        this.incrementalDiagnostic = requireText(incrementalDiagnostic, "incrementalDiagnostic");
    }

    @Override
    public final IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws IOException {
        Objects.requireNonNull(request, "request");
        Path root = request.projectRoot().toAbsolutePath().normalize();
        if (request.mode() == IndexingMode.INCREMENTAL) {
            throw new IllegalStateException(incrementalDiagnostic);
        }
        validateProject(root);
        if (!Files.isRegularFile(executable)) {
            throw new IllegalStateException(providerId + " executable is missing: " + executable);
        }
        Path runRoot = Objects.requireNonNull(runDirectory, "runDirectory").toAbsolutePath().normalize();
        Path output = runRoot.resolve("index.scip");
        Files.createDirectories(output.getParent());
        Map<String, String> environment = environment(request, root, runRoot, output);
        List<String> command = command(request, root, runRoot, output);
        return new IndexerProcessPlan(command, root, environment, output, timeout());
    }

    protected abstract void validateProject(Path root) throws IOException;

    protected abstract List<String> command(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) throws IOException;

    protected Map<String, String> environment(
            IndexingExecutionRequest request,
            Path root,
            Path runRoot,
            Path output
    ) throws IOException {
        return Map.of();
    }

    protected Duration timeout() {
        return DEFAULT_TIMEOUT;
    }

    protected final Path executable() {
        return executable;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value;
    }
}
