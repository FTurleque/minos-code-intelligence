package com.minos.semantic;

import com.minos.domain.InformationNature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/** Bounded semantic retrieval over the active, snapshot-aligned vector index. */
public final class SemanticSearchService {

    public static final int MAX_RESULTS = 1_000;
    public static final int MAX_QUERY_CACHE_ENTRIES = 256;

    private static final Comparator<SearchHit> BEST_FIRST = Comparator
            .comparingDouble(SearchHit::score).reversed()
            .thenComparing(hit -> hit.document().stableKey());
    private static final Comparator<SearchHit> WORST_FIRST = BEST_FIRST.reversed();

    private final SemanticIndexService indexService;
    private final Map<QueryCacheKey, SemanticVector> queryCache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<QueryCacheKey, SemanticVector> eldest) {
            return size() > MAX_QUERY_CACHE_ENTRIES;
        }
    };

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
        SemanticVector queryVector = queryVector(provider, request.query());
        var index = indexService.activeIndex(projectReference).orElseThrow();
        PriorityQueue<SearchHit> top = new PriorityQueue<>(request.limit(), WORST_FIRST);
        for (SemanticVectorStore.IndexedDocument value : index.documents()) {
            double score = cosine(queryVector, value.vector());
            if (score < request.minimumScore()) continue;
            SearchHit candidate = new SearchHit(
                    value.document(), score, InformationNature.HEURISTIC, provider.id(), provider.modelId());
            if (top.size() < request.limit()) {
                top.add(candidate);
            } else if (BEST_FIRST.compare(candidate, top.peek()) < 0) {
                top.poll();
                top.add(candidate);
            }
        }
        List<SearchHit> hits = new ArrayList<>(top);
        hits.sort(BEST_FIRST);
        List<String> limitations = new ArrayList<>(status.limitations());
        limitations.add("VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT");
        limitations.add("VECTOR_SEARCH_LINEAR_SCAN");
        limitations.add("ANN_NOT_ENABLED_M21_S8_KEEP_CURRENT_BACKEND");
        limitations.add("SEMANTIC_QUERY_VECTOR_CACHE_BOUNDED_256");
        return new SearchResponse(status.projectId(), index.snapshotId(), request.query(),
                "SEMANTIC", hits, List.copyOf(limitations), elapsedMillis(started));
    }

    private SemanticVector queryVector(EmbeddingProvider provider, String query) throws IOException {
        QueryCacheKey key = new QueryCacheKey(provider.id(), provider.modelId(), provider.dimensions(), query);
        synchronized (queryCache) {
            SemanticVector cached = queryCache.get(key);
            if (cached != null) return cached;
        }
        SemanticVector embedded = provider.embed("query", query);
        if (embedded.dimensions() != provider.dimensions()) {
            throw new IllegalStateException("embedding provider returned unexpected query dimensions");
        }
        synchronized (queryCache) {
            SemanticVector existing = queryCache.get(key);
            if (existing != null) return existing;
            queryCache.put(key, embedded);
        }
        return embedded;
    }

    int queryCacheSize() {
        synchronized (queryCache) {
            return queryCache.size();
        }
    }

    private static double cosine(SemanticVector left, SemanticVector right) {
        if (left.dimensions() != right.dimensions()) throw new IllegalArgumentException("vector dimensions mismatch");
        double denominator = left.norm() * right.norm();
        if (denominator == 0.0) return 0.0;
        double dot = 0.0;
        for (int i = 0; i < left.dimensions(); i++) {
            dot += left.valueAt(i) * right.valueAt(i);
        }
        double value = dot / denominator;
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }

    private record QueryCacheKey(String providerId, String modelId, int dimensions, String query) {
        private QueryCacheKey {
            Objects.requireNonNull(providerId, "providerId");
            Objects.requireNonNull(modelId, "modelId");
            Objects.requireNonNull(query, "query");
        }
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
