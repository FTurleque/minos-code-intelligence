package com.minos.semantic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SemanticSearchRequestLimitTest {

    @Test
    void acceptsQueryAtUtf8Boundary() {
        String query = "a".repeat(SemanticSearchService.MAX_QUERY_UTF8_BYTES);
        assertDoesNotThrow(() -> SemanticSearchService.SearchRequest.defaults(query));
    }

    @Test
    void rejectsQueryAboveUtf8BoundaryBeforeEmbeddingRequest() {
        String query = "a".repeat(SemanticSearchService.MAX_QUERY_UTF8_BYTES + 1);
        assertThrows(IllegalArgumentException.class,
                () -> SemanticSearchService.SearchRequest.defaults(query));
    }
}
