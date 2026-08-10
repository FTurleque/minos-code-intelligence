package com.minos.semantic;

import com.minos.context.TokenEstimator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds bounded context from hybrid-ranked semantic documents without changing ranking authority. */
public final class HybridContextBuilder {

    public static final int MAX_TOKENS = 65_536;
    public static final int MAX_DOCUMENTS = 100;
    private static final int RESPONSE_OVERHEAD_TOKENS = 32;

    private final HybridSearchService hybridSearch;

    public HybridContextBuilder(HybridSearchService hybridSearch) {
        this.hybridSearch = Objects.requireNonNull(hybridSearch, "hybridSearch");
    }

    public ContextResponse build(String projectReference, ContextRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        int probe = Math.min(HybridSearchService.MAX_RESULTS, Math.max(request.maxDocuments(), request.maxDocuments() * 3));
        HybridSearchService.HybridResponse ranked = hybridSearch.search(projectReference,
                new HybridSearchService.HybridRequest(request.query(), probe, 0.0));
        List<ContextItem> selected = new ArrayList<>();
        int usedTokens = RESPONSE_OVERHEAD_TOKENS;
        boolean truncated = ranked.hits().size() > request.maxDocuments();

        for (HybridSearchService.HybridHit hit : ranked.hits()) {
            if (selected.size() >= request.maxDocuments()) { truncated = true; break; }
            int remaining = request.maxTokens() - usedTokens;
            if (remaining <= 0) { truncated = true; break; }
            int itemBudget = Math.min(request.maxTokensPerDocument(), remaining);
            String content = hit.document().content();
            boolean itemTruncated = TokenEstimator.estimate(content) > itemBudget;
            if (itemTruncated) content = TokenEstimator.truncate(content, itemBudget);
            int tokens = TokenEstimator.estimate(content);
            if (tokens == 0 && content.isBlank()) continue;
            if (usedTokens + tokens > request.maxTokens()) { truncated = true; break; }
            selected.add(new ContextItem(
                    hit.document().stableKey(), hit.document().kind().name(), hit.document().sourceId(),
                    hit.document().fileId(), hit.document().startLine(), hit.document().endLine(), content,
                    hit.score(), hit.rankingMode(), hit.signals(), tokens, itemTruncated));
            usedTokens += tokens;
            truncated |= itemTruncated;
        }
        return new ContextResponse(ranked.projectId(), ranked.snapshotId(), request.query(),
                request.maxTokens(), Math.min(usedTokens, request.maxTokens()), truncated,
                selected, ranked.limitations());
    }

    public record ContextRequest(String query, int maxDocuments, int maxTokens, int maxTokensPerDocument) {
        public ContextRequest {
            if (query == null || query.isBlank()) throw new IllegalArgumentException("query must not be blank");
            if (query.getBytes(StandardCharsets.UTF_8).length > HybridSearchService.MAX_QUERY_UTF8_BYTES) {
                throw new IllegalArgumentException("query exceeds UTF-8 byte limit: " + HybridSearchService.MAX_QUERY_UTF8_BYTES);
            }
            if (maxDocuments < 1 || maxDocuments > MAX_DOCUMENTS) {
                throw new IllegalArgumentException("maxDocuments must be between 1 and " + MAX_DOCUMENTS);
            }
            if (maxTokens < 128 || maxTokens > MAX_TOKENS) {
                throw new IllegalArgumentException("maxTokens must be between 128 and " + MAX_TOKENS);
            }
            if (maxTokensPerDocument < 32 || maxTokensPerDocument > maxTokens) {
                throw new IllegalArgumentException("maxTokensPerDocument must be between 32 and maxTokens");
            }
        }
        public static ContextRequest defaults(String query) { return new ContextRequest(query, 10, 4_000, 800); }
    }

    public record ContextItem(
            String stableKey, String kind, String sourceId, String fileId,
            int startLine, int endLine, String content, double rankingScore,
            String rankingMode, List<HybridSearchService.RankingSignal> signals,
            int estimatedTokens, boolean truncated) {
        public ContextItem { signals = List.copyOf(Objects.requireNonNull(signals, "signals")); }
    }

    public record ContextResponse(
            String projectId, String snapshotId, String query, int maxTokens, int usedTokens,
            boolean truncated, List<ContextItem> items, List<String> limitations) {
        public ContextResponse {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            limitations = List.copyOf(Objects.requireNonNull(limitations, "limitations"));
            if (usedTokens > maxTokens) throw new IllegalArgumentException("usedTokens exceeds maxTokens");
        }
    }
}
