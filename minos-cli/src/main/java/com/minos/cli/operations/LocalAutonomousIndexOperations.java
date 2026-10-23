package com.minos.cli.operations;

import com.minos.application.MinosApplication;
import com.minos.application.ProviderPlatformService;
import com.minos.application.ProviderRuntimeSummary;
import com.minos.application.indexing.ProjectIndexingUseCase;
import com.minos.orchestration.IndexingResumePolicy;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Autonomous indexing adapter over the selected MINOS storage backend. */
public final class LocalAutonomousIndexOperations
        implements AutonomousIndexOperations, AutoCloseable {
    private final MinosApplication ownedApplication;
    private final MinosApplication application;
    private final ProjectIndexingUseCase indexing;

    public LocalAutonomousIndexOperations(Path minosHome) throws IOException {
        this(MinosApplication.open(minosHome), UnaryOperator.identity(), true);
    }

    public LocalAutonomousIndexOperations(MinosApplication application) {
        this(application, UnaryOperator.identity(), false);
    }

    public LocalAutonomousIndexOperations(
            MinosApplication application,
            UnaryOperator<IndexerExecutor> executorDecorator
    ) {
        this(application, executorDecorator, false);
    }

    private LocalAutonomousIndexOperations(
            MinosApplication application,
            UnaryOperator<IndexerExecutor> executorDecorator,
            boolean ownsApplication
    ) {
        Objects.requireNonNull(application, "application");
        this.ownedApplication = ownsApplication ? application : null;
        this.application = application;
        // AUD-ARC-02: the orchestration lives in the application use case; this surface only projects its result.
        this.indexing = ProjectIndexingUseCase.of(application, executorDecorator);
    }

    @Override
    public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return view(indexing.plan(projectIdentifier, providerOverride, forceFull));
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) throws Exception {
        return execute(projectIdentifier, providerOverride, forceFull, IndexingResumePolicy.RESUME);
    }

    @Override
    public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull,
                                      IndexingResumePolicy resumePolicy) throws Exception {
        ProjectIndexingUseCase.Outcome outcome =
                indexing.execute(projectIdentifier, providerOverride, forceFull, resumePolicy);
        return new IndexExecutionView(view(outcome.preparation()), outcome.run().map(run -> run.id().toString()).orElse(null),
                outcome.status(), outcome.activeSnapshotId().orElse(null), outcome.fingerprintPromoted(),
                outcome.diagnostic().orElse(null), outcome.run().map(LocalAutonomousIndexOperations::resumeView).orElse(null));
    }

    private static ResumeView resumeView(IndexingRun run) {
        return run.resume()
                .map(trace -> new ResumeView(trace.attempt(), trace.reusedTargets(), trace.reexecutedTargets(),
                        trace.refusalReason().orElse(null)))
                .orElse(null);
    }

    @Override public List<ProviderView> providers() {
        return ProviderPlatformService.defaults(application).runtimes().stream()
                .map(LocalAutonomousIndexOperations::view).toList();
    }

    @Override public ProviderView installProvider(String providerId) throws Exception {
        return view(ProviderPlatformService.defaults(application).installRuntime(providerId));
    }

    private static IndexPlanView view(ProjectIndexingUseCase.Preparation prepared) {
        var project = prepared.project();
        return new IndexPlanView(
                project.id().toString(), project.displayName(), project.rootPath().toString(),
                prepared.discovery().languages().stream().map(Enum::name).sorted().toList(),
                prepared.discovery().buildSystems().stream().map(Enum::name).sorted().toList(),
                prepared.providerIds(),
                prepared.providerRuntimes().stream().map(ProviderRuntimeSummary::of)
                        .map(LocalAutonomousIndexOperations::view).toList(),
                prepared.effectiveMode(), prepared.reasons(), prepared.changedFiles(), prepared.forcedFull());
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
