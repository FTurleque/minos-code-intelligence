package com.minos.cli;

import com.minos.orchestration.IndexingMode;

import java.util.List;
import java.util.Objects;

/** CLI port for autonomous indexing and provider runtime administration. */
public interface AutonomousIndexOperations {

    IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception;

    IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception;

    List<ProviderView> providers();

    ProviderView installProvider(String providerId) throws Exception;

    record ProviderView(
            String id,
            String version,
            String state,
            String executable,
            List<String> diagnostics,
            boolean requiredByDefault
    ) {
        public ProviderView {
            requireText(id, "id");
            requireText(version, "version");
            requireText(state, "state");
            diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        }

        /** Compatibility constructor for historical baseline-required providers. */
        public ProviderView(String id, String version, String state, String executable, List<String> diagnostics) {
            this(id, version, state, executable, diagnostics, true);
        }
    }

    record IndexPlanView(
            String projectId,
            String projectName,
            String rootPath,
            List<String> languages,
            List<String> buildSystems,
            List<String> providerIds,
            List<ProviderView> providerRuntimes,
            IndexingMode mode,
            List<String> reasons,
            List<String> changedFiles,
            boolean forcedFull
    ) {
        public IndexPlanView {
            requireText(projectId, "projectId");
            requireText(projectName, "projectName");
            requireText(rootPath, "rootPath");
            languages = List.copyOf(Objects.requireNonNull(languages, "languages"));
            buildSystems = List.copyOf(Objects.requireNonNull(buildSystems, "buildSystems"));
            providerIds = List.copyOf(Objects.requireNonNull(providerIds, "providerIds"));
            providerRuntimes = List.copyOf(Objects.requireNonNull(providerRuntimes, "providerRuntimes"));
            Objects.requireNonNull(mode, "mode");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
            changedFiles = List.copyOf(Objects.requireNonNull(changedFiles, "changedFiles"));
        }
    }

    record IndexExecutionView(
            IndexPlanView plan,
            String runId,
            String status,
            String activeSnapshotId,
            boolean fingerprintPromoted,
            String diagnostic
    ) {
        public IndexExecutionView {
            Objects.requireNonNull(plan, "plan");
            requireText(status, "status");
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
