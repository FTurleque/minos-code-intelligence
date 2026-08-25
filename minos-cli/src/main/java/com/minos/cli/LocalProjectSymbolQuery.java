package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;
import com.minos.registry.ProjectRegistry;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Backward-compatible CLI facade delegating to the application-level query implementation.
 *
 * @deprecated public surfaces should use {@link com.minos.application.LocalProjectSymbolQuery}.
 */
@Deprecated(forRemoval = false)
public final class LocalProjectSymbolQuery implements ProjectSymbolQuery {

    private final com.minos.application.LocalProjectSymbolQuery delegate;

    public LocalProjectSymbolQuery(ProjectRegistry projectRegistry, CodeKnowledgeSnapshotStore snapshotStore) {
        this(new com.minos.application.LocalProjectSymbolQuery(projectRegistry, snapshotStore));
    }

    public LocalProjectSymbolQuery(MinosApplication application) {
        this(new com.minos.application.LocalProjectSymbolQuery(application));
    }

    private LocalProjectSymbolQuery(com.minos.application.LocalProjectSymbolQuery delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<SymbolResult> findSymbols(String projectIdentifier, SymbolSearchCriteria criteria) throws IOException {
        return delegate.findSymbols(projectIdentifier, criteria);
    }

    @Override
    public List<SymbolResult> getFileSymbols(String projectIdentifier, String fileId, int limit) throws IOException {
        return delegate.getFileSymbols(projectIdentifier, fileId, limit);
    }

    @Override
    public List<UsageResult> findUsages(String projectIdentifier, String symbolId, int limit) throws IOException {
        return delegate.findUsages(projectIdentifier, symbolId, limit);
    }

    @Override
    public List<RelationshipResult> findRelationships(
            String projectIdentifier,
            RelationshipSearchCriteria criteria
    ) throws IOException {
        return delegate.findRelationships(projectIdentifier, criteria);
    }

    @Override
    public CodeSearchResponse searchCode(String projectIdentifier, CodeSearchCriteria criteria) throws IOException {
        return delegate.searchCode(projectIdentifier, criteria);
    }

    @Override
    public SourceExcerpt getSource(String projectIdentifier, String fileId) throws IOException {
        return delegate.getSource(projectIdentifier, fileId);
    }
}
