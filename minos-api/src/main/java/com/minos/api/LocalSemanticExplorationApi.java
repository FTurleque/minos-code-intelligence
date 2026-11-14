package com.minos.api;

import com.minos.application.MinosApplication;
import com.minos.application.semantic.SemanticCorpusQueryService;
import com.minos.application.semantic.SemanticProjectionService;

import java.util.List;
import java.util.Objects;

/** Local implementation of the exploration API, backed by the shared long-lived MinosApplication. */
public final class LocalSemanticExplorationApi implements SemanticExplorationApi {

    private static final String QUERY = "query";

    private final MinosApplication application;

    public LocalSemanticExplorationApi(MinosApplication application) {
        this.application = Objects.requireNonNull(application, "application");
    }

    @Override
    public List<ProjectDto> listProjects() throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> corpus().projects().stream()
                .map(value -> new ProjectDto(value.projectId(), value.name(), value.state(), value.providerId(),
                        value.modelId(), value.dimensions(), value.activeSnapshotId(), value.indexedSnapshotId(),
                        value.documentCount(), value.limitations()))
                .toList());
    }

    @Override
    public DocumentPageDto listDocuments(String projectIdentifier, DocumentPageQuery query)
            throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> {
            DocumentPageQuery value = MinosApiSupport.required(query, QUERY);
            SemanticCorpusQueryService.DocumentsPage page = corpus().documents(projectIdentifier,
                    new SemanticCorpusQueryService.DocumentsRequest(value.cursor(), value.limit(),
                            SemanticCorpusQueryService.parseKind(value.kind()), value.module(), value.language(), value.filePrefix()));
            return new DocumentPageDto(page.projectId(), page.identity().map(LocalSemanticExplorationApi::identity)
                    .orElse(null), page.documents().stream().map(LocalSemanticExplorationApi::document).toList(),
                    page.nextCursor(), page.totalDocuments(), page.limitations());
        });
    }

    @Override
    public DocumentDetailDto getDocument(String projectIdentifier, String stableKey)
            throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> {
            SemanticCorpusQueryService.Detail detail = corpus().detail(projectIdentifier, stableKey);
            return new DocumentDetailDto(detail.projectId(), identity(detail.identity()), document(detail.document()),
                    detail.indexedContent(),
                    detail.source().map(source -> new SourceExcerptDto(source.fileId(), source.startLine(),
                            source.endLine(), source.content(), source.truncated())).orElse(null),
                    detail.divergence().name(), detail.limitations());
        });
    }

    @Override
    public SearchDto search(String projectIdentifier, SearchQuery query) throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> {
            SearchQuery value = MinosApiSupport.required(query, QUERY);
            SemanticCorpusQueryService.SearchResult result = corpus().search(projectIdentifier,
                    new SemanticCorpusQueryService.SearchRequest(value.query(), SemanticCorpusQueryService.SearchMode.parse(value.mode()), value.limit(),
                            value.minimumScore(), SemanticCorpusQueryService.parseKind(value.kind()), value.module(), value.language(),
                            value.filePrefix()));
            return new SearchDto(result.projectId(), result.snapshotId(), result.mode(), result.query(),
                    result.hits().stream().map(hit -> new SearchHitDto(hit.rank(), document(hit.document()),
                            hit.score(), hit.scoreKind(), hit.signals().stream()
                            .map(signal -> new ScoreSignalDto(signal.type(), signal.score(), signal.nature()))
                            .toList())).toList(),
                    result.rankedCount(), result.identity().map(LocalSemanticExplorationApi::identity).orElse(null),
                    result.limitations(), result.latencyMillis());
        });
    }

    @Override
    public NeighborsDto getNeighbors(String projectIdentifier, String stableKey, int limit)
            throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> {
            SemanticCorpusQueryService.Neighbors neighbors = corpus().neighbors(projectIdentifier, stableKey, limit);
            return new NeighborsDto(neighbors.projectId(), identity(neighbors.identity()), neighbors.stableKey(),
                    neighbors.neighbors().stream()
                            .map(neighbor -> new NeighborDto(document(neighbor.document()), neighbor.similarity()))
                            .toList(),
                    neighbors.limitations());
        });
    }

    @Override
    public ProjectionDto getProjection(String projectIdentifier) throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(
                () -> projection(application.semanticProjectionService().status(projectIdentifier)));
    }

    @Override
    public ProjectionPointsDto getProjectionPoints(String projectIdentifier, int offset, int limit)
            throws MinosApi.MinosApiException {
        return MinosApiSupport.execute(() -> {
            SemanticProjectionService.Points page =
                    application.semanticProjectionService().points(projectIdentifier, offset, limit);
            return new ProjectionPointsDto(projection(page.status()), page.offset(), page.total(),
                    page.points().stream().map(point -> new ProjectionPointDto(point.stableKey(),
                            point.kind().name(), point.x(), point.y())).toList());
        });
    }

    private static ProjectionDto projection(SemanticProjectionService.Status status) {
        if (status.projection().isEmpty()) {
            return new ProjectionDto(status.projectId(), status.state().name(), status.cause(),
                    status.prepareCommand(), status.indexDocumentCount(), null, null, 0, null, 0L, List.of(), 0, 0,
                    0, 0, false, List.of());
        }
        SemanticProjectionService.Description value = status.projection().orElseThrow();
        return new ProjectionDto(status.projectId(), status.state().name(), status.cause(),
                status.prepareCommand(), status.indexDocumentCount(),
                new IndexIdentityDto(value.index().projectId(), value.index().snapshotId(),
                        value.index().providerId(), value.index().modelId(), value.index().dimensions(),
                        value.index().documentsFingerprint()),
                value.method(), value.methodVersion(), value.parameters(), value.seed(), value.kinds(),
                value.maxPoints(), value.pointCount(), value.candidateCount(), value.documentCount(),
                value.sampled(), value.explainedVariance());
    }

    private SemanticCorpusQueryService corpus() {
        return application.semanticCorpusQueryService();
    }

    private static IndexIdentityDto identity(SemanticCorpusQueryService.IndexIdentity value) {
        return new IndexIdentityDto(value.projectId(), value.snapshotId(), value.providerId(), value.modelId(),
                value.dimensions(), value.documentsFingerprint());
    }

    private static DocumentDto document(SemanticCorpusQueryService.DocumentSummary value) {
        return new DocumentDto(value.stableKey(), value.kind(), value.sourceId(), value.fileId(), value.startLine(),
                value.endLine(), value.checksum(), value.contentLength(), value.symbolResolved(), value.symbolName(),
                value.qualifiedName(), value.symbolKind(), value.language(), value.moduleId());
    }
}
