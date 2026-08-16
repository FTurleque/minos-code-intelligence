package com.minos.api;

import com.minos.application.LocalProjectOperations;
import com.minos.application.LocalProjectSymbolQuery;
import com.minos.application.MinosApplication;
import com.minos.application.ProjectOperations;
import com.minos.application.ProjectSymbolQuery;
import com.minos.architecture.ArchitectureIntelligenceView;
import com.minos.architecture.ArchitectureModule;
import com.minos.architecture.ArchitectureModuleContext;
import com.minos.architecture.ArchitectureModuleDependency;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.domain.CodeEntityRef;
import com.minos.domain.CodeEntityType;
import com.minos.domain.Evidence;
import com.minos.domain.InformationNature;
import com.minos.domain.Origin;
import com.minos.domain.RelationshipDirection;
import com.minos.domain.RelationshipKind;
import com.minos.domain.RelationshipSearchCriteria;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.domain.SymbolSearchCriteria;
import com.minos.impact.ImpactAnalysisReport;
import com.minos.impact.ImpactAnalysisRequest;
import com.minos.impact.ImpactPathStep;
import com.minos.impact.ImpactedSymbol;
import com.minos.impact.ProjectImpactQuery;
import com.minos.query.RelationshipResult;
import com.minos.query.SymbolResult;
import com.minos.query.UsageResult;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Local M11 implementation backed by the already-qualified MINOS services.
 *
 * <p>This class is an exposure layer only: it maps the public API contract to
 * M1-M8 query services and never reimplements code-intelligence analysis.</p>
 */
public final class LocalMinosApi implements MinosApi, AutoCloseable {

    private final MinosApplication application;
    private final boolean ownsApplication;
    private final ProjectOperations projectOperations;
    private final ProjectSymbolQuery symbolQuery;
    private final ProjectArchitectureQuery architectureQuery;
    private final ProjectImpactQuery impactQuery;
    private final MinosTeamApi teamApi;

    public LocalMinosApi(Path home) throws MinosApiException {
        this(openApplication(home), true);
    }

    /** Uses an already-composed application so API and other surfaces share stateful infrastructure. */
    public LocalMinosApi(MinosApplication application) {
        this(application, false);
    }

    private LocalMinosApi(MinosApplication application, boolean ownsApplication) {
        MinosApplication app = Objects.requireNonNull(application, "application");
        this.application = app;
        this.ownsApplication = ownsApplication;
        this.projectOperations = new LocalProjectOperations(app);
        this.symbolQuery = new LocalProjectSymbolQuery(app.projectRegistry(), app.snapshotStore());
        this.architectureQuery = app.architectureQuery();
        this.impactQuery = app.impactQuery();
        this.teamApi = new LocalMinosTeamApi(app);
    }

    @Override
    public ProjectDto addProject(Path rootPath, String displayName) throws MinosApiException {
        return execute(() -> project(projectOperations.addProject(rootPath, displayName)));
    }

    @Override
    public List<ProjectDto> listProjects() throws MinosApiException {
        return execute(() -> projectOperations.listProjects().stream().map(LocalMinosApi::project).toList());
    }

    @Override
    public ProjectDto getProject(String projectIdentifier) throws MinosApiException {
        return execute(() -> project(projectOperations.inspectProject(projectIdentifier)));
    }

    @Override
    public IndexImportDto importScip(
            String projectIdentifier,
            Path indexFile,
            IndexImportRequest request
    ) throws MinosApiException {
        return execute(() -> {
            IndexImportRequest value = required(request, "request");
            return indexImport(projectOperations.importScip(
                    projectIdentifier,
                    indexFile,
                    value.providerId(),
                    value.providerVersion(),
                    value.moduleId(),
                    value.snapshotId()
            ));
        });
    }

