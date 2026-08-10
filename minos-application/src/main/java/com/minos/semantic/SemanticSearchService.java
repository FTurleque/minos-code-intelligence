package com.minos.semantic;

import com.minos.domain.InformationNature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Bounded semantic retrieval over the active, snapshot-aligned vector index. */
public final class SemanticSearchService {

    public static final int MAX_RESULTS = 1_000;
    public static final int MAX_QUERY_CACHE_ENTRIES = 256;
    public static final int MAX_QUERY_UTF8_BYTES = 64 * 1024;
    public static final long MAX_QUERY_CACHE_WEIGHT_BYTES = 8L * 1024L * 1024L;

    private final SemanticIndexService indexService;
    private final Map<QueryCacheKey, SemanticVector> queryCache = new LinkedHashMap<>(32, 0.75f, true);
    private long queryCacheWeightBytes;

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
        SemanticVectorStore vectorStore = indexService.vectorStore();
        List<SearchHit> hits = vectorStore.search(status.projectId(), queryVector, request.limit(), request.minimumScore())
                .stream()
                .map(hit -> new SearchHit(hit.document(), hit.score(), InformationNature.HEURISTIC,
                        provider.id(), provider.modelId()))
                .toList();
        List<String> limitations = new ArrayList<>(status.limitations());
        limitations.add("VECTOR_SCORE_IS_RANKING_SIGNAL_NOT_STRUCTURAL_FACT");
        if ("exact-linear".equals(vectorStore.searchEngine())) {
            limitations.add("VECTOR_SEARCH_LINEAR_SCAN");
        } else {
            limitations.add("VECTOR_SEARCH_ENGINE=" + vectorStore.searchEngine());
        }
        limitations.add("SEMANTIC_QUERY_VECTOR_CACHE_WEIGHTED_8_MIB");
        return new SearchResponse(status.projectId(), status.activeSnapshotId(), request.query(),
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
            queryCacheWeightBytes = checkedAdd(queryCacheWeightBytes, cacheWeight(key, embedded));
            evictQueryCache();
        }
        return embedded;
    }

    private void evictQueryCache() {
        Iterator<Map.Entry<QueryCacheKey, SemanticVector>> entries = queryCache.entrySet().iterator();
        while ((queryCache.size() > MAX_QUERY_CACHE_ENTRIES
                || queryCacheWeightBytes > MAX_QUERY_CACHE_WEIGHT_BYTES) && entries.hasNext()) {
            Map.Entry<QueryCacheKey, SemanticVector> eldest = entries.next();
            queryCacheWeightBytes -= cacheWeight(eldest.getKey(), eldest.getValue());
            entries.remove();
        }
    }

    private static long cacheWeight(QueryCacheKey key, SemanticVector vector) {
        return 128L
                + key.providerId().getBytes(StandardCharsets.UTF_8).length
                + key.modelId().getBytes(StandardCharsets.UTF_8).length
                + key.query().getBytes(StandardCharsets.UTF_8).length
                + (long) vector.dimensions() * Double.BYTES;
    }

    private static long checkedAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    int queryCacheSize() { synchronized (queryCache) { return queryCache.size(); } }
    long queryCacheWeightBytes() { synchronized (queryCache) { return queryCacheWeightBytes; } }

    private static long elapsedMillis(long started) { return (System.nanoTime() - started) / 1_000_000L; }

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
            int queryBytes = query.getBytes(StandardCharsets.UTF_8).length;
            if (queryBytes > MAX_QUERY_UTF8_BYTES) {
                throw new IllegalArgumentException("query exceeds UTF-8 byte limit: " + queryBytes
                        + "/" + MAX_QUERY_UTF8_BYTES);
            }
            if (limit < 1 || limit > MAX_RESULTS) throw new IllegalArgumentException("limit must be between 1 and " + MAX_RESULTS);
            if (!Double.isFinite(minimumScore) || minimumScore < -1.0 || minimumScore > 1.0) {
                throw new IllegalArgumentException("minimumScore must be between -1 and 1");
            }
        }
        public static SearchRequest defaults(String query) { return new SearchRequest(query, 20, 0.0); }
    }

    public record SearchHit(SemanticDocument document, double score, InformationNature nature,
                            String providerId, String modelId) {
        public SearchHit { Objects.requireNonNull(document, "document"); Objects.requireNonNull(nature, "nature"); }
    }

    public record SearchResponse(String projectId, String snapshotId, String query, String mode,
                                 List<SearchHit> hits, List<String> limitations, long latencyMillis) {
        public SearchResponse {
            hits = List.copyOf(Objects.requireNonNull(hits, "hits"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
        }
    }
}
