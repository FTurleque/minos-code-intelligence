package com.minos.cli;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.ScipSymbolSnapshotReport;
import com.minos.adapter.scip.ScipSymbolSnapshotRequest;
import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.FileIndexStateStore;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexingRun;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Implémentation locale de l'administration CLI des projets.
 *
 * <p>M14 conserve la compatibilité d'import manuel M9 tout en rendant les états
 * persistants du lifecycle autonome visibles dans {@code inspect} et
 * {@code index-status}.</p>
 */
public final class LocalProjectOperations implements ProjectOperations {

    private final LocalProjectRegistry registry;
    private final FileSymbolSnapshotStore snapshotStore;
    private final FileIndexStateStore stateStore;
    private final ProjectDiscoveryService discoveryService;
    private final Path historyDirectory;
    private final Map<String, IndexerDescriptor> knownProviders;

    public LocalProjectOperations(Path home) throws IOException {
        Path normalizedHome = Objects.requireNonNull(home, "home").toAbsolutePath().normalize();
        this.registry = new LocalProjectRegistry(normalizedHome.resolve("registry"));
        this.snapshotStore = new FileSymbolSnapshotStore(normalizedHome.resolve("symbol-snapshots"));
        this.stateStore = new FileIndexStateStore(normalizedHome.resolve("index-state"));
        this.discoveryService = new ProjectDiscoveryService();
        this.historyDirectory = normalizedHome.resolve("cli-index-history");
        this.knownProviders = ScipIndexerCatalog.qualifiedM1Descriptors().stream()
                .collect(Collectors.toUnmodifiableMap(IndexerDescriptor::id, Function.identity()));
    }

    @Override
    public ProjectView addProject(Path rootPath, String displayName) throws IOException {
        RegisteredProject project = registry.registerProject(rootPath, displayName);
        return view(project);
    }

    @Override
    public List<ProjectView> listProjects() throws IOException {
        List<ProjectView> projects = new ArrayList<>();
        for (RegisteredProject project : registry.listProjects()) {
            projects.add(view(project));
        }
        return List.copyOf(projects);
    }

    @Override
    public ProjectView inspectProject(String projectIdentifier) throws IOException {
        return view(resolveProject(projectIdentifier));
    }

    @Override
    public IndexImportResult importScip(
            String projectIdentifier,
            Path indexFile,
            String providerId,
            String providerVersion,
            String moduleId,
            String snapshotId
    ) throws IOException {
        RegisteredProject project = resolveProject(projectIdentifier);
        Path artifact = Objects.requireNonNull(indexFile, "indexFile").toAbsolutePath().normalize();
        if (!Files.isRegularFile(artifact)) {
            throw new IllegalArgumentException("SCIP artifact must be an existing file: " + artifact);
        }
        requireText(providerId, "providerId");
        String effectiveSnapshotId = snapshotId == null || snapshotId.isBlank()
                ? "scip-" + sha256(artifact).substring(0, 24)
                : snapshotId;

        ScipSymbolSnapshotReport report = new ScipSymbolSnapshotImporter().importSnapshot(
                artifact,
                new ScipSymbolSnapshotRequest(
                        project.id(),
                        effectiveSnapshotId,
                        blankToNull(moduleId),
                        providerId,
                        blankToNull(providerVersion),
                        "cli-" + effectiveSnapshotId,
                        java.util.Map.of()
                ),
                snapshotStore
        );
        Instant completedAt = Instant.now();
        writeHistory(project.id(), new IndexHistory(
                effectiveSnapshotId,
                providerId,
                blankToNull(providerVersion),
                completedAt
        ));
        stateStore.saveProjectState(new ProjectIndexState(
                project.id(),
                ProjectIndexState.Availability.READY,
                Optional.of(effectiveSnapshotId),
                Optional.empty(),
                completedAt,
                Optional.of("active snapshot imported explicitly through import-scip")
        ));
        return new IndexImportResult(
                project.id().toString(),
                report.snapshotId(),
                providerId,
                blankToNull(providerVersion),
                report.normalizedSymbolCount(),
                report.occurrenceCount(),
                report.relationshipCount(),
                report.relatedTestRelationshipCount(),
                report.unresolvedOccurrenceCount(),
                report.unresolvedRelationshipCount(),
                completedAt.toString()
        );
    }

