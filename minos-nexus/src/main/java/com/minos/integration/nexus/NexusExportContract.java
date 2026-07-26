package com.minos.integration.nexus;

import java.util.List;
import java.util.Objects;

/**
 * Versioned, transport-neutral contract exported by MINOS for NEXUS consumption.
 *
 * <p>The contract deliberately contains only JDK types and MINOS-owned DTOs. It does
 * not depend on NEXUS classes and may therefore cross the Java 24 / Java 21 process
 * boundary as JSON.</p>
 */
public final class NexusExportContract {

    public static final String CONTRACT_VERSION = "1";
    public static final String PRODUCER = "MINOS";

    private NexusExportContract() {
    }

    public record ExportSnapshot(
            String contractVersion,
            String producer,
            ExportProject project,
            List<ExportSymbol> symbols,
            List<ExportRelation> relations,
            List<String> limitations
    ) {
        public ExportSnapshot {
            requireText(contractVersion, "contractVersion");
            requireText(producer, "producer");
            Objects.requireNonNull(project, "project");
            symbols = immutable(symbols);
            relations = immutable(relations);
            limitations = immutable(limitations);
        }
    }

    public record ExportProject(
            String id,
            String name,
            String rootPath,
            String snapshotId
    ) {
        public ExportProject {
            requireText(id, "id");
            requireText(name, "name");
            requireText(rootPath, "rootPath");
            requireText(snapshotId, "snapshotId");
        }
    }

    public record ExportOrigin(
            String providerId,
            String providerType,
            String providerVersion,
            String indexRunId,
            String sourceType
    ) {
        public ExportOrigin {
            requireText(providerId, "providerId");
            requireText(sourceType, "sourceType");
        }
    }

    public record ExportEvidence(
            String type,
            String description,
            Double weight
    ) {
        public ExportEvidence {
            requireText(type, "type");
            requireText(description, "description");
        }
    }

    public record ExportSymbol(
            String id,
            String symbolKey,
            String filePath,
            String moduleId,
            String kind,
            String name,
            String qualifiedName,
            String signature,
            String language,
            int startLine,
            int endLine,
            String resolutionStatus,
            String identityQuality,
            boolean generated,
            ExportOrigin origin
    ) {
        public ExportSymbol {
            requireText(id, "id");
            requireText(symbolKey, "symbolKey");
            requireText(filePath, "filePath");
            requireText(kind, "kind");
            requireText(name, "name");
            requireText(language, "language");
            requireText(resolutionStatus, "resolutionStatus");
            requireText(identityQuality, "identityQuality");
            Objects.requireNonNull(origin, "origin");
            if (startLine < 1 || endLine < startLine) {
                throw new IllegalArgumentException("invalid symbol line range");
            }
        }
    }

    public record ExportRelation(
            String id,
            String filePath,
            String kind,
            String sourceId,
            String sourceQualifiedName,
            String targetId,
            String targetQualifiedName,
            String resolutionStatus,
            String nature,
            Double confidence,
            ExportOrigin origin,
            List<ExportEvidence> evidence
    ) {
        public ExportRelation {
            requireText(id, "id");
            requireText(filePath, "filePath");
            requireText(kind, "kind");
            requireText(sourceId, "sourceId");
            requireText(sourceQualifiedName, "sourceQualifiedName");
            requireText(targetId, "targetId");
            requireText(targetQualifiedName, "targetQualifiedName");
            requireText(resolutionStatus, "resolutionStatus");
            requireText(nature, "nature");
            Objects.requireNonNull(origin, "origin");
            evidence = immutable(evidence);
            if (confidence != null && (!Double.isFinite(confidence) || confidence < 0.0 || confidence > 1.0)) {
                throw new IllegalArgumentException("confidence must be between 0 and 1");
            }
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
