package com.minos.application;

import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotReport;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.io.BoundedFileDigest;
import com.minos.orchestration.IndexArtifactLimits;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.ProjectIndexLease;
import com.minos.orchestration.ProjectIndexState;
import com.minos.orchestration.ProviderId;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Local application adapter over the selected MINOS storage backend. */
public final class LocalProjectOperations implements ProjectOperations, AutoCloseable {
    private final MinosApplication ownedApplication;
    private final Path home;
    private final ProjectRegistry registry;
    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;
    private final ProjectInspectionService inspectionService;
    private final Path historyDirectory;

    public LocalProjectOperations(Path home) throws IOException { this(MinosApplication.open(home), true); }
    public LocalProjectOperations(MinosApplication application) { this(application, false); }

    private LocalProjectOperations(MinosApplication application, boolean ownsApplication) {
        MinosApplication value = Objects.requireNonNull(application, "application");
        this.ownedApplication = ownsApplication ? value : null;
        this.home = value.home();
        this.registry = value.projectRegistry();
        this.projectResolver = new ProjectResolver(registry);
        this.snapshotStore = value.snapshotStore();
        this.stateStore = value.indexStateStore();
        this.inspectionService = value.projectInspectionService();
        this.historyDirectory = home.resolve("cli-index-history");
    }

    @Override public ProjectView addProject(Path rootPath, String displayName) throws IOException {
        return projectView(inspectionService.view(registry.registerProject(rootPath, displayName)));
    }
    @Override public List<ProjectView> listProjects() throws IOException {
        return inspectionService.listProjects().stream().map(LocalProjectOperations::projectView).toList();
    }
    @Override public ProjectView inspectProject(String projectIdentifier) throws IOException {
        return projectView(inspectionService.inspectProject(projectIdentifier));
    }

    @Override
    public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId,
                                        String providerVersion, String moduleId, String snapshotId) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        try (ProjectIndexLease ignored = ProjectIndexLease.acquire(home, project.id())) {
            return importScipLocked(project, indexFile, providerId, providerVersion, moduleId, snapshotId);
        }
    }

    private IndexImportResult importScipLocked(RegisteredProject project, Path indexFile, String providerId,
                                                String providerVersion, String moduleId, String snapshotId)
            throws IOException {
        Path artifact = Objects.requireNonNull(indexFile, "indexFile").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(artifact) || !Files.isRegularFile(artifact, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("SCIP artifact must be an existing regular file: " + artifact);
        }
        String safeProviderId = ProviderId.require(providerId);
        String effectiveSnapshotId = snapshotId == null || snapshotId.isBlank()
                ? "scip-" + BoundedFileDigest.sha256Exact(
                        artifact, IndexArtifactLimits.MAX_SCIP_ARTIFACT_BYTES, "SCIP artifact").substring(0, 24)
                : snapshotId;
        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(
                artifact,
                new ScipSymbolSnapshotRequest(project.id(), effectiveSnapshotId, blankToNull(moduleId), safeProviderId,
                        blankToNull(providerVersion), "application-" + effectiveSnapshotId, java.util.Map.of()),
                snapshotStore);

        // importSnapshot() has crossed the authoritative active-snapshot commit point. A following
        // metadata failure must therefore be reported as a committed operation requiring recovery,
        // never as a generic failed import that invites an unsafe blind retry.
        Instant completedAt = Instant.now();
        ProjectIndexState committedState = new ProjectIndexState(project.id(), ProjectIndexState.Availability.READY,
                Optional.of(effectiveSnapshotId), Optional.empty(), completedAt,
                Optional.of("active snapshot imported explicitly through import-scip"));
        CommitOutcome commitOutcome = saveCommittedState(committedState);
        try {
            writeHistory(project.id(), new IndexHistory(
                    effectiveSnapshotId, safeProviderId, blankToNull(providerVersion), completedAt));
        } catch (IOException ignored) {
            // CLI history is secondary evidence. The active snapshot remains authoritative.
        }

        return new IndexImportResult(project.id().toString(), report.snapshotId(), safeProviderId,
                blankToNull(providerVersion), report.normalizedSymbolCount(), report.occurrenceCount(),
                report.relationshipCount(), report.relatedTestRelationshipCount(), report.unresolvedOccurrenceCount(),
                report.unresolvedRelationshipCount(), completedAt.toString(), commitOutcome.status(),
                commitOutcome.diagnostic());
    }

    private CommitOutcome saveCommittedState(ProjectIndexState state) {
        RuntimeException firstFailure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                stateStore.saveProjectState(state);
                return new CommitOutcome(IndexImportCommitStatus.COMMITTED, null);
            } catch (RuntimeException failure) {
                if (firstFailure == null) firstFailure = failure;
                else firstFailure.addSuppressed(failure);
            }
        }
        return new CommitOutcome(
                IndexImportCommitStatus.COMMITTED_METADATA_PENDING,
                "active snapshot committed; project index metadata persistence failed after 2 attempts: "
                        + safeMessage(firstFailure));
    }

    private static String safeMessage(RuntimeException failure) {
        if (failure == null) return "unknown state persistence failure";
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    private static ProjectView projectView(ProjectInspectionService.ProjectView view) {
        return new ProjectView(view.id(), view.name(), view.rootPath(), view.rootAvailable(), view.languages(),
                view.buildSystems(), view.moduleCount(), view.indexState(), view.activeSnapshotId(),
                view.lastSuccessfulIndexAt(), view.providerId(), view.providerVersion());
    }

    private void writeHistory(UUID projectId, IndexHistory history) throws IOException {
        Files.createDirectories(historyDirectory);
        Path target = historyDirectory.resolve(projectId + ".properties");
        Path temporary = Files.createTempFile(historyDirectory, projectId + ".", ".tmp");
        Properties properties = new Properties();
        properties.setProperty("snapshotId", history.snapshotId());
        properties.setProperty("providerId", history.providerId());
        properties.setProperty("providerVersion", history.providerVersion() == null ? "" : history.providerVersion());
        properties.setProperty("completedAt", history.completedAt().toString());
        try {
            try (OutputStream output = Files.newOutputStream(
                    temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "MINOS project index history");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }

    private record CommitOutcome(IndexImportCommitStatus status, String diagnostic) {
        private CommitOutcome {
            Objects.requireNonNull(status, "status");
        }
    }

    private record IndexHistory(String snapshotId, String providerId, String providerVersion, Instant completedAt) {
        private IndexHistory {
            if (snapshotId == null || snapshotId.isBlank()) throw new IllegalArgumentException("snapshotId must not be blank");
            ProviderId.require(providerId);
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }

    @Override public void close() throws IOException { if (ownedApplication != null) ownedApplication.close(); }
}
