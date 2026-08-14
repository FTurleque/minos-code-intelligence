package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.BoundedProperties;
import com.minos.orchestration.IndexingRun.IndexerExecution;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * File-backed indexing state with durable atomic replacement and project-partitioned run history.
 *
 * <p>Authoritative metadata is never replaced through a non-atomic fallback. If the filesystem
 * cannot provide {@link StandardCopyOption#ATOMIC_MOVE}, the mutation fails closed. New run files
 * live below {@code runs/<projectId>/}; legacy flat run files are migrated on open so one corrupt
 * project's history cannot break another project's listing.</p>
 */
public final class FileIndexStateStore implements IndexStateStore {

    private static final long MAX_PROPERTIES_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_PROPERTIES_ENTRIES = 40_032;
    private static final int MAX_PROPERTY_KEY_CHARS = 128;
    private static final int MAX_PROPERTY_VALUE_CHARS = 32_768;

    private final Path storageRoot;
    private final Path projectRoot;
    private final Path runRoot;

    public FileIndexStateStore(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.projectRoot = this.storageRoot.resolve("projects");
        this.runRoot = this.storageRoot.resolve("runs");
        Files.createDirectories(projectRoot);
        Files.createDirectories(runRoot);
        migrateLegacyRuns();
    }

    @Override
    public ProjectLease acquireProjectLease(UUID projectId) {
        try {
            ProjectIndexLease lease = ProjectIndexLease.acquire(storageRoot, Objects.requireNonNull(projectId, "projectId"));
            return () -> {
                try {
                    lease.close();
                } catch (IOException exception) {
                    throw new UncheckedIOException("cannot release project indexing lifecycle lease", exception);
                }
            };
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot acquire project indexing lifecycle lease", exception);
        }
    }

    @Override
    public synchronized Optional<ProjectIndexState> findProjectState(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Path file = projectRoot.resolve(projectId + ".properties");
        if (!Files.isRegularFile(file)) return Optional.empty();
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
        try {
            Path match = null;
            try (var projects = Files.list(runRoot)) {
                for (Path projectDirectory : projects.filter(Files::isDirectory).toList()) {
                    Path candidate = projectDirectory.resolve(runId + ".properties");
                    if (!Files.isRegularFile(candidate)) continue;
                    if (match != null) {
                        throw new IllegalStateException("duplicate indexing run id across project partitions: " + runId);
                    }
                    match = candidate;
                }
            }
            if (match != null) return Optional.of(readRun(match, runId));

            Path legacy = runRoot.resolve(runId + ".properties");
            if (!Files.isRegularFile(legacy)) return Optional.empty();
            IndexingRun run = readRun(legacy, runId);
            migrateLegacyRun(legacy, run);
            return Optional.of(run);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot find MINOS indexing run", exception);
        }
    }

    @Override
    public synchronized List<IndexingRun> listRuns(UUID projectId) {
        Objects.requireNonNull(projectId, "projectId");
        Path projectRuns = projectRunDirectory(projectId);
        if (!Files.isDirectory(projectRuns)) return List.of();
        try (var stream = Files.list(projectRuns)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .map(path -> readRun(path, idFromPropertiesFile(path)))
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
        Properties properties = properties(run);
        store(runFile(run.projectId(), run.id()), properties, "MINOS indexing run");
    }

    synchronized boolean deleteRun(UUID projectId, UUID runId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(runId, "runId");
        Path file = runFile(projectId, runId);
        boolean deleted = Files.deleteIfExists(file);
        Path legacy = runRoot.resolve(runId + ".properties");
        deleted |= Files.deleteIfExists(legacy);
        if (deleted) {
            if (Files.isDirectory(file.getParent())) forceDirectory(file.getParent());
            forceDirectory(runRoot);
        }
        return deleted;
    }

    private Properties properties(IndexingRun run) {
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
        return properties;
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

    private void migrateLegacyRuns() throws IOException {
        try (var stream = Files.list(runRoot)) {
            for (Path legacy : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                final UUID runId;
                try {
                    runId = idFromPropertiesFile(legacy);
                } catch (IllegalStateException ignored) {
                    continue;
                }
                final IndexingRun run;
                try {
                    run = readRun(legacy, runId);
                } catch (RuntimeException corruptLegacyMetadata) {
                    // Legacy corruption remains addressable through findRun(runId), but it must not
                    // poison listing for unrelated projects after the partitioned layout is active.
                    continue;
                }
                migrateLegacyRun(legacy, run);
            }
        }
    }

    private void migrateLegacyRun(Path legacy, IndexingRun run) throws IOException {
        Path target = runFile(run.projectId(), run.id());
        ensureDirectory(target.getParent());
        if (Files.isRegularFile(target)) {
            Files.deleteIfExists(legacy);
            forceDirectory(runRoot);
            return;
        }
        forceFile(legacy);
        move(legacy, target);
    }

    private Path projectRunDirectory(UUID projectId) {
        return runRoot.resolve(projectId.toString());
    }

    private Path runFile(UUID projectId, UUID runId) {
        return projectRunDirectory(projectId).resolve(runId + ".properties");
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
            ensureDirectory(file.getParent());
            Path temporary = Files.createTempFile(file.getParent(), ".state-", ".tmp");
            try {
                try (OutputStream output = Files.newOutputStream(temporary)) {
                    properties.store(output, comment);
                }
                forceFile(temporary);
                move(temporary, file);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write MINOS index state: " + file, exception);
        }
    }

    private static void ensureDirectory(Path directory) throws IOException {
        boolean existed = Files.isDirectory(directory);
        Files.createDirectories(directory);
        if (!existed && directory.getParent() != null && Files.isDirectory(directory.getParent())) {
            forceDirectory(directory.getParent());
        }
    }

    private static void forceFile(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            throw new IOException("filesystem does not support required atomic metadata replacement: " + target, exception);
        }
        forceDirectory(target.getParent());
    }

    private static void forceDirectory(Path directory) throws IOException {
        if (directory == null || windows()) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (UnsupportedOperationException exception) {
            throw new IOException("filesystem does not support required directory durability sync: " + directory, exception);
        }
    }

    private static boolean windows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
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
