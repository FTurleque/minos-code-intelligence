package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;
import com.minos.application.ProviderRuntimeSummary;
import com.minos.application.indexing.IndexExecutionSummary;
import com.minos.application.indexing.IndexPlanSummary;
import com.minos.application.indexing.IndexingResumeChoice;
import com.minos.application.indexing.ProjectIndexingUseCase;
import com.minos.application.indexing.ResumeTraceSummary;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Autonomous indexing over the selected MINOS storage backend. The orchestration is the application use case
 * (AUD-ARC-02) and the provider runtimes are administered by {@link ProviderPlatformService}; this class only
 * projects their summaries into the views of the command (AUD-ARC-01).
 */
public final class LocalAutonomousIndexOperations implements AutonomousIndexOperations, AutoCloseable {
    private final MinosApplication ownedApplication;
    private final MinosApplication application;
    private final ProjectIndexingUseCase indexing;

    public LocalAutonomousIndexOperations(Path minosHome) throws IOException {
        this(MinosApplication.open(minosHome), true);
    }

    public LocalAutonomousIndexOperations(MinosApplication application) {
        this(application, false);
    }

    private LocalAutonomousIndexOperations(MinosApplication application, boolean ownsApplication) {
        this.application = Objects.requireNonNull(application, "application");
        this.ownedApplication = ownsApplication ? application : null;
        this.indexing = ProjectIndexingUseCase.of(application);
    }

    @Override
    public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return view(indexing.planSummary(projectIdentifier, providerOverride, forceFull));
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return execute(projectIdentifier, providerOverride, forceFull, IndexingResumeChoice.RESUME);
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull,
                                      IndexingResumeChoice resumeChoice) throws Exception {
        return view(indexing.executeSummary(projectIdentifier, providerOverride, forceFull, resumeChoice));
    }

    @Override public List<ProviderView> providers() {
        return ProviderPlatformService.defaults(application).runtimes().stream()
                .map(LocalAutonomousIndexOperations::view).toList();
    }

    @Override public ProviderView installProvider(String providerId) throws Exception {
        return view(ProviderPlatformService.defaults(application).installRuntime(providerId));
    }

    /** The view of an execution summary, shared with the remote indexing of the command. */
    static IndexExecutionView view(IndexExecutionSummary execution) {
        return new IndexExecutionView(view(execution.plan()), execution.runId().orElse(null), execution.status(),
                execution.activeSnapshotId().orElse(null), execution.fingerprintPromoted(),
                execution.diagnostic().orElse(null),
                execution.resumed().map(LocalAutonomousIndexOperations::view).orElse(null));
    }

    private static ResumeView view(ResumeTraceSummary trace) {
        return new ResumeView(trace.attempt(), trace.reusedTargets(), trace.reexecutedTargets(),
                trace.refusalReason().orElse(null));
    }

    private static IndexPlanView view(IndexPlanSummary plan) {
        return new IndexPlanView(
                plan.projectId(), plan.projectName(), plan.rootPath(), plan.languages(), plan.buildSystems(),
                plan.providerIds(), plan.providerRuntimes().stream().map(LocalAutonomousIndexOperations::view).toList(),
                plan.mode(), plan.reasons(), plan.changedFiles(), plan.forcedFull());
    }

    private static ProviderView view(ProviderRuntimeSummary runtime) {
        return new ProviderView(runtime.providerId(), runtime.version(), runtime.state(),
                runtime.executable().orElse(null), runtime.diagnostics(), runtime.requiredByDefault());
    }

    @Override
    public void close() throws IOException {
        if (ownedApplication != null) ownedApplication.close();
    }
}
