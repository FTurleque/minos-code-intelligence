package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.application.ProjectQueryService;
import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;
import com.minos.registry.LocalProjectRegistry;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * CLI adapter over the shared application-level project query service.
 */
public final class LocalProjectSymbolQuery implements ProjectSymbolQuery {

    private final ProjectQueryService service;

    public LocalProjectSymbolQuery(
            LocalProjectRegistry projectRegistry,
            FileSymbolSnapshotStore snapshotStore
    ) {
        this(new ProjectQueryService(projectRegistry, snapshotStore));
    }

    public LocalProjectSymbolQuery(MinosApplication application) {
        Objects.requireNonNull(application, "application");
        this.service = application.projectQueryService();
    }

    private LocalProjectSymbolQuery(ProjectQueryService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    @Override
    public List<SymbolResult> findSymbols(
            String projectIdentifier,
            SymbolSearchCriteria criteria
    ) throws IOException {
        return service.findSymbols(projectIdentifier, criteria);
    }

    @Override
    public List<SymbolResult> getFileSymbols(
            String projectIdentifier,
            String fileId,
            int limit
    ) throws IOException {
        return service.getFileSymbols(projectIdentifier, fileId, limit);
    }

    @Override
    public List<UsageResult> findUsages(
            String projectIdentifier,
            String symbolId,
            int limit
    ) throws IOException {
        return service.findUsages(projectIdentifier, symbolId, limit);
    }

    @Override
    public List<RelationshipResult> findRelationships(
            String projectIdentifier,
            RelationshipSearchCriteria criteria
    ) throws IOException {
        return service.findRelationships(projectIdentifier, criteria);
    }

    @Override
    public CodeSearchResponse searchCode(
            String projectIdentifier,
            CodeSearchCriteria criteria
    ) throws IOException {
        return service.searchCode(projectIdentifier, criteria);
    }

    @Override
    public SourceExcerpt getSource(String projectIdentifier, String fileId) throws IOException {
        return service.getSource(projectIdentifier, fileId);
    }
}
