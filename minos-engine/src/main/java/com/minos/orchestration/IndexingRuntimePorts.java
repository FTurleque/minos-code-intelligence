package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.orchestration.IndexerNegotiationResult.IndexerSelection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
     * Explicit observation of the authoritative active snapshot state.
     *
     * <p>The status deliberately distinguishes an authority that cannot be observed from an
     * observable authority that currently has no active snapshot. This prevents persistent
     * lifecycle metadata from treating a missing authoritative snapshot as an unsupported
     * capability.</p>
     */
    public record ActiveSnapshotObservation(Status status, Optional<String> snapshotId) {
        public enum Status {
            UNSUPPORTED,
            NO_ACTIVE_SNAPSHOT,
            ACTIVE
        }

        public ActiveSnapshotObservation {
            status = Objects.requireNonNull(status, "status");
            snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
            if (status == Status.ACTIVE) {
                String id = snapshotId.orElseThrow(() ->
                        new IllegalArgumentException("ACTIVE observation requires a snapshot id"));
                if (id.isBlank()) {
                    throw new IllegalArgumentException("active snapshot id must not be blank");
                }
            } else if (snapshotId.isPresent()) {
                throw new IllegalArgumentException(status + " observation must not carry a snapshot id");
            }
        }

        public static ActiveSnapshotObservation unsupported() {
            return new ActiveSnapshotObservation(Status.UNSUPPORTED, Optional.empty());
        }

        public static ActiveSnapshotObservation noActiveSnapshot() {
            return new ActiveSnapshotObservation(Status.NO_ACTIVE_SNAPSHOT, Optional.empty());
        }

        public static ActiveSnapshotObservation active(String snapshotId) {
            return new ActiveSnapshotObservation(Status.ACTIVE, Optional.of(
                    Objects.requireNonNull(snapshotId, "snapshotId")));
        }
    }

    /**
     * Canonical identity captured before an execution request crosses into the provider runtime.
     *
     * <p>The canonical path protects against symlink retargeting. The optional file key additionally
     * detects replacement of a directory by a different filesystem object at the same pathname when
     * the underlying filesystem exposes a stable identity. A missing file key deliberately degrades
     * to canonical-path identity rather than inventing a weaker synthetic identifier.</p>
     */
    public record ExecutionPathAuthorization(
            Path registeredProjectRoot,
            Path projectRoot,
            Optional<String> registeredProjectFileKey,
            Optional<String> projectFileKey
    ) {
        public ExecutionPathAuthorization {
            registeredProjectRoot = normalizedAbsolute(registeredProjectRoot, "registeredProjectRoot");
            projectRoot = normalizedAbsolute(projectRoot, "projectRoot");
            registeredProjectFileKey = Objects.requireNonNull(
                    registeredProjectFileKey, "registeredProjectFileKey");
            projectFileKey = Objects.requireNonNull(projectFileKey, "projectFileKey");
            if (!projectRoot.startsWith(registeredProjectRoot)) {
                throw new IllegalArgumentException(
                        "authorized projectRoot must stay inside authorized registeredProjectRoot");
            }
        }

        public static Optional<ExecutionPathAuthorization> tryCapture(
                Path registeredProjectRoot,
                Path projectRoot
        ) {
            try {
                Path realRegisteredRoot = normalizedAbsolute(
                        registeredProjectRoot, "registeredProjectRoot").toRealPath();
                Path realProjectRoot = normalizedAbsolute(projectRoot, "projectRoot").toRealPath();
                if (!realProjectRoot.startsWith(realRegisteredRoot)) {
                    throw new IllegalArgumentException(
                            "projectRoot resolves outside registeredProjectRoot");
                }
                return Optional.of(new ExecutionPathAuthorization(
                        realRegisteredRoot,
                        realProjectRoot,
                        fileKey(realRegisteredRoot),
                        fileKey(realProjectRoot)));
            } catch (IOException unavailable) {
                // Some protocol/contract tests intentionally construct requests for paths that are
                // not materialized. Local process execution rejects an absent authorization before
                // launch; non-process ports can continue to use the historical request contract.
                return Optional.empty();
            }
        }

        /**
         * Re-resolves the lexical request roots and fails closed if either identity changed.
         */
        public void verifyCurrent(Path currentRegisteredProjectRoot, Path currentProjectRoot) {
            final Path realRegisteredRoot;
            final Path realProjectRoot;
            final Optional<String> currentRegisteredFileKey;
            final Optional<String> currentProjectFileKey;
            try {
                realRegisteredRoot = normalizedAbsolute(
                        currentRegisteredProjectRoot, "currentRegisteredProjectRoot").toRealPath();
                realProjectRoot = normalizedAbsolute(currentProjectRoot, "currentProjectRoot").toRealPath();
                currentRegisteredFileKey = fileKey(realRegisteredRoot);
                currentProjectFileKey = fileKey(realProjectRoot);
            } catch (IOException failure) {
                throw new IllegalStateException(
                        "provider execution path identity cannot be revalidated before launch", failure);
            }

            if (!realRegisteredRoot.equals(registeredProjectRoot)
                    || !realProjectRoot.equals(projectRoot)
                    || !fileIdentityMatches(registeredProjectFileKey, currentRegisteredFileKey)
                    || !fileIdentityMatches(projectFileKey, currentProjectFileKey)) {
                throw new IllegalStateException(
                        "provider execution path identity changed after canonical authorization");
            }
            if (!realProjectRoot.startsWith(realRegisteredRoot)) {
                throw new IllegalStateException(
                        "provider execution path no longer resolves inside the registered project root");
            }
        }

        private static Optional<String> fileKey(Path realPath) throws IOException {
            Object key = Files.readAttributes(
                    realPath, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
            return key == null ? Optional.empty() : Optional.of(key.toString());
        }

        private static boolean fileIdentityMatches(Optional<String> authorized, Optional<String> current) {
            return authorized.isEmpty() || authorized.equals(current);
        }

        private static Path normalizedAbsolute(Path value, String label) {
            return Objects.requireNonNull(value, label).toAbsolutePath().normalize();
        }
    }

    /**
     * One provider execution request.
     *
     * <p>{@code registeredProjectRoot} is the root persisted in the MINOS project registry.
     * {@code projectRoot} is the concrete provider/build root for this execution. They are
     * equal for a single-root project. In a polyglot monorepo, {@code projectRoot} can be a
     * nested module while {@code projectRelativeRoot} preserves its portable position inside
     * the registered project.</p>
     *
     * <p>{@code pathAuthorization} snapshots the canonical filesystem identity at request
     * construction whenever both roots are materialized. Local process execution requires this
     * authorization and revalidates it immediately before {@code ProcessBuilder.start()}.</p>
     */
    public record IndexingExecutionRequest(
            UUID runId,
            UUID projectId,
            Path registeredProjectRoot,
            Path projectRoot,
            Path projectRelativeRoot,
            IndexerSelection selection,
            IndexingMode mode,
            List<String> changedFiles,
            Optional<ExecutionPathAuthorization> pathAuthorization
    ) {
        public IndexingExecutionRequest {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(projectId, "projectId");
            registeredProjectRoot = normalizedAbsolute(registeredProjectRoot, "registeredProjectRoot");
            projectRoot = normalizedAbsolute(projectRoot, "projectRoot");
            projectRelativeRoot = normalizedRelative(projectRelativeRoot, "projectRelativeRoot");
            if (!projectRoot.startsWith(registeredProjectRoot)) {
                throw new IllegalArgumentException("projectRoot must stay inside registeredProjectRoot");
            }
            Path expectedRelative = registeredProjectRoot.relativize(projectRoot).normalize();
            if (!expectedRelative.equals(projectRelativeRoot)) {
                throw new IllegalArgumentException("projectRelativeRoot does not match registered/project roots");
            }
            Objects.requireNonNull(selection, "selection");
            Objects.requireNonNull(mode, "mode");
            if (mode == IndexingMode.NONE) {
                throw new IllegalArgumentException("NONE is not an executable indexing mode");
            }
            changedFiles = immutableSortedPaths(changedFiles);
            pathAuthorization = Objects.requireNonNull(pathAuthorization, "pathAuthorization");
            pathAuthorization.ifPresent(authorization ->
                    authorization.verifyCurrent(registeredProjectRoot, projectRoot));
            if (mode == IndexingMode.FULL && !changedFiles.isEmpty()) {
                throw new IllegalArgumentException("FULL execution must not expose a partial changed-file scope");
            }
            if (mode == IndexingMode.INCREMENTAL && changedFiles.isEmpty()) {
                throw new IllegalArgumentException("INCREMENTAL execution requires changed files");
            }
        }

        /** Constructor preserving the historical request shape while capturing path identity. */
        public IndexingExecutionRequest(
                UUID runId,
                UUID projectId,
                Path registeredProjectRoot,
                Path projectRoot,
                Path projectRelativeRoot,
                IndexerSelection selection,
                IndexingMode mode,
                List<String> changedFiles
        ) {
            this(runId, projectId, registeredProjectRoot, projectRoot, projectRelativeRoot,
                    selection, mode, changedFiles,
                    ExecutionPathAuthorization.tryCapture(registeredProjectRoot, projectRoot));
        }

        /** Compatibility constructor for historical single-root executions. */
        public IndexingExecutionRequest(
                UUID runId,
                UUID projectId,
                Path projectRoot,
                IndexerSelection selection,
                IndexingMode mode,
                List<String> changedFiles
        ) {
            this(runId, projectId, projectRoot, projectRoot, Path.of(""), selection, mode, changedFiles);
        }

        public IndexingExecutionRequest(
                UUID runId,
                UUID projectId,
                Path projectRoot,
                IndexerSelection selection
        ) {
            this(runId, projectId, projectRoot, selection, IndexingMode.FULL, List.of());
        }

        private static Path normalizedAbsolute(Path value, String label) {
            return Objects.requireNonNull(value, label).toAbsolutePath().normalize();
        }

        private static Path normalizedRelative(Path value, String label) {
            Path normalized = Objects.requireNonNull(value, label).normalize();
            if (normalized.isAbsolute() || normalized.startsWith("..")) {
                throw new IllegalArgumentException(label + " must stay relative to the registered project root");
            }
            return normalized;
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

    public record IndexingArtifact(
            Language language,
            String indexerId,
            Path finalArtifact,
            Path projectRelativeRoot
    ) {
        public IndexingArtifact {
            Objects.requireNonNull(language, "language");
            if (indexerId == null || indexerId.isBlank()) {
                throw new IllegalArgumentException("indexerId must not be blank");
            }
            Objects.requireNonNull(finalArtifact, "finalArtifact");
            projectRelativeRoot = Objects.requireNonNull(projectRelativeRoot, "projectRelativeRoot").normalize();
            if (projectRelativeRoot.isAbsolute() || projectRelativeRoot.startsWith("..")) {
                throw new IllegalArgumentException("projectRelativeRoot must stay relative to the registered project root");
            }
        }

        /** Compatibility constructor for historical single-root artifacts. */
        public IndexingArtifact(Language language, String indexerId, Path finalArtifact) {
            this(language, indexerId, finalArtifact, Path.of(""));
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
