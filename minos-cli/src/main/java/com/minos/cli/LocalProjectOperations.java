package com.minos.cli;

import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotReport;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.application.MinosApplication;
import com.minos.application.ProjectInspectionService;
import com.minos.application.ProjectResolver;
import com.minos.orchestration.IndexStateStore;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Local CLI administration adapter over the selected MINOS storage backend. */
public final class LocalProjectOperations implements ProjectOperations {
    private final ProjectRegistry registry;
    private final ProjectResolver projectResolver;
    private final CodeKnowledgeSnapshotStore snapshotStore;
    private final IndexStateStore stateStore;
    private final ProjectInspectionService inspectionService;
    private final Path historyDirectory;

    public LocalProjectOperations(Path home) throws IOException { this(MinosApplication.open(home)); }
    public LocalProjectOperations(MinosApplication application) {
        MinosApplication value = Objects.requireNonNull(application, "application");
        this.registry = value.projectRegistry(); this.projectResolver = new ProjectResolver(this.registry);
        this.snapshotStore = value.snapshotStore(); this.stateStore = value.indexStateStore();
        this.inspectionService = value.projectInspectionService(); this.historyDirectory = value.home().resolve("cli-index-history");
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
    public IndexImportResult importScip(String projectIdentifier, Path indexFile, String providerId, String providerVersion,
                                       String moduleId, String snapshotId) throws IOException {
        RegisteredProject project = projectResolver.resolve(projectIdentifier);
        Path artifact = Objects.requireNonNull(indexFile, "indexFile").toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) throw new IllegalArgumentException("SCIP artifact must be an existing file: " + artifact);
        requireText(providerId, "providerId");
        String effectiveSnapshotId = snapshotId == null || snapshotId.isBlank() ? "scip-" + sha256(artifact).substring(0, 24) : snapshotId;
        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(artifact,
                new ScipSymbolSnapshotRequest(project.id(), effectiveSnapshotId, blankToNull(moduleId), providerId,
                        blankToNull(providerVersion), "cli-" + effectiveSnapshotId, java.util.Map.of()), snapshotStore);
        Instant completedAt = Instant.now();
        writeHistory(project.id(), new IndexHistory(effectiveSnapshotId, providerId, blankToNull(providerVersion), completedAt));
        stateStore.saveProjectState(new ProjectIndexState(project.id(), ProjectIndexState.Availability.READY,
                Optional.of(effectiveSnapshotId), Optional.empty(), completedAt,
                Optional.of("active snapshot imported explicitly through import-scip")));
        return new IndexImportResult(project.id().toString(), report.snapshotId(), providerId, blankToNull(providerVersion),
                report.normalizedSymbolCount(), report.occurrenceCount(), report.relationshipCount(),
                report.relatedTestRelationshipCount(), report.unresolvedOccurrenceCount(), report.unresolvedRelationshipCount(),
                completedAt.toString());
    }

    private static ProjectView projectView(ProjectInspectionService.ProjectView view) {
        return new ProjectView(view.id(), view.name(), view.rootPath(), view.rootAvailable(), view.languages(), view.buildSystems(),
                view.moduleCount(), view.indexState(), view.activeSnapshotId(), view.lastSuccessfulIndexAt(), view.providerId(), view.providerVersion());
    }

    private void writeHistory(UUID projectId, IndexHistory history) throws IOException {
        Files.createDirectories(historyDirectory);
        Path target = historyDirectory.resolve(projectId + ".properties");
        Path temporary = Files.createTempFile(historyDirectory, projectId + ".", ".tmp");
        Properties properties = new Properties();
        properties.setProperty("snapshotId", history.snapshotId()); properties.setProperty("providerId", history.providerId());
        properties.setProperty("providerVersion", history.providerVersion() == null ? "" : history.providerVersion());
        properties.setProperty("completedAt", history.completedAt().toString());
        try {
            try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "MINOS CLI index history");
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) { input.transferTo(OutputStream.nullOutputStream()); }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 is unavailable", exception); }
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private static void requireText(String value, String label) { if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank"); }
    private record IndexHistory(String snapshotId, String providerId, String providerVersion, Instant completedAt) {
        private IndexHistory { requireText(snapshotId, "snapshotId"); requireText(providerId, "providerId"); Objects.requireNonNull(completedAt, "completedAt"); }
    }
}
