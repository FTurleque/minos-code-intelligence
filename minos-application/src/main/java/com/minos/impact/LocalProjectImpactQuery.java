package com.minos.impact;

import com.minos.application.ProjectResolver;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.Objects;

/**
 * Bootstrap local M8 : résolution projet + snapshot de connaissance actif.
 */
public final class LocalProjectImpactQuery implements ProjectImpactQuery {

    private final ProjectResolver projectResolver;
    private final FileSymbolSnapshotStore snapshotStore;
    private final ImpactAnalysisService impactService;

    public LocalProjectImpactQuery(LocalProjectRegistry projectRegistry, FileSymbolSnapshotStore snapshotStore) {
        this(new ProjectResolver(projectRegistry), snapshotStore, new ImpactAnalysisService());
    }

    public LocalProjectImpactQuery(ProjectResolver projectResolver, FileSymbolSnapshotStore snapshotStore) {
        this(projectResolver, snapshotStore, new ImpactAnalysisService());
    }

    LocalProjectImpactQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ImpactAnalysisService impactService
    ) {
        this(new ProjectResolver(projectRegistry), snapshotStore, impactService);
    }

    private LocalProjectImpactQuery(
            ProjectResolver projectResolver,
            FileSymbolSnapshotStore snapshotStore,
            ImpactAnalysisService impactService
    ) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.impactService = Objects.requireNonNull(impactService, "impactService");
    }

    @Override
    public ImpactAnalysisReport analyzeImpact(String projectIdentifier, ImpactAnalysisRequest request) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active code knowledge snapshot: " + project.id()
                ));
        return impactService.analyze(snapshot, request);
    }
}
