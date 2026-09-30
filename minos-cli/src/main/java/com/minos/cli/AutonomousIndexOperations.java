package com.minos.cli;

import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingResumePolicy;

import java.util.List;
import java.util.Objects;

/** CLI port for autonomous indexing and provider runtime administration. */
public interface AutonomousIndexOperations {

    IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception;

    IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception;

    /**
     * Executes with an explicit resume policy (ADR 0039 §6). Backends without resume control accept
     * only {@link IndexingResumePolicy#RESUME}, which is the default behaviour of {@link #execute}.
     * {@link IndexingResumePolicy#NO_RESUME} is a complete run: it never reopens an interrupted run,
     * supersedes it and indexes everything, as if {@code forceFull} were set.
     */
    default IndexExecutionView execute(
            String projectIdentifier,
            String providerOverride,
            boolean forceFull,
            IndexingResumePolicy resumePolicy
    ) throws Exception {
        if (Objects.requireNonNull(resumePolicy, "resumePolicy") != IndexingResumePolicy.RESUME) {
            throw new IllegalStateException("resume control is not supported by this indexing backend");
        }
        return execute(projectIdentifier, providerOverride, forceFull);
    }

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
            diagnostics = CliCommandSupport.publicDiagnostics(
                    List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics")));
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

    /** Outcome of the resume considered for a run (ADR 0039 §6); {@code refusalReason} is public text. */
    record ResumeView(int attempt, int reusedTargets, int reexecutedTargets, String refusalReason) {
        public ResumeView {
            if (attempt < 1) throw new IllegalArgumentException("attempt must be positive");
            if (reusedTargets < 0 || reexecutedTargets < 0) {
                throw new IllegalArgumentException("target counts must not be negative");
            }
            refusalReason = CliCommandSupport.publicDiagnostic(refusalReason);
        }
    }

    record IndexExecutionView(
            IndexPlanView plan,
            String runId,
            String status,
            String activeSnapshotId,
            boolean fingerprintPromoted,
            String diagnostic,
            ResumeView resumed
    ) {
        public IndexExecutionView {
            Objects.requireNonNull(plan, "plan");
            requireText(status, "status");
            diagnostic = CliCommandSupport.publicDiagnostic(diagnostic);
        }

        /** Compatibility constructor: no resume was considered. */
        public IndexExecutionView(
                IndexPlanView plan,
                String runId,
                String status,
                String activeSnapshotId,
                boolean fingerprintPromoted,
                String diagnostic
        ) {
            this(plan, runId, status, activeSnapshotId, fingerprintPromoted, diagnostic, null);
        }
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }
}
