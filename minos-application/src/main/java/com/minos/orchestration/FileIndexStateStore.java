package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.BoundedProperties;
import com.minos.orchestration.IndexingRun.IndexerExecution;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Persistance locale simple des états d'indexation et des runs M14.
 *
 * <p>L'interface historique n'expose pas d'IOException ; les erreurs de stockage
 * sont donc propagées comme {@link UncheckedIOException} et ne sont jamais
 * transformées en succès.</p>
 */
public final class FileIndexStateStore implements IndexStateStore {

    private static final long MAX_PROPERTIES_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_PROPERTIES_ENTRIES = 40_032;
    private static final int MAX_PROPERTY_KEY_CHARS = 128;
    private static final int MAX_PROPERTY_VALUE_CHARS = 32_768;

    private final Path projectRoot;
    private final Path runRoot;

    public FileIndexStateStore(Path storageRoot) throws IOException {
        Path root = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.projectRoot = root.resolve("projects");
        this.runRoot = root.resolve("runs");
        Files.createDirectories(projectRoot);
        Files.createDirectories(runRoot);
        migrateLegacyRuns();
    }

    @Override
    public synchronized Optional<ProjectIndexState> findProjectState(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Path file = projectRoot.resolve(projectId + ".properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties properties = load(file);
        UUID persistedProjectId = UUID.fromString(required(properties, "projectId", file));
        requireIdentity(projectId, persistedProjectId, file, "project state");
        return Optional.of(new ProjectIndexState(
                persistedProjectId,
                ProjectIndexState.Availability.valueOf(required(properties, "availability", file)),
                optional(properties, "activeSnapshotId"),
                optional(properties, "latestRunId").map(UUID::fromString),
                Instant.parse(required(properties, "updatedAt", file)),
                optional(properties, "detail")
        ));
    }

    @Override
    public synchronized Optional<IndexingRun> findRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        Path legacy = runRoot.resolve(runId + ".properties");
        if (Files.isRegularFile(legacy)) {
            return Optional.of(readRun(legacy, runId));
        }
        try (var projects = Files.list(runRoot)) {
            for (Path directory : projects.filter(Files::isDirectory).sorted().toList()) {
                Path candidate = directory.resolve(runId + ".properties");
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(readRun(candidate, runId));
                }
            }
            return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot locate MINOS indexing run", exception);
        }
    }

    @Override
    public synchronized List<IndexingRun> listRuns(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Path directory = projectRunRoot(projectId);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(path -> readRun(path, idFromPropertiesFile(path)))
                    .peek(run -> requireIdentity(projectId, run.projectId(), directory, "indexing run project"))
                    .sorted(Comparator.comparing(IndexingRun::createdAt).thenComparing(IndexingRun::id))
                    .toList();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot list MINOS indexing runs", exception);
        }
    }

    @Override
    public synchronized void saveProjectState(ProjectIndexState state) {
        Objects.requireNonNull(state, "state");
        Properties properties = new Properties();
        properties.setProperty("projectId", state.projectId().toString());
        properties.setProperty("availability", state.availability().name());
        putOptional(properties, "activeSnapshotId", state.activeSnapshotId());
        putOptional(properties, "latestRunId", state.latestRunId().map(UUID::toString));
        properties.setProperty("updatedAt", state.updatedAt().toString());
        putOptional(properties, "detail", state.detail());
        store(projectRoot.resolve(state.projectId() + ".properties"), properties, "MINOS project index state");
    }

    @Override
    public synchronized void saveRun(IndexingRun run) {
        Objects.requireNonNull(run, "run");
        Properties properties = new Properties();
        properties.setProperty("id", run.id().toString());
        properties.setProperty("projectId", run.projectId().toString());
        properties.setProperty("status", run.status().name());
        properties.setProperty("phase", run.phase().name());
        properties.setProperty("createdAt", run.createdAt().toString());
        putOptional(properties, "completedAt", run.completedAt().map(Instant::toString));
        putOptional(properties, "stagedSnapshotId", run.stagedSnapshotId());
        putOptional(properties, "activeSnapshotBefore", run.activeSnapshotBefore());
        putOptional(properties, "activeSnapshotAfter", run.activeSnapshotAfter());
        putOptional(properties, "message", run.message());
        properties.setProperty("execution.count", Integer.toString(run.executions().size()));
        for (int index = 0; index < run.executions().size(); index++) {
            IndexerExecution execution = run.executions().get(index);
            String prefix = "execution." + index + ".";
            properties.setProperty(prefix + "language", execution.language().name());
            properties.setProperty(prefix + "indexerId", execution.indexerId());
            properties.setProperty(prefix + "artifact", execution.finalArtifact().toString());
        }
        store(projectRunRoot(run.projectId()).resolve(run.id() + ".properties"), properties, "MINOS indexing run");
    }

    private IndexingRun readRun(Path file, UUID expectedRunId) {
        Properties properties = load(file);
        UUID persistedRunId = UUID.fromString(required(properties, "id", file));
        requireIdentity(expectedRunId, persistedRunId, file, "indexing run");
        int executionCount;
        try {
            executionCount = Integer.parseInt(required(properties, "execution.count", file));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("invalid execution count in " + file, exception);
        }
        if (executionCount < 0 || executionCount > 10_000) {
            throw new IllegalStateException("unsafe execution count in " + file + ": " + executionCount);
        }
        List<IndexerExecution> executions = new ArrayList<>(executionCount);
        for (int index = 0; index < executionCount; index++) {
            String prefix = "execution." + index + ".";
            executions.add(new IndexerExecution(
                    Language.valueOf(required(properties, prefix + "language", file)),
                    required(properties, prefix + "indexerId", file),
                    Path.of(required(properties, prefix + "artifact", file))
            ));
        }
        return new IndexingRun(
                persistedRunId,
                UUID.fromString(required(properties, "projectId", file)),
                IndexingRun.Status.valueOf(required(properties, "status", file)),
                IndexingRun.Phase.valueOf(required(properties, "phase", file)),
                Instant.parse(required(properties, "createdAt", file)),
                optional(properties, "completedAt").map(Instant::parse),
                executions,
                optional(properties, "stagedSnapshotId"),
                optional(properties, "activeSnapshotBefore"),
                optional(properties, "activeSnapshotAfter"),
                optional(properties, "message")
        );
    }

    private Path projectRunRoot(UUID projectId) {
        Path directory = runRoot.resolve(Objects.requireNonNull(projectId, "projectId").toString()).normalize();
        if (!directory.startsWith(runRoot)) {
            throw new IllegalStateException("project run directory escapes run root");
        }
        return directory;
    }

    /** Moves valid pre-partition runs into their project directory once, preserving corrupt legacy evidence. */
    private void migrateLegacyRuns() throws IOException {
        try (var stream = Files.list(runRoot)) {
            for (Path file : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                try {
                    UUID runId = idFromPropertiesFile(file);
                    Properties properties = load(file);
                    requireIdentity(runId, UUID.fromString(required(properties, "id", file)), file, "indexing run");
                    UUID projectId = UUID.fromString(required(properties, "projectId", file));
                    Path destination = projectRunRoot(projectId).resolve(file.getFileName());
                    Files.createDirectories(destination.getParent());
                    if (Files.exists(destination)) {
                        Files.deleteIfExists(file);
                    } else {
                        move(file, destination);
                    }
                } catch (RuntimeException ignored) {
                    // Corrupt legacy files are left in place. They no longer affect project-scoped listing.
                }
            }
        }
    }

    private static UUID idFromPropertiesFile(Path file) {
        String name = file.getFileName().toString();
        String suffix = ".properties";
        if (!name.endsWith(suffix)) {
            throw new IllegalStateException("unexpected index-state metadata filename: " + file);
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - suffix.length()));
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("index-state metadata filename is not a UUID: " + file, exception);
        }
    }

    private static void requireIdentity(UUID expected, UUID actual, Path file, String label) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(label + " identity mismatch in " + file
                    + ": expected=" + expected + " persisted=" + actual);
        }
    }

    private static Properties load(Path file) {
        try {
            return BoundedProperties.load(
                    file,
                    MAX_PROPERTIES_BYTES,
                    MAX_PROPERTIES_ENTRIES,
                    MAX_PROPERTY_KEY_CHARS,
                    MAX_PROPERTY_VALUE_CHARS,
                    "MINOS index state metadata");
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read MINOS index state: " + file, exception);
        }
    }

    private static void store(Path file, Properties properties, String comment) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".state-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    properties.store(output, comment);
                }
                move(temporary, file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write MINOS index state: " + file, exception);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException(
                    "atomic file replacement is required for MINOS index-state durability: "
                            + source + " -> " + target,
                    exception);
        }
    }

    private static void putOptional(Properties properties, String key, Optional<String> value) {
        properties.setProperty(key, Objects.requireNonNull(value, key).orElse(""));
    }

    private static Optional<String> optional(Properties properties, String key) {
        String value = properties.getProperty(key);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String required(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("missing property '" + key + "' in " + file);
        }
        return value;
    }
}