    @Override
    public List<SymbolDto> findSymbols(String projectIdentifier, SymbolQuery query) throws MinosApiException {
        return execute(() -> {
            SymbolQuery value = required(query, "query");
            SymbolSearchCriteria criteria = new SymbolSearchCriteria(
                    value.text(),
                    value.qualifiedName(),
                    enumValue(SymbolKind.class, value.kind(), "kind", true),
                    value.moduleId(),
                    value.limit()
            );
            return symbolQuery.findSymbols(projectIdentifier, criteria).stream()
                    .map(LocalMinosApi::symbol)
                    .toList();
        });
    }

    @Override
    public List<UsageDto> findUsages(String projectIdentifier, String symbolId, int limit) throws MinosApiException {
        return execute(() -> {
            requireText(symbolId, "symbolId");
            requireLimit(limit, "limit");
            return symbolQuery.findUsages(projectIdentifier, symbolId, limit).stream()
                    .map(LocalMinosApi::usage)
                    .toList();
        });
    }

    @Override
    public List<RelationshipDto> findRelationships(
            String projectIdentifier,
            RelationshipQuery query
    ) throws MinosApiException {
        return execute(() -> {
            RelationshipQuery value = required(query, "query");
            Set<RelationshipKind> kinds = value.kinds().stream()
                    .map(kind -> enumValue(RelationshipKind.class, kind, "relationship kind", false))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            RelationshipSearchCriteria criteria = new RelationshipSearchCriteria(
                    new CodeEntityRef(
                            enumValue(CodeEntityType.class, value.anchorType(), "anchorType", false),
                            value.anchorId()
                    ),
                    enumValue(RelationshipDirection.class, value.direction(), "direction", false),
                    kinds,
                    enumValue(ResolutionStatus.class, value.resolutionStatus(), "resolutionStatus", true),
                    enumValue(InformationNature.class, value.nature(), "nature", true),
                    value.limit()
            );
            return symbolQuery.findRelationships(projectIdentifier, criteria).stream()
                    .map(LocalMinosApi::relationship)
                    .toList();
        });
    }

    @Override
    public ArchitectureDto getArchitecture(String projectIdentifier) throws MinosApiException {
        return execute(() -> architecture(architectureQuery.getArchitectureIntelligence(projectIdentifier)));
    }

    @Override
    public ArchitectureGraphDto getArchitectureGraph(String projectIdentifier) throws MinosApiException {
        return execute(() -> architectureGraph(architectureQuery.getArchitectureIntelligence(projectIdentifier)));
    }

    @Override
    public ModuleContextDto getModuleContext(
            String projectIdentifier,
            String moduleIdentifier
    ) throws MinosApiException {
        return execute(() -> moduleContext(architectureQuery.getModuleContext(projectIdentifier, moduleIdentifier)));
    }

    @Override
    public ImpactReportDto analyzeImpact(String projectIdentifier, ImpactQuery query) throws MinosApiException {
        return execute(() -> {
            ImpactQuery value = required(query, "query");
            return impact(impactQuery.analyzeImpact(
                    projectIdentifier,
                    new ImpactAnalysisRequest(value.symbolId(), value.maxDepth(), value.maxResults())
            ));
        });
    }

    @Override
    public MinosTeamApi team() {
        return teamApi;
    }

    @Override
    public void close() throws MinosApiException {
        if (!ownsApplication) return;
        try {
            application.close();
        } catch (Exception exception) {
            throw MinosApiSupport.publicFailure(ErrorCode.IO_FAILURE, "MINOS API shutdown failed", exception);
        }
    }

    private static ProjectDto project(ProjectOperations.ProjectView view) {
        return new ProjectDto(
                view.id(), view.name(), view.rootPath(), view.rootAvailable(),
                view.languages(), view.buildSystems(), view.moduleCount(), view.indexState(),
                view.activeSnapshotId(), view.lastSuccessfulIndexAt(), view.providerId(), view.providerVersion()
        );
    }

