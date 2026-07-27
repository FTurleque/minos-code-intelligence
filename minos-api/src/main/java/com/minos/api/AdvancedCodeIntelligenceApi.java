package com.minos.api;

import java.util.List;
import java.util.Objects;

/** Additive, provider-independent public M19 contract. Existing MinosApi v1 remains unchanged. */
public interface AdvancedCodeIntelligenceApi {

    String CONTRACT_VERSION = "1";

    default String contractVersion() {
        return CONTRACT_VERSION;
    }

    ProgramGraphDto getProgramGraph(String projectIdentifier, ProgramGraphQuery query) throws MinosApi.MinosApiException;

    AdvancedImpactDto analyzeImpactV2(String projectIdentifier, AdvancedImpactQuery query) throws MinosApi.MinosApiException;

    SecurityReportDto analyzeSecurityPaths(String projectIdentifier, SecurityQuery query) throws MinosApi.MinosApiException;

    record ProgramGraphQuery(int maxNodes, int maxEdges) {
        public ProgramGraphQuery {
            requireRange(maxNodes, 1, 100_000, "maxNodes");
            requireRange(maxEdges, 1, 500_000, "maxEdges");
        }

        public static ProgramGraphQuery defaults() {
            return new ProgramGraphQuery(10_000, 50_000);
        }
    }

    record AdvancedImpactQuery(String symbolId, int maxDepth, int maxResults) {
        public AdvancedImpactQuery {
            requireText(symbolId, "symbolId");
            requireRange(maxDepth, 1, 32, "maxDepth");
            requireRange(maxResults, 1, 10_000, "maxResults");
        }
    }

    record SecurityQuery(String sourceNodeId, int maxDepth, int maxResults) {
        public SecurityQuery {
            if (sourceNodeId != null && sourceNodeId.isBlank()) throw new IllegalArgumentException("sourceNodeId must be null or non-blank");
            requireRange(maxDepth, 1, 32, "maxDepth");
            requireRange(maxResults, 1, 1000, "maxResults");
        }
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

    record ProgramNodeDto(
            String id,
            String symbolId,
            String kind,
            String label,
            LocationDto location,
            String nature,
            Double confidence,
            String providerId
    ) {
    }

    record ProgramEdgeDto(
            String id,
            String sourceNodeId,
            String targetNodeId,
            String kind,
            String nature,
            Double confidence,
            String providerId,
            List<String> evidence
    ) {
        public ProgramEdgeDto {
            evidence = immutable(evidence);
        }
    }

    record ProgramGraphDto(
            String projectId,
            String snapshotId,
            List<String> capabilities,
            List<ProgramNodeDto> nodes,
            List<ProgramEdgeDto> edges,
            List<String> limitations
    ) {
        public ProgramGraphDto {
            capabilities = immutable(capabilities);
            nodes = immutable(nodes);
            edges = immutable(edges);
            limitations = immutable(limitations);
        }
    }

    record AdvancedImpactItemDto(
            String symbolId,
            String label,
            int depth,
            double confidence,
            String nature,
            List<String> programEdgePath
    ) {
        public AdvancedImpactItemDto {
            programEdgePath = immutable(programEdgePath);
        }
    }

    record AdvancedImpactDto(
            String projectId,
            String snapshotId,
            int baselineImpactCount,
            int advancedAddedCount,
            int totalImpactCount,
            List<AdvancedImpactItemDto> advancedAdded,
            List<String> limitations
    ) {
        public AdvancedImpactDto {
            advancedAdded = immutable(advancedAdded);
            limitations = immutable(limitations);
        }
    }

    record SecurityPathDto(
            String sourceNodeId,
            String sourceLabel,
            String sinkNodeId,
            String sinkLabel,
            List<String> nodePath,
            List<String> edgePath,
            List<String> sanitizerNodeIds,
            boolean sanitizedPathObserved,
            double confidence,
            String nature
    ) {
        public SecurityPathDto {
            nodePath = immutable(nodePath);
            edgePath = immutable(edgePath);
            sanitizerNodeIds = immutable(sanitizerNodeIds);
        }
    }

    record SecurityReportDto(
            String projectId,
            String snapshotId,
            List<SecurityPathDto> observedPaths,
            List<String> limitations
    ) {
        public SecurityReportDto {
            observedPaths = immutable(observedPaths);
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
