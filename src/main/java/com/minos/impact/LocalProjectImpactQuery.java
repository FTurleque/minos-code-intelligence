package com.minos.impact;

import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bootstrap local M8 : registre projet + snapshot de connaissance actif.
 */
public final class LocalProjectImpactQuery implements ProjectImpactQuery {

    private final LocalProjectRegistry projectRegistry;
    private final FileSymbolSnapshotStore snapshotStore;
    private final ImpactAnalysisService impactService;

    public LocalProjectImpactQuery(LocalProjectRegistry projectRegistry, FileSymbolSnapshotStore snapshotStore) {
        this(projectRegistry, snapshotStore, new ImpactAnalysisService());
    }

    LocalProjectImpactQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore,
            ImpactAnalysisService impactService
    ) {
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
        this.impactService = Objects.requireNonNull(impactService, "impactService");
    }

    @Override
    public ImpactAnalysisReport analyzeImpact(String projectIdentifier, ImpactAnalysisRequest request) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active code knowledge snapshot: " + project.id()
                ));
        return impactService.analyze(snapshot, request);
    }

    private RegisteredProject resolveProject(String identifier) throws IOException {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("project identifier must not be blank");
        }
        UUID projectId = parseUuid(identifier);
        if (projectId != null) {
            return projectRegistry.findProject(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown project: " + identifier));
        }
        List<RegisteredProject> matches = projectRegistry.listProjects().stream()
                .filter(project -> identifier.equals(project.displayName()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown project: " + identifier);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("ambiguous project name, use its UUID: " + identifier);
        }
        return matches.getFirst();
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
