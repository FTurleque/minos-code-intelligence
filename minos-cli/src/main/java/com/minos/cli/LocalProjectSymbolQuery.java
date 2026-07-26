package com.minos.cli;

import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.CodeSearchService;
import com.minos.context.LocalSourceReader;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.RelationshipQueryService;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolQueryService;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import com.minos.store.InMemoryCodeKnowledgeStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Bootstrap local de la requête projet vers le snapshot de symboles actif.
 */
public final class LocalProjectSymbolQuery implements ProjectSymbolQuery {

    private final LocalProjectRegistry projectRegistry;
    private final FileSymbolSnapshotStore snapshotStore;

    public LocalProjectSymbolQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore
    ) {
        this.projectRegistry = Objects.requireNonNull(projectRegistry, "projectRegistry");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    }

    @Override
    public List<SymbolResult> findSymbols(
            String projectIdentifier,
            SymbolSearchCriteria criteria
    ) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        return loadQueryService(project).findSymbolResults(
                project.id().toString(),
                Objects.requireNonNull(criteria, "criteria")
        );
    }

    @Override
    public List<SymbolResult> getFileSymbols(
            String projectIdentifier,
            String fileId,
            int limit
    ) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        return loadQueryService(project).getFileSymbolResults(
                project.id().toString(),
                fileId,
                limit
        );
    }

    @Override
    public List<UsageResult> findUsages(
            String projectIdentifier,
            String symbolId,
            int limit
    ) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        return new SymbolQueryService(loadQueryStore(project)).findUsageResults(
                project.id().toString(),
                symbolId,
                limit
        );
    }

    @Override
    public List<RelationshipResult> findRelationships(
            String projectIdentifier,
            RelationshipSearchCriteria criteria
    ) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        return new RelationshipQueryService(loadQueryStore(project)).findRelationshipResults(
                project.id().toString(),
                Objects.requireNonNull(criteria, "criteria")
        );
    }

    @Override
    public CodeSearchResponse searchCode(
            String projectIdentifier,
            CodeSearchCriteria criteria
    ) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        return new CodeSearchService(
                loadQueryStore(project),
                new LocalSourceReader(project.rootPath())
        ).search(project.id().toString(), Objects.requireNonNull(criteria, "criteria"));
    }

    @Override
    public SourceExcerpt getSource(String projectIdentifier, String fileId) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        return new LocalSourceReader(project.rootPath()).readFull(fileId);
    }

    private SymbolQueryService loadQueryService(RegisteredProject project) throws IOException {
        return new SymbolQueryService(loadQueryStore(project));
    }

    private InMemoryCodeKnowledgeStore loadQueryStore(RegisteredProject project) throws IOException {
        CodeKnowledgeSnapshot snapshot = snapshotStore.loadActiveKnowledge(project.id())
                .orElseThrow(() -> new IllegalStateException(
                        "project has no active symbol snapshot: " + project.id()
                ));

        InMemoryCodeKnowledgeStore queryStore = new InMemoryCodeKnowledgeStore();
        queryStore.putSymbols(snapshot.symbols());
        queryStore.putOccurrences(snapshot.occurrences());
        queryStore.putRelationships(snapshot.relationships());
        return queryStore;
    }

    private RegisteredProject resolveProject(String identifier) throws IOException {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("project identifier must not be blank");
        }

        UUID projectId = parseUuid(identifier);
        if (projectId != null) {
            return projectRegistry.findProject(projectId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown project: " + identifier
                    ));
        }

        List<RegisteredProject> matches = projectRegistry.listProjects().stream()
                .filter(project -> identifier.equals(project.displayName()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown project: " + identifier);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException(
                    "ambiguous project name, use its UUID: " + identifier
            );
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