    private ProjectView view(RegisteredProject project) throws IOException {
        boolean rootAvailable = Files.isDirectory(project.rootPath());
        List<String> languages = List.of();
        List<String> buildSystems = List.of();
        int moduleCount = 0;
        if (rootAvailable) {
            ProjectDiscovery discovery = discoveryService.discover(project.rootPath());
            languages = discovery.languages().stream().map(Enum::name).sorted().toList();
            buildSystems = discovery.buildSystems().stream().map(Enum::name).sorted().toList();
            moduleCount = discovery.modules().size();
        }

        Optional<CodeKnowledgeSnapshot> active = snapshotStore.loadActiveKnowledge(project.id());
        String activeSnapshotId = active.map(CodeKnowledgeSnapshot::snapshotId).orElse(null);
        Optional<ProjectIndexState> persistedState = stateStore.findProjectState(project.id());
        String indexState = persistedState
                .map(state -> state.availability().name())
                .orElse(active.isPresent() ? ProjectIndexState.Availability.READY.name()
                        : ProjectIndexState.Availability.NEVER_INDEXED.name());

        Optional<IndexingRun> activeRun = activeSnapshotId == null
                ? Optional.empty()
                : stateStore.listRuns(project.id()).stream()
                    .filter(run -> run.status() == IndexingRun.Status.SUCCEEDED)
                    .filter(run -> run.activeSnapshotAfter().filter(activeSnapshotId::equals).isPresent())
                    .max(Comparator.comparing(run -> run.completedAt().orElse(run.createdAt())));

        Optional<IndexHistory> manualHistory = readHistory(project.id()).filter(candidate ->
                activeSnapshotId != null && activeSnapshotId.equals(candidate.snapshotId()));

        String lastSuccessfulIndexAt = activeRun
                .flatMap(IndexingRun::completedAt)
                .map(Instant::toString)
                .orElseGet(() -> manualHistory.map(value -> value.completedAt().toString()).orElse(null));
        String providerId = activeRun
                .map(LocalProjectOperations::providerIds)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> manualHistory.map(IndexHistory::providerId).orElse(null));
        String providerVersion = activeRun
                .map(this::providerVersions)
                .filter(value -> !value.isBlank())
                .orElseGet(() -> manualHistory.map(IndexHistory::providerVersion).orElse(null));

        return new ProjectView(
                project.id().toString(),
                project.displayName(),
                project.rootPath().toString(),
                rootAvailable,
                languages,
                buildSystems,
                moduleCount,
                indexState,
                activeSnapshotId,
                lastSuccessfulIndexAt,
                providerId,
                providerVersion
        );
    }

    private static String providerIds(IndexingRun run) {
        return run.executions().stream()
                .map(IndexingRun.IndexerExecution::indexerId)
                .distinct()
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String providerVersions(IndexingRun run) {
        return run.executions().stream()
                .map(IndexingRun.IndexerExecution::indexerId)
                .distinct()
                .sorted()
                .map(id -> Optional.ofNullable(knownProviders.get(id))
                        .map(IndexerDescriptor::version)
                        .map(version -> id + "@" + version)
                        .orElse(id + "@unknown"))
                .collect(Collectors.joining(","));
    }

    private RegisteredProject resolveProject(String identifier) throws IOException {
        requireText(identifier, "project identifier");
        UUID projectId = parseUuid(identifier);
        if (projectId != null) {
            return registry.findProject(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("unknown project: " + identifier));
        }
        List<RegisteredProject> matches = registry.listProjects().stream()
                .filter(project -> identifier.equals(project.displayName()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("unknown project: " + identifier);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("ambiguous project name, use its UUID: " + identifier);
        }
        return matches.getFirst();
    }

    private Optional<IndexHistory> readHistory(UUID projectId) throws IOException {
        Path file = historyDirectory.resolve(projectId + ".properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        }
        return Optional.of(new IndexHistory(
                required(properties, "snapshotId", file),
                required(properties, "providerId", file),
                blankToNull(properties.getProperty("providerVersion")),
                Instant.parse(required(properties, "completedAt", file))
        ));
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
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(output, "MINOS CLI index history");
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

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String required(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing CLI index property '" + key + "' in " + file);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static void requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private record IndexHistory(
            String snapshotId,
            String providerId,
            String providerVersion,
            Instant completedAt
    ) {
        private IndexHistory {
            requireText(snapshotId, "snapshotId");
            requireText(providerId, "providerId");
            Objects.requireNonNull(completedAt, "completedAt");
        }
    }
}
