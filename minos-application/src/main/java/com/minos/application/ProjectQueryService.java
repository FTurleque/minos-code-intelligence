package com.minos.application;

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

/**
 * Application-level read service over the active Code Intelligence snapshot of a project.
 *
 * <p>Transport adapters must use this service instead of rebuilding snapshot/query plumbing.
 * Project references are resolved exclusively through the shared {@link ProjectResolver}.</p>
 */
public final class ProjectQueryService {

    private final ProjectResolver projectResolver;
    private final FileSymbolSnapshotStore snapshotStore;

    public ProjectQueryService(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore
    ) {
        this(new ProjectResolver(projectRegistry), snapshotStore);
    }

    public ProjectQueryService(
            ProjectResolver projectResolver,
            FileSymbolSnapshotStore snapshotStore
    ) {
        this.projectResolver = Objects.requireNonNull(projectResolver, "projectResolver");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore");
    }

    public List<SymbolResult> findSymbols(
            String projectIdentifier,
            SymbolSearchCriteria criteria
    ) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        return loadQueryService(project).findSymbolResults(
                project.id().toString(),
                Objects.requireNonNull(criteria, "criteria")
        );
    }

    public List<SymbolResult> getFileSymbols(
            String projectIdentifier,
            String fileId,
            int limit
    ) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        return loadQueryService(project).getFileSymbolResults(
                project.id().toString(),
                fileId,
                limit
        );
    }

    public List<UsageResult> findUsages(
            String projectIdentifier,
            String symbolId,
            int limit
    ) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        return new SymbolQueryService(loadQueryStore(project)).findUsageResults(
                project.id().toString(),
                symbolId,
                limit
        );
    }

    public List<RelationshipResult> findRelationships(
            String projectIdentifier,
            RelationshipSearchCriteria criteria
    ) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        return new RelationshipQueryService(loadQueryStore(project)).findRelationshipResults(
                project.id().toString(),
                Objects.requireNonNull(criteria, "criteria")
        );
    }

    public CodeSearchResponse searchCode(
            String projectIdentifier,
            CodeSearchCriteria criteria
    ) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        return new CodeSearchService(
                loadQueryStore(project),
                new LocalSourceReader(project.rootPath())
        ).search(project.id().toString(), Objects.requireNonNull(criteria, "criteria"));
    }

    public SourceExcerpt getSource(String projectIdentifier, String fileId) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
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
}
