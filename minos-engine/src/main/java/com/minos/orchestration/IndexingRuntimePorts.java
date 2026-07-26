package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Ports d'exécution utilisés par le cycle de vie M1 sans exposer de type fournisseur.
 */
public final class IndexingRuntimePorts {

    private IndexingRuntimePorts() {
    }

    public interface IndexerExecutor {
        String indexerId();

        IndexingArtifact execute(IndexingExecutionRequest request) throws Exception;
    }

    public interface SnapshotStager {
        String stage(IndexSnapshotStageRequest request) throws Exception;
    }

    /**
     * La promotion fournie par ce port doit être atomique au sens de l'ADR-0006.
     */
    public interface SnapshotPromoter {
        void promote(UUID projectId, UUID runId, String stagedSnapshotId) throws Exception;
    }

    public record IndexingExecutionRequest(
            UUID runId,
            UUID projectId,
            Path projectRoot,
            IndexerSelection selection,
            IndexingMode mode,
            List<String> changedFiles
    ) {
        public IndexingExecutionRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(projectRoot, "projectRoot");
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(mode, "mode");
            if (mode == IndexingMode.NONE) {
                throw new IllegalArgumentException("NONE is not an executable indexing mode");
            }
            changedFiles = immutableSortedPaths(changedFiles);
            if (mode == IndexingMode.FULL && !changedFiles.isEmpty()) {
                throw new IllegalArgumentException("FULL execution must not expose a partial changed-file scope");
            }
            if (mode == IndexingMode.INCREMENTAL && changedFiles.isEmpty()) {
                throw new IllegalArgumentException("INCREMENTAL execution requires changed files");
            }
        }

        public IndexingExecutionRequest(
                UUID runId,
                UUID projectId,
                Path projectRoot,
                IndexerSelection selection
        ) {
            this(runId, projectId, projectRoot, selection, IndexingMode.FULL, List.of());
        }

        private static List<String> immutableSortedPaths(List<String> paths) {
            List<String> copy = List.copyOf(Objects.requireNonNull(paths, "changedFiles"));
            String previous = null;
            for (String path : copy) {
                if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")) {
                    throw new IllegalArgumentException("changedFiles must contain portable relative paths");
                }
                if (previous != null && previous.compareTo(path) >= 0) {
                    throw new IllegalArgumentException("changedFiles must be strictly sorted and unique");
                }
                previous = path;
            }
            return copy;
        }
    }

    public record IndexingArtifact(Language language, String indexerId, Path finalArtifact) {
        public IndexingArtifact {
            Objects.requireNonNull(language, "language");
            if (indexerId == null || indexerId.isBlank()) {
                throw new IllegalArgumentException("indexerId must not be blank");
            }
            Objects.requireNonNull(finalArtifact, "finalArtifact");
        }
    }

    public record IndexSnapshotStageRequest(
            UUID runId,
            UUID projectId,
            List<IndexingArtifact> artifacts
    ) {
        public IndexSnapshotStageRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(projectId, "projectId");
            artifacts = List.copyOf(Objects.requireNonNull(artifacts, "artifacts"));
            if (artifacts.isEmpty()) {
                throw new IllegalArgumentException("artifacts must not be empty");
            }
        }
    }
}
