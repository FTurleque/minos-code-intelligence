package com.minos.application;

import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** Application-level port for queries against a project's active index. */
@FunctionalInterface
public interface ProjectSymbolQuery {

    List<SymbolResult> findSymbols(String projectId, SymbolSearchCriteria criteria) throws IOException;

    default List<SymbolResult> getFileSymbols(String projectId, String fileId, int limit) throws IOException {
        throw new UnsupportedOperationException("file symbol queries are not supported");
    }

    default List<UsageResult> findUsages(String projectId, String symbolId, int limit) throws IOException {
        throw new UnsupportedOperationException("usage queries are not supported");
    }

    default List<RelationshipResult> findRelationships(
            String projectId,
            RelationshipSearchCriteria criteria
    ) throws IOException {
        throw new UnsupportedOperationException("relationship queries are not supported");
    }

    /**
     * What an answer built from relationships of these kinds cannot see on this project's active snapshot (see
     * {@code SnapshotCoverageLimitations}): empty when nothing is to be declared. The default declares nothing, so an
     * implementation that does not know its snapshot's content keeps the historical answers.
     */
    default List<String> relationshipLimitations(String projectId, Set<RelationshipKind> kinds) throws IOException {
        return List.of();
    }

    default CodeSearchResponse searchCode(String projectId, CodeSearchCriteria criteria) throws IOException {
        throw new UnsupportedOperationException("context search is not supported");
    }

    default SourceExcerpt getSource(String projectId, String fileId) throws IOException {
        throw new UnsupportedOperationException("source retrieval is not supported");
    }
}