    private static IndexImportDto indexImport(ProjectOperations.IndexImportResult result) {
        return new IndexImportDto(
                result.projectId(), result.snapshotId(), result.providerId(), result.providerVersion(),
                result.normalizedSymbolCount(), result.occurrenceCount(), result.relationshipCount(),
                result.relatedTestRelationshipCount(), result.unresolvedOccurrenceCount(),
                result.unresolvedRelationshipCount(), result.completedAt()
        );
    }

    private static SymbolDto symbol(SymbolResult value) {
        return new SymbolDto(
                value.id(), value.symbolKey(), value.identityQuality().name(), value.projectId(),
                value.moduleId(), value.fileId(), value.kind().name(), value.name(), value.qualifiedName(),
                value.signature(), value.language(), location(value.location()), value.resolutionStatus().name(),
                origin(value.origin()), value.external(), value.generated()
        );
    }

    private static UsageDto usage(UsageResult value) {
        return new UsageDto(
                value.id(), value.projectId(), value.symbolId(), location(value.location()),
                value.roles().stream().map(Enum::name).sorted().toList(),
                value.resolutionStatus().name(), origin(value.origin())
        );
    }

    private static RelationshipDto relationship(RelationshipResult value) {
        return new RelationshipDto(
                value.id(), value.projectId(), entity(value.source()), entity(value.target()),
                value.unresolvedTarget(), value.kind().name(), location(value.location()),
                value.resolutionStatus().name(), value.nature().name(), value.confidence(),
                origin(value.origin()), value.evidence().stream().map(LocalMinosApi::evidence).toList()
        );
    }

    private static ArchitectureDto architecture(ArchitectureIntelligenceView view) {
        return new ArchitectureDto(
                view.projectId(),
                view.projectName(),
                view.snapshotId(),
                view.nature().name(),
                view.overview().languages(),
                view.overview().buildSystems(),
                view.overview().moduleCount(),
                view.overview().localSymbolCount(),
                view.overview().externalSymbolCount(),
                view.overview().relationshipCount(),
                view.dependencies().totalDependencyCount(),
                view.dependencies().interModuleDependencyCount(),
                view.dependencies().intraModuleDependencyCount(),
                view.dependencies().unassignedDependencyCount(),
                view.dependencies().moduleEdgeCount(),
                view.centrality().topIncomingModuleIds(),
                view.centrality().topOutgoingModuleIds(),
                view.technologies().technologies().stream().map(value -> value.name()).toList(),
                view.overview().modules().stream().map(LocalMinosApi::architectureModule).toList()
        );
    }

    private static ArchitectureGraphDto architectureGraph(ArchitectureIntelligenceView view) {
        Map<String, ArchitectureModule> modulesById = new LinkedHashMap<>();
        view.overview().modules().forEach(module -> modulesById.put(module.id(), module));
        return new ArchitectureGraphDto(
                view.projectId(),
                view.projectName(),
                view.snapshotId(),
                view.nature().name(),
                view.overview().modules().stream().map(LocalMinosApi::architectureModule).toList(),
                view.dependencies().dependencies().stream()
                        .map(edge -> architectureDependency(edge, modulesById))
                        .toList()
        );
    }

    private static ArchitectureDependencyDto architectureDependency(
            ArchitectureModuleDependency edge,
            Map<String, ArchitectureModule> modulesById
    ) {
        ArchitectureModule source = modulesById.get(edge.sourceModuleId());
        ArchitectureModule target = modulesById.get(edge.targetModuleId());
        return new ArchitectureDependencyDto(
                edge.id(),
                edge.sourceModuleId(),
                source == null ? null : source.name(),
                edge.targetModuleId(),
                target == null ? null : target.name(),
                edge.dependencyCount(),
                edge.sourceSymbolCount(),
                edge.targetSymbolCount(),
                edge.sampleDependencyIds(),
                edge.nature().name(),
                edge.confidence()
        );
    }

