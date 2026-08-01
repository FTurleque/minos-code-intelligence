package com.minos.api;

import java.io.Serial;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Public, provider-independent Java contract for consuming MINOS Code Intelligence.
 *
 * <p>The public surface deliberately uses only JDK types and DTOs declared here.
 * Consumers therefore do not depend on SCIP, Glean, CLI/MCP contracts, storage
 * implementations or MINOS internal domain models.</p>
 */
public interface MinosApi {

    String CONTRACT_VERSION = "1";

    default String contractVersion() {
        return CONTRACT_VERSION;
    }

    ProjectDto addProject(Path rootPath, String displayName) throws MinosApiException;

    List<ProjectDto> listProjects() throws MinosApiException;

    ProjectDto getProject(String projectIdentifier) throws MinosApiException;

    IndexImportDto importScip(
            String projectIdentifier,
            Path indexFile,
            IndexImportRequest request
    ) throws MinosApiException;

    List<SymbolDto> findSymbols(
            String projectIdentifier,
            SymbolQuery query
    ) throws MinosApiException;

    List<UsageDto> findUsages(
            String projectIdentifier,
            String symbolId,
            int limit
    ) throws MinosApiException;

    List<RelationshipDto> findRelationships(
            String projectIdentifier,
            RelationshipQuery query
    ) throws MinosApiException;

    ArchitectureDto getArchitecture(String projectIdentifier) throws MinosApiException;

    /**
     * Returns the actual inter-module dependency graph behind the architecture
     * aggregate. This default keeps contract-v1 source/binary compatibility for
     * third-party implementations while LocalMinosApi provides the full graph.
     */
    default ArchitectureGraphDto getArchitectureGraph(String projectIdentifier) throws MinosApiException {
        throw new MinosApiException(ErrorCode.UNAVAILABLE, "architecture graph is not available in this implementation");
    }

    ModuleContextDto getModuleContext(
            String projectIdentifier,
            String moduleIdentifier
    ) throws MinosApiException;

    ImpactReportDto analyzeImpact(
            String projectIdentifier,
            ImpactQuery query
    ) throws MinosApiException;

    /** Opt-in M27 team surface; default preserves third-party contract-v1 implementations. */
    default MinosTeamApi team() throws MinosApiException {
        throw new MinosApiException(ErrorCode.UNAVAILABLE, "team mode is not available in this implementation");
    }

    enum ErrorCode {
        INVALID_REQUEST,
        ACCESS_DENIED,
        UNAVAILABLE,
        IO_FAILURE,
        EXECUTION_FAILURE
    }

    final class MinosApiException extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;

        private final ErrorCode code;

        public MinosApiException(ErrorCode code, String message) {
            super(message);
            this.code = Objects.requireNonNull(code, "code");
        }

        public MinosApiException(ErrorCode code, String message, Throwable cause) {
            super(message, cause);
            this.code = Objects.requireNonNull(code, "code");
        }

