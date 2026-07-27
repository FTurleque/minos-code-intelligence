package com.minos.semantic;

import com.minos.domain.InformationNature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Bounded semantic retrieval over the active, snapshot-aligned vector index. */
public final class SemanticSearchService {

    public static final int MAX_RESULTS = 1_000;

    private final SemanticIndexService indexService;

    public SemanticSearchService(SemanticIndexService indexService) {
        this.indexService = Objects.requireNonNull(indexService, "indexService");
    }

    public SearchResponse search(String projectReference, SearchRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        long started = System.nanoTime();
        SemanticIndexService.Status status = indexService.status(projectReference);
        if (status.state() != SemanticIndexService.State.READY) {
            List<String> limitations = new ArrayList<>(status.limitations());
            limitations.add("SEMANTIC_SEARCH_REQUIRES_READY_INDEX");
            return new SearchResponse(status.projectId(), status.activeSnapshotId(), request.query(),
                    "SEMANTIC", List.of(), List.copyOf(limitations), elapsedMillis(started));
        }
        EmbeddingProvider provider = indexService.embeddingProvider().orElseThrow();
        SemanticVector queryVector = provider.embed("query", request.query());
        if (queryVector.dimensions() != provider.dimensions()) {
            throw new IllegalStateException("embedding provider returned unexpected query dimensions");
        }
        var index = indexService.activeIndex(projectReference).orElseThrow();
        List<SearchHit> hits = index.documents().stream()
                .map(value -> new SearchHit(
                        value.document(),
                        cosine(queryVector, value.vector()),
                        InformationNature.HEURISTIC,
                        provider.id(),
                        provider.modelId()))
                .filter(hit -> hit.score() >= request.minimumScore())
                .sorted(Comparator.comparingDouble(SearchHit::score).reversed()
                        .thenComparing(hit -> hit.document().stableKey()))
                .limit(request.limit())
                .toList();
        List<String> limitations = new ArrayList<>(status.limitations());
        limitations.add("VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT");
        limitations.add("VECTOR_SEARCH_LINEAR_SCAN");
        return new SearchResponse(status.projectId(), index.snapshotId(), request.query(),
                "SEMANTIC", hits, List.copyOf(limitations), elapsedMillis(started));
    }

    private static double cosine(SemanticVector left, SemanticVector right) {
        if (left.dimensions() != right.dimensions()) throw new IllegalArgumentException("vector dimensions mismatch");
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < left.dimensions(); i++) {
            double a = left.values().get(i);
            double b = right.values().get(i);
            dot += a * b;
            leftNorm += a * a;
            rightNorm += b * b;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0;
        double value = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    public record SearchRequest(String query, int limit, double minimumScore) {
        public SearchRequest {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
            if (limit < 1 || limit > MAX_RESULTS) throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
            if (!Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
                throw new IllegalArgumentException("minimumScore must be between -1 and 1");
            }
        }

        public static SearchRequest defaults(String query) {
            return new SearchRequest(query, 20, 0.0);
        }
    }

    public record SearchHit(
            SemanticDocument document,
            double score,
            InformationNature nature,
            String providerId,
            String modelId
    ) {
        public SearchHit {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(nature, "nature");
        }
    }

    public record SearchResponse(
            String projectId,
            String snapshotId,
            String query,
            String mode,
            List<SearchHit> hits,
            List<String> limitations,
            long latencyMillis
    ) {
        public SearchResponse {
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }
}
