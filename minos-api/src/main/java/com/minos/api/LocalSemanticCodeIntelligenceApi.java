package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.semantic.HybridContextBuilder;
import com.minos.semantic.HybridSearchService;
import com.minos.semantic.SemanticDocument;
import com.minos.semantic.SemanticIndexService;
import com.minos.semantic.SemanticSearchService;

import java.io.IOException;
import java.util.Objects;

/** Local M20 implementation backed by the shared long-lived MinosApplication. */
public final class LocalSemanticCodeIntelligenceApi implements SemanticCodeIntelligenceApi {

    private final MinosApplication application;

    public LocalSemanticCodeIntelligenceApi(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    @Override
    public SemanticIndexStatusDto getSemanticIndexStatus(String projectIdentifier) throws MinosApi.MinosApiException {
        return execute(() -> status(application.semanticIndexService().status(projectIdentifier)));
    }

    @Override
    public SemanticIndexUpdateDto synchronizeSemanticIndex(String projectIdentifier) throws MinosApi.MinosApiException {
        return execute(() -> update(application.semanticIndexService().synchronize(projectIdentifier)));
    }

    @Override
    public SemanticSearchDto semanticSearch(String projectIdentifier, SemanticQuery query) throws MinosApi.MinosApiException {
        return execute(() -> {
            SemanticQuery value = Objects.requireNonNull(query, "query");
            return semantic(application.semanticSearchService().search(projectIdentifier,
                    new SemanticSearchService.SearchRequest(value.query(), value.limit(), value.minimumScore())));
        });
    }

    @Override
    public HybridSearchDto hybridSearch(String projectIdentifier, HybridQuery query) throws MinosApi.MinosApiException {
        return execute(() -> {
            HybridQuery value = Objects.requireNonNull(query, "query");
            return hybrid(application.hybridSearchService().search(projectIdentifier,
                    new HybridSearchService.HybridRequest(value.query(), value.limit(), value.minimumScore())));
        });
    }

    @Override
    public HybridContextDto buildHybridContext(String projectIdentifier, ContextQuery query) throws MinosApi.MinosApiException {
        return execute(() -> {
            ContextQuery value = Objects.requireNonNull(query, "query");
            return context(application.hybridContextBuilder().build(projectIdentifier,
                    new HybridContextBuilder.ContextRequest(
                            value.query(), value.maxDocuments(), value.maxTokens(), value.maxTokensPerDocument())));
        });
    }

    private static SemanticIndexStatusDto status(SemanticIndexService.Status value) {
        return new SemanticIndexStatusDto(
                value.projectId(), value.projectName(), value.state().name(), value.activeSnapshotId(), value.indexedSnapshotId(),
                value.providerId(), value.modelId(), value.dimensions(), value.documentCount(), value.indexSizeBytes(), value.limitations());
    }

    private static SemanticIndexUpdateDto update(SemanticIndexService.UpdateReport value) {
        return new SemanticIndexUpdateDto(
                value.projectId(), value.snapshotId(), value.state().name(), value.documentCount(), value.embeddedAdded(),
                value.embeddedChanged(), value.removed(), value.reused(), value.embeddedCount(), value.rebuildMillis(),
                value.indexSizeBytes(), value.limitations());
    }

    private static SemanticSearchDto semantic(SemanticSearchService.SearchResponse value) {
        return new SemanticSearchDto(
                value.projectId(), value.snapshotId(), value.query(), value.mode(),
                value.hits().stream().map(hit -> new SemanticHitDto(
                        document(hit.document()), hit.score(), hit.nature().name(), hit.providerId(), hit.modelId())).toList(),
                value.limitations(), value.latencyMillis());
    }

    private static HybridSearchDto hybrid(HybridSearchService.HybridResponse value) {
        return new HybridSearchDto(
                value.projectId(), value.snapshotId(), value.query(), value.semanticAvailable(),
                value.hits().stream().map(LocalSemanticCodeIntelligenceApi::hybridHit).toList(),
                value.limitations(), value.latencyMillis());
    }

    private static HybridHitDto hybridHit(HybridSearchService.HybridHit hit) {
        return new HybridHitDto(
                document(hit.document()), hit.score(), hit.lexicalScore(), hit.graphScore(), hit.semanticScore(),
                hit.rankingMode(), hit.signals().stream().map(LocalSemanticCodeIntelligenceApi::signal).toList());
    }

    private static HybridContextDto context(HybridContextBuilder.ContextResponse value) {
        return new HybridContextDto(
                value.projectId(), value.snapshotId(), value.query(), value.maxTokens(), value.usedTokens(), value.truncated(),
                value.items().stream().map(item -> new ContextItemDto(
                        item.stableKey(), item.kind(), item.sourceId(), item.fileId(), item.startLine(), item.endLine(), item.content(),
                        item.rankingScore(), item.rankingMode(), item.signals().stream().map(LocalSemanticCodeIntelligenceApi::signal).toList(),
                        item.estimatedTokens(), item.truncated())).toList(), value.limitations());
    }

    private static SemanticDocumentDto document(SemanticDocument value) {
        return new SemanticDocumentDto(
                value.id(), value.stableKey(), value.kind().name(), value.sourceId(), value.fileId(),
                value.startLine(), value.endLine(), value.checksum());
    }

    private static RankingSignalDto signal(HybridSearchService.RankingSignal value) {
        return new RankingSignalDto(value.type(), value.score(), value.nature().name());
    }

    private static <T> T execute(ThrowingSupplier<T> action) throws MinosApi.MinosApiException {
        try {
            return action.get();
        } catch (IllegalArgumentException exception) {
            throw new MinosApi.MinosApiException(MinosApi.ErrorCode.INVALID_REQUEST, exception.getMessage(), exception);
        } catch (IOException exception) {
            throw new MinosApi.MinosApiException(MinosApi.ErrorCode.IO_FAILURE, exception.getMessage(), exception);
        } catch (RuntimeException exception) {
            throw new MinosApi.MinosApiException(MinosApi.ErrorCode.EXECUTION_FAILURE, exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws IOException;
    }
}
