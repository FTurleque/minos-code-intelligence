package com.minos.api;

import java.util.List;
import java.util.Objects;

/**
 * Additive read-only API over the semantic documents of a project: projects, paged documents, detail of one
 * document, search and neighbours. Nothing here writes, embeds a corpus or synchronizes an index; the semantic
 * API {@link SemanticCodeIntelligenceApi} is unchanged.
 *
 * <p>Failures follow the published taxonomy: an unknown document, an invalid cursor or a bound out of range is
 * {@link MinosApi.ErrorCode#INVALID_REQUEST}; an index replaced while it was read is
 * {@link MinosApi.ErrorCode#UNAVAILABLE} and the reading starts again from its first page.</p>
 */
public interface SemanticExplorationApi {

    String CONTRACT_VERSION = "1";

    /** Largest page of documents and largest number of neighbours one call returns. */
    int MAX_PAGE_SIZE = 200;

    default String contractVersion() {
        return CONTRACT_VERSION;
    }

    /** The registered projects, by name, with the state of their semantic index. No root path is returned. */
    List<ProjectDto> listProjects() throws MinosApi.MinosApiException;

    /** One page of the documents of the index, in stable-key order, bound to the index it was read from. */
    DocumentPageDto listDocuments(String projectIdentifier, DocumentPageQuery query) throws MinosApi.MinosApiException;

    /** One document: its indexed content and the excerpt its source file holds now. */
    DocumentDetailDto getDocument(String projectIdentifier, String stableKey) throws MinosApi.MinosApiException;

    /** A search ranked by the semantic or the hybrid service, unchanged, with each score named. */
    SearchDto search(String projectIdentifier, SearchQuery query) throws MinosApi.MinosApiException;

    /** The nearest documents of one document, ranked from its stored vector without any provider call. */
    NeighborsDto getNeighbors(String projectIdentifier, String stableKey, int limit) throws MinosApi.MinosApiException;

    /**
     * A page request. {@code cursor} is the {@code nextCursor} of the previous page or {@code null}; {@code kind}
     * is {@code SYMBOL}, {@code CHUNK}, {@code FILE} or {@code null}; each other filter is {@code null} when it
     * does not apply.
     */
    record DocumentPageQuery(String cursor, int limit, String kind, String module, String language,
                             String filePrefix) {
        public DocumentPageQuery {
            if (limit < 1 || limit > MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE_SIZE);
            }
        }

        /** The first page of 50 documents, without filter. */
        public static DocumentPageQuery firstPage() {
            return new DocumentPageQuery(null, 50, null, null, null, null);
        }
    }

    /**
     * A search. {@code mode} is {@code semantic} or {@code hybrid}; the limit is at most 1 000 for the former
     * and 500 for the latter, the minimum score at least -1 and 0 respectively.
     */
    record SearchQuery(String query, String mode, int limit, double minimumScore, String kind, String module,
                       String language, String filePrefix) {
        public SearchQuery {
            Objects.requireNonNull(query, "query");
            Objects.requireNonNull(mode, "mode");
        }
    }

    record IndexIdentityDto(String projectId, String snapshotId, String providerId, String modelId, int dimensions,
                            String documentsFingerprint) {
    }

    record ProjectDto(String projectId, String name, String state, String providerId, String modelId,
                      int dimensions, String activeSnapshotId, String indexedSnapshotId, int documentCount,
                      List<String> limitations) {
        public ProjectDto {
            limitations = immutable(limitations);
        }
    }

    /** A document without its content; the symbol fields are {@code null} unless {@code symbolResolved}. */
    record DocumentDto(String stableKey, String kind, String sourceId, String fileId, int startLine, int endLine,
                       String contentChecksum, int contentLength, boolean symbolResolved, String symbolName,
                       String qualifiedName, String symbolKind, String language, String moduleId) {
    }

    /** {@code identity} is {@code null} when the project has no index; {@code nextCursor} on the last page. */
    record DocumentPageDto(String projectId, IndexIdentityDto identity, List<DocumentDto> documents,
                           String nextCursor, int totalDocuments, List<String> limitations) {
        public DocumentPageDto {
            documents = immutable(documents);
            limitations = immutable(limitations);
        }
    }

    record SourceExcerptDto(String fileId, int startLine, int endLine, String content, boolean truncated) {
    }

    /**
     * {@code divergence} is {@code IDENTIQUE}, {@code DIFFERENTE}, {@code SOURCE_INTROUVABLE} or
     * {@code NON_COMPARABLE}; {@code source} is {@code null} when no excerpt was read.
     */
    record DocumentDetailDto(String projectId, IndexIdentityDto identity, DocumentDto document,
                             String indexedContent, SourceExcerptDto source, String divergence,
                             List<String> limitations) {
        public DocumentDetailDto {
            limitations = immutable(limitations);
        }
    }

    record ScoreSignalDto(String type, double score, String nature) {
    }

    /** {@code rank} is the 1-based rank before any filter; {@code scoreKind} names what {@code score} is. */
    record SearchHitDto(int rank, DocumentDto document, double score, String scoreKind,
                        List<ScoreSignalDto> signals) {
        public SearchHitDto {
            signals = immutable(signals);
        }
    }

    record SearchDto(String projectId, String snapshotId, String mode, String query, List<SearchHitDto> hits,
                     int rankedCount, IndexIdentityDto identity, List<String> limitations, long latencyMillis) {
        public SearchDto {
            hits = immutable(hits);
            limitations = immutable(limitations);
        }
    }

    record NeighborDto(DocumentDto document, double similarity) {
    }

    record NeighborsDto(String projectId, IndexIdentityDto identity, String stableKey, List<NeighborDto> neighbors,
                        List<String> limitations) {
        public NeighborsDto {
            neighbors = immutable(neighbors);
            limitations = immutable(limitations);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return List.copyOf(Objects.requireNonNull(values, "values"));
    }
}