        public ErrorCode code() {
            return code;
        }
    }

    record IndexImportRequest(
            String providerId,
            String providerVersion,
            String moduleId,
            String snapshotId
    ) {
        public IndexImportRequest {
            requireText(providerId, "providerId");
        }
    }

    record SymbolQuery(
            String text,
            String qualifiedName,
            String kind,
            String moduleId,
            int limit
    ) {
        public SymbolQuery {
            text = blankToNull(text);
            qualifiedName = blankToNull(qualifiedName);
            kind = blankToNull(kind);
            moduleId = blankToNull(moduleId);
            if (text == null && qualifiedName == null && kind == null && moduleId == null) {
                throw new IllegalArgumentException("at least one symbol criterion is required");
            }
            requireLimit(limit, 10_000, "limit");
        }

        public static SymbolQuery lexical(String text, int limit) {
            return new SymbolQuery(text, null, null, null, limit);
        }
    }

    record RelationshipQuery(
            String anchorType,
            String anchorId,
            String direction,
            Set<String> kinds,
            String resolutionStatus,
            String nature,
            int limit
    ) {
        public RelationshipQuery {
            requireText(anchorType, "anchorType");
            requireText(anchorId, "anchorId");
            requireText(direction, "direction");
            kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
            resolutionStatus = blankToNull(resolutionStatus);
            nature = blankToNull(nature);
            requireLimit(limit, 10_000, "limit");
        }

        public static RelationshipQuery outgoingSymbol(String symbolId, Set<String> kinds, int limit) {
            return new RelationshipQuery("SYMBOL", symbolId, "OUTGOING", kinds, null, null, limit);
        }

        public static RelationshipQuery incomingSymbol(String symbolId, Set<String> kinds, int limit) {
            return new RelationshipQuery("SYMBOL", symbolId, "INCOMING", kinds, null, null, limit);
        }
    }

    record ImpactQuery(String symbolId, int maxDepth, int maxResults) {
        public ImpactQuery {
            requireText(symbolId, "symbolId");
            if (maxDepth < 1 || maxDepth > 32) {
                throw new IllegalArgumentException("maxDepth must be between 1 and 32");
            }
            requireLimit(maxResults, 10_000, "maxResults");
        }

        public static ImpactQuery defaults(String symbolId) {
            return new ImpactQuery(symbolId, 4, 200);
        }
    }

    record ProjectDto(
            String id,
            String name,
            String rootPath,
            boolean rootAvailable,
            List<String> languages,
            List<String> buildSystems,
            int moduleCount,
            String indexState,
            String activeSnapshotId,
            String lastSuccessfulIndexAt,
            String providerId,
            String providerVersion
    ) {
        public ProjectDto {
            languages = immutable(languages);
            buildSystems = immutable(buildSystems);
        }
    }

    record IndexImportDto(
            String projectId,
            String snapshotId,
            String providerId,
            String providerVersion,
            int normalizedSymbolCount,
            int occurrenceCount,
            int relationshipCount,
            int relatedTestRelationshipCount,
            int unresolvedOccurrenceCount,
            int unresolvedRelationshipCount,
            String completedAt
    ) {
    }

    record LocationDto(
            String fileId,
            int startLine,
            int startColumn,
            int endLine,
            int endColumn,
            String positionEncoding
    ) {
    }

    record OriginDto(
            String providerId,
            String providerType,
            String providerVersion,
            String indexRunId,
            String sourceType
    ) {
    }

    record EntityRefDto(String type, String id) {
    }

    record EvidenceDto(
            String type,
            String description,
            EntityRefDto source,
            EntityRefDto target,
            LocationDto location,
            Double weight
    ) {
    }

    record SymbolDto(
            String id,
            String symbolKey,
            String identityQuality,
            String projectId,
            String moduleId,
            String fileId,
            String kind,
            String name,
            String qualifiedName,
            String signature,
            String language,
            LocationDto location,
            String resolutionStatus,
            OriginDto origin,
            boolean external,
            boolean generated
    ) {
    }

    record UsageDto(
            String id,
            String projectId,
            String symbolId,
            LocationDto location,
            List<String> roles,
            String resolutionStatus,
            OriginDto origin
    ) {
        public UsageDto {
            roles = immutable(roles);
        }
    }

    record RelationshipDto(
            String id,
            String projectId,
            EntityRefDto source,
            EntityRefDto target,
            String unresolvedTarget,
            String kind,
            LocationDto location,
            String resolutionStatus,
            String nature,
            Double confidence,
            OriginDto origin,
            List<EvidenceDto> evidence
    ) {
        public RelationshipDto {
            evidence = immutable(evidence);
        }
    }

    record ArchitectureModuleDto(
            String id,
            String name,
            String relativePath,
            List<String> buildSystems,
            List<String> languages,
            int sourceRootCount,
            int symbolCount,
            int namespaceCount
    ) {
        public ArchitectureModuleDto {
            buildSystems = immutable(buildSystems);
            languages = immutable(languages);
        }
    }

    record ArchitectureDto(
            String projectId,
            String projectName,
            String snapshotId,
            String nature,
            List<String> languages,
            List<String> buildSystems,
            int moduleCount,
            int localSymbolCount,
            int externalSymbolCount,
            int relationshipCount,
            int totalDependencyCount,
            int interModuleDependencyCount,
            int intraModuleDependencyCount,
            int unassignedDependencyCount,
            int moduleEdgeCount,
            List<String> topIncomingModuleIds,
            List<String> topOutgoingModuleIds,
            List<String> technologies,
            List<ArchitectureModuleDto> modules
    ) {
        public ArchitectureDto {
            languages = immutable(languages);
            buildSystems = immutable(buildSystems);
            topIncomingModuleIds = immutable(topIncomingModuleIds);
            topOutgoingModuleIds = immutable(topOutgoingModuleIds);
            technologies = immutable(technologies);
            modules = immutable(modules);
        }
    }

    record ArchitectureDependencyDto(
            String id,
            String sourceModuleId,
            String sourceModuleName,
            String targetModuleId,
            String targetModuleName,
            int dependencyCount,
            int sourceSymbolCount,
            int targetSymbolCount,
            List<String> sampleDependencyIds,
            String nature,
            Double confidence
    ) {
        public ArchitectureDependencyDto {
            sampleDependencyIds = immutable(sampleDependencyIds);
        }
    }

    record ArchitectureGraphDto(
            String projectId,
            String projectName,
            String snapshotId,
            String nature,
            List<ArchitectureModuleDto> modules,
            List<ArchitectureDependencyDto> dependencies
    ) {
        public ArchitectureGraphDto {
            modules = immutable(modules);
            dependencies = immutable(dependencies);
        }

        public int moduleCount() {
            return modules.size();
        }

        public int edgeCount() {
            return dependencies.size();
        }
    }

    record ModuleContextDto(
            String projectId,
            String snapshotId,
            String nature,
            ArchitectureModuleDto module,
            int incomingModuleEdgeCount,
            int outgoingModuleEdgeCount,
            int incomingDependencyCount,
            int outgoingDependencyCount,
            int incomingRank,
            int outgoingRank,
            List<String> technologies
    ) {
        public ModuleContextDto {
            technologies = immutable(technologies);
        }
    }

    record ImpactSymbolDto(
            String id,
            String name,
            String qualifiedName,
            String kind,
            String signature,
            String fileId,
            String moduleId
    ) {
    }

    record ImpactPathStepDto(
            String changedSymbolId,
            String impactedSymbolId,
            String relationshipId,
            String relationshipKind,
            String relationshipNature,
            double confidence
    ) {
    }

    record ImpactItemDto(
            ImpactSymbolDto symbol,
            String level,
            int depth,
            double confidence,
            String nature,
            boolean testImpact,
            List<ImpactPathStepDto> path
    ) {
        public ImpactItemDto {
            path = immutable(path);
        }
    }

    record ImpactReportDto(
            String projectId,
            String snapshotId,
            String nature,
            ImpactSymbolDto rootSymbol,
            int maxDepth,
            int maxResults,
            List<String> limitations,
            List<ImpactItemDto> impacts,
            List<ImpactItemDto> potentiallyImpactedTests
    ) {
        public ImpactReportDto {
            limitations = immutable(limitations);
            impacts = immutable(impacts);
            potentiallyImpactedTests = immutable(potentiallyImpactedTests);
        }

        public int impactCount() {
            return impacts.size();
        }

        public int testCount() {
            return potentiallyImpactedTests.size();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireLimit(int value, int maximum, String name) {
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(name + " must be between 1 and " + maximum);
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
