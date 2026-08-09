package com.minos.application;

import com.minos.context.CodeSearchCriteria;
import com.minos.context.CodeSearchResponse;
import com.minos.context.SourceExcerpt;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;

import java.util.List;

/** Application-level port for queries against a project's active index. */
@FunctionalInterface
public interface ProjectSymbolQuery {

    List<SymbolResult> findSymbols(String projectId, SymbolSearchCriteria criteria) throws Exception;

    default List<SymbolResult> getFileSymbols(String projectId, String fileId, int limit) throws Exception {
        throw new UnsupportedOperationException("file symbol queries are not supported");
    }

    default List<UsageResult> findUsages(String projectId, String symbolId, int limit) throws Exception {
        throw new UnsupportedOperationException("usage queries are not supported");
    }

    default List<RelationshipResult> findRelationships(
            String projectId,
            RelationshipSearchCriteria criteria
    ) throws Exception {
        throw new UnsupportedOperationException("relationship queries are not supported");
    }

    default CodeSearchResponse searchCode(String projectId, CodeSearchCriteria criteria) throws Exception {
        throw new UnsupportedOperationException("context search is not supported");
    }

    default SourceExcerpt getSource(String projectId, String fileId) throws Exception {
        throw new UnsupportedOperationException("source retrieval is not supported");
    }
}
