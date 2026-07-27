package com.minos.api;

import java.util.List;
import java.util.Objects;

/** Additive M20 semantic/hybrid API. Existing structured APIs remain unchanged and authoritative. */
public interface SemanticCodeIntelligenceApi {

    String CONTRACT_VERSION = "1";

    default String contractVersion() {
        return CONTRACT_VERSION;
    }

    SemanticIndexStatusDto getSemanticIndexStatus(String projectIdentifier) throws MinosApi.MinosApiException;

    SemanticIndexUpdateDto synchronizeSemanticIndex(String projectIdentifier) throws MinosApi.MinosApiException;

    SemanticSearchDto semanticSearch(String projectIdentifier, SemanticQuery query) throws MinosApi.MinosApiException;

    HybridSearchDto hybridSearch(String projectIdentifier, HybridQuery query) throws MinosApi.MinosApiException;

    HybridContextDto buildHybridContext(String projectIdentifier, ContextQuery query) throws MinosApi.MinosApiException;

    record SemanticQuery(String query, int limit, double minimumScore) {
        public SemanticQuery {
            requireText(query, "query");
            requireRange(limit, 1, 1000, "limit");
            if (!Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
                throw new IllegalArgumentException("minimumScore must be between -1 and 1");
            }
        }
    }

    record HybridQuery(String query, int limit, double minimumScore) {
        public HybridQuery {
            requireText(query, "query");
            requireRange(limit, 1, 500, "limit");
            if (!Double.isFinite(minimumScore) || minimumScore < 0.0 || minimumScore > 1.0) {
                throw new IllegalArgumentException("minimumScore must be between 0 and 1");
            }
        }
    }

    record ContextQuery(String query, int maxDocuments, int maxTokens, int maxTokensPerDocument) {
        public ContextQuery {
            requireText(query, "query");
            requireRange(maxDocuments, 1, 100, "maxDocuments");
            requireRange(maxTokens, 128, 65_536, "maxTokens");
            requireRange(maxTokensPerDocument, 32, maxTokens, "maxTokensPerDocument");
        }
    }

    record SemanticDocumentDto(
            String id,
            String stableKey,
            String kind,
            String sourceId,
            String fileId,
            int startLine,
            int endLine,
            String contentChecksum
    ) {
    }

    record RankingSignalDto(String type, double score, String nature) {
    }

    record SemanticHitDto(
            SemanticDocumentDto document,
            double score,
            String nature,
            String providerId,
            String modelId
    ) {
    }

    record HybridHitDto(
            SemanticDocumentDto document,
            double score,
            double lexicalScore,
            double graphScore,
            double semanticScore,
            String rankingMode,
            List<RankingSignalDto> signals
    ) {
        public HybridHitDto {
            signals = immutable(signals);
        }
    }

    record SemanticIndexStatusDto(
            String projectId,
            String projectName,
            String state,
            String activeSnapshotId,
            String indexedSnapshotId,
            String providerId,
            String modelId,
            int dimensions,
            int documentCount,
            long indexSizeBytes,
            List<String> limitations
    ) {
        public SemanticIndexStatusDto {
            limitations = immutable(limitations);
        }
    }

    record SemanticIndexUpdateDto(
            String projectId,
            String snapshotId,
            String state,
            int documentCount,
            int embeddedAdded,
            int embeddedChanged,
            int removed,
            int reused,
            int embeddedCount,
            long rebuildMillis,
            long indexSizeBytes,
            List<String> limitations
    ) {
        public SemanticIndexUpdateDto {
            limitations = immutable(limitations);
        }
    }

    record SemanticSearchDto(
            String projectId,
            String snapshotId,
            String query,
            String mode,
            List<SemanticHitDto> hits,
            List<String> limitations,
            long latencyMillis
    ) {
        public SemanticSearchDto {
            hits = immutable(hits);
            limitations = immutable(limitations);
        }
    }

    record HybridSearchDto(
            String projectId,
            String snapshotId,
            String query,
            boolean semanticAvailable,
            List<HybridHitDto> hits,
            List<String> limitations,
            long latencyMillis
    ) {
        public HybridSearchDto {
            hits = immutable(hits);
            limitations = immutable(limitations);
        }
    }

    record ContextItemDto(
            String stableKey,
            String kind,
            String sourceId,
            String fileId,
            int startLine,
            int endLine,
            String content,
            double rankingScore,
            String rankingMode,
            List<RankingSignalDto> signals,
            int estimatedTokens,
            boolean truncated
    ) {
        public ContextItemDto {
            signals = immutable(signals);
        }
    }

    record HybridContextDto(
            String projectId,
            String snapshotId,
            String query,
            int maxTokens,
            int usedTokens,
            boolean truncated,
            List<ContextItemDto> items,
            List<String> limitations
    ) {
        public HybridContextDto {
            items = immutable(items);
            limitations = immutable(limitations);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