    private static ModuleContextDto moduleContext(ArchitectureModuleContext context) {
        return new ModuleContextDto(
                context.projectId(),
                context.snapshotId(),
                context.nature().name(),
                architectureModule(context.module()),
                context.incomingModuleEdgeCount(),
                context.outgoingModuleEdgeCount(),
                context.concentration().incomingDependencyCount(),
                context.concentration().outgoingDependencyCount(),
                context.centrality().incomingRank(),
                context.centrality().outgoingRank(),
                context.technologies().stream().map(value -> value.name()).toList()
        );
    }

    private static ArchitectureModuleDto architectureModule(ArchitectureModule module) {
        return new ArchitectureModuleDto(
                module.id(), module.name(), module.relativePath(), module.buildSystems(), module.languages(),
                module.sourceRootCount(), module.symbolCount(), module.namespaceCount()
        );
    }

    private static ImpactReportDto impact(ImpactAnalysisReport report) {
        return new ImpactReportDto(
                report.projectId().toString(),
                report.snapshotId(),
                report.nature().name(),
                impactSymbol(report.rootSymbol()),
                report.request().maxDepth(),
                report.request().maxResults(),
                report.limitations().stream().map(Enum::name).toList(),
                report.impacts().stream().map(LocalMinosApi::impactItem).toList(),
                report.potentiallyImpactedTests().stream().map(LocalMinosApi::impactItem).toList()
        );
    }

    private static ImpactItemDto impactItem(ImpactedSymbol value) {
        return new ImpactItemDto(
                impactSymbol(value.symbol()),
                value.level().name(),
                value.depth(),
                value.confidence(),
                value.nature().name(),
                value.testImpact(),
                value.path().stream().map(LocalMinosApi::impactStep).toList()
        );
    }

    private static ImpactPathStepDto impactStep(ImpactPathStep step) {
        return new ImpactPathStepDto(
                step.changedSymbolId(), step.impactedSymbolId(), step.relationshipId(),
                step.relationshipKind().name(), step.relationshipNature().name(), step.confidence()
        );
    }

    private static ImpactSymbolDto impactSymbol(Symbol symbol) {
        return new ImpactSymbolDto(
                symbol.id(), symbol.name(), symbol.qualifiedName(), symbol.kind().name(),
                symbol.signature(), symbol.fileId(), symbol.moduleId()
        );
    }

    private static LocationDto location(SymbolLocation value) {
        if (value == null) {
            return null;
        }
        return new LocationDto(
                value.fileId(), value.startLine(), value.startColumn(), value.endLine(), value.endColumn(),
                value.positionEncoding().name()
        );
    }

    private static OriginDto origin(Origin value) {
        if (value == null) {
            return null;
        }
        return new OriginDto(
                value.providerId(), value.providerType(), value.providerVersion(), value.indexRunId(),
                value.sourceType().name()
        );
    }

    private static EntityRefDto entity(CodeEntityRef value) {
        return value == null ? null : new EntityRefDto(value.type().name(), value.id());
    }

    private static EvidenceDto evidence(Evidence value) {
        return new EvidenceDto(
                value.type().name(), value.description(), entity(value.source()), entity(value.target()),
                location(value.location()), value.weight()
        );
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            String value,
            String field,
            boolean nullable
    ) {
        if (value == null || value.isBlank()) {
            if (nullable) {
                return null;
            }
            throw new IllegalArgumentException(field + " must not be blank");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("unsupported " + field + ": " + value, exception);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireLimit(int value, String field) {
        if (value < 1 || value > 10_000) {
            throw new IllegalArgumentException(field + " must be between 1 and 10000");
        }
    }

    private static <T> T required(T value, String field) {
        return MinosApiSupport.required(value, field);
    }

    private static <T> T execute(MinosApiSupport.ApiCall<T> call) throws MinosApiException {
        return MinosApiSupport.execute(call);
    }

    private static MinosApplication openApplication(Path home) throws MinosApiException {
        return MinosApiSupport.openApplication(home, "MINOS API bootstrap failed");
    }
}
