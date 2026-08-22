package com.minos.orchestration;

import com.minos.discovery.ProjectDiscovery.Language;
import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;
import com.minos.orchestration.IndexingRun.IndexerExecution;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * File-backed indexing state with durable atomic replacement and project-partitioned run history.
 *
 * <p>Authoritative metadata is never replaced through a non-atomic fallback. New run files live
 * below {@code runs/<projectId>/}; a durable {@code runs/.by-id/<runId>.properties} locator makes
 * global run lookup O(1). Existing partitioned and legacy histories are migrated idempotently.</p>
 */
public final class FileIndexStateStore implements IndexStateStore {

    private static final long MAX_PROPERTIES_BYTES = 4L * 1024L * 1024L;
    private static final int MAX_PROPERTIES_ENTRIES = 40_032;
    private static final int MAX_PROPERTY_KEY_CHARS = 128;
    private static final int MAX_PROPERTY_VALUE_CHARS = 32_768;
    private static final String RUN_LOCATOR_DIRECTORY = ".by-id";
    private static final String RUN_LOCATOR_READY = "v1.ready";
    private static final String PROJECT_ID_PROPERTY = "projectId";

    private final Path storageRoot;
    private final Path projectRoot;
    private final Path runRoot;
    private final Path runLocatorRoot;
    private final ThreadLocal<Map<UUID, HeldProjectLease>> heldProjectLeases =
            ThreadLocal.withInitial(HashMap::new);

    public FileIndexStateStore(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        DurableAtomicFile.ensureDirectory(this.storageRoot, "index state storage root");
        this.projectRoot = this.storageRoot.resolve("projects");
        this.runRoot = this.storageRoot.resolve("runs");
        this.runLocatorRoot = this.runRoot.resolve(RUN_LOCATOR_DIRECTORY);
        DurableAtomicFile.ensureDirectory(projectRoot, "index project-state directory");
        DurableAtomicFile.ensureDirectory(runRoot, "index run directory");
        DurableAtomicFile.ensureDirectory(runLocatorRoot, "index run locator directory");
        migrateLegacyRuns();
        migrateRunLocators();
    }

    @Override
    public ProjectLease acquireProjectLease(UUID projectId) {
        UUID id = Objects.requireNonNull(projectId, PROJECT_ID_PROPERTY);
        Map<UUID, HeldProjectLease> heldByProject = heldProjectLeases.get();
        HeldProjectLease nested = heldByProject.get(id);
        if (nested != null) {
            nested.depth++;
            return logicalProjectLease(id, nested, heldByProject);
        }
        try {
            ProjectIndexLease physical = ProjectIndexLease.acquire(storageRoot, id);
            HeldProjectLease held = new HeldProjectLease(physical);
            heldByProject.put(id, held);
            return logicalProjectLease(id, held, heldByProject);
        } catch (IOException exception) {
            if (heldByProject.isEmpty()) heldProjectLeases.remove();
            throw new UncheckedIOException("cannot acquire project indexing lifecycle lease", exception);
        }
    }

    private ProjectLease logicalProjectLease(
            UUID projectId,
            HeldProjectLease held,
            Map<UUID, HeldProjectLease> heldByProject
    ) {
        AtomicBoolean closed = new AtomicBoolean();
        Thread owner = Thread.currentThread();
        return () -> closeLogicalProjectLease(projectId, held, heldByProject, closed, owner);
    }

    private void closeLogicalProjectLease(
            UUID projectId,
            HeldProjectLease held,
            Map<UUID, HeldProjectLease> heldByProject,
            AtomicBoolean closed,
            Thread owner
    ) {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("file project lifecycle lease must be released by its owner thread");
        }
        if (closed.get()) return;
        if (heldByProject.get(projectId) != held) {
            throw new IllegalStateException("file project lifecycle lease lost thread ownership context");
        }
        if (!closed.compareAndSet(false, true)) return;
        releaseLogicalProjectLease(projectId, held, heldByProject);
    }

    private void releaseLogicalProjectLease(
            UUID projectId,
            HeldProjectLease held,
            Map<UUID, HeldProjectLease> heldByProject
    ) {
        held.depth--;
        if (held.depth < 0) {
            throw new IllegalStateException("file project lifecycle lease depth underflow");
        }
        if (held.depth != 0) return;
        closePhysicalProjectLease(projectId, held, heldByProject);
    }

    private void closePhysicalProjectLease(
            UUID projectId,
            HeldProjectLease held,
            Map<UUID, HeldProjectLease> heldByProject
    ) {
        try {
            held.physical.close();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot release project indexing lifecycle lease", exception);
        } finally {
            heldByProject.remove(projectId, held);
            if (heldByProject.isEmpty()) heldProjectLeases.remove();
        }
    }

    @Override
    public synchronized Optional<ProjectIndexState> findProjectState(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_PROPERTY);
        Path file = projectRoot.resolve(projectId + ".properties");
        try {
            if (!regularFileExists(file, "project index state")) return Optional.empty();
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot read MINOS project state: " + file, exception);
        }
        Properties properties = load(file);
        UUID persistedProjectId = UUID.fromString(required(properties, PROJECT_ID_PROPERTY, file));
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
            Optional<UUID> locatedProject = readRunLocator(runId);
            if (locatedProject.isPresent()) {
                UUID projectId = locatedProject.orElseThrow();
                Path projectRuns = projectRunDirectory(projectId);
                if (directoryExists(projectRuns, "index run project partition")) {
                    Path candidate = runFile(projectId, runId);
                    if (regularFileExists(candidate, "indexing run")) {
                        IndexingRun run = readRun(candidate, runId);
                        requireIdentity(projectId, run.projectId(), candidate, "indexing run project");
                        return Optional.of(run);
                    }
                }
            }

            Path legacy = runRoot.resolve(runId + ".properties");
            if (!regularFileExists(legacy, "legacy indexing run")) return Optional.empty();
            IndexingRun run = readRun(legacy, runId);
            migrateLegacyRun(legacy, run);
            return Optional.of(run);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot find MINOS indexing run", exception);
        }
    }

    @Override
    public synchronized List<IndexingRun> listRuns(UUID projectId) {
        Objects.requireNonNull(projectId, PROJECT_ID_PROPERTY);
        Path projectRuns = projectRunDirectory(projectId);
        try {
            if (!directoryExists(projectRuns, "index run project partition")) return List.of();
            List<IndexingRun> runs = new ArrayList<>();
            for (Path path : propertyFiles(projectRuns, "index run project partition")) {
                runs.add(requireProjectRunIdentity(
                        projectId, projectRuns, readRun(path, idFromPropertiesFile(path))));
            }
            runs.sort(Comparator.comparing(IndexingRun::createdAt).thenComparing(IndexingRun::id));
            return List.copyOf(runs);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot list MINOS indexing runs", exception);
        }
    }

    private static IndexingRun requireProjectRunIdentity(UUID projectId, Path projectRuns, IndexingRun run) {
        requireIdentity(projectId, run.projectId(), projectRuns, "indexing run project");
        return run;
    }

    @Override
    public synchronized void saveProjectState(ProjectIndexState state) {
        Objects.requireNonNull(state, "state");
        Properties properties = new Properties();
        properties.setProperty(PROJECT_ID_PROPERTY, state.projectId().toString());
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
        try {
            ensureRunLocator(run.id(), run.projectId());
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot index MINOS run identity: " + run.id(), exception);
        }
        store(runFile(run.projectId(), run.id()), properties(run), "MINOS indexing run");
    }

    synchronized boolean deleteRun(UUID projectId, UUID runId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_PROPERTY);
        Objects.requireNonNull(runId, "runId");
        Path projectRuns = projectRunDirectory(projectId);
        boolean deleted = false;
        if (directoryExists(projectRuns, "index run project partition")) {
            deleted = DurableAtomicFile.deleteIfExists(
                    runFile(projectId, runId), "index run deletion");
        }
        deleted |= DurableAtomicFile.deleteIfExists(
                runRoot.resolve(runId + ".properties"), "legacy index run deletion");
        DurableAtomicFile.deleteIfExists(runLocatorFile(runId), "index run locator deletion");
        return deleted;
    }

    private Properties properties(IndexingRun run) {
        Properties properties = new Properties();
        properties.setProperty("id", run.id().toString());
        properties.setProperty(PROJECT_ID_PROPERTY, run.projectId().toString());
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
                UUID.fromString(required(properties, PROJECT_ID_PROPERTY, file)),
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
        for (Path legacy : propertyFiles(runRoot, "index run directory")) {
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
                continue;
            }
            migrateLegacyRun(legacy, run);
        }
    }

    private void migrateLegacyRun(Path legacy, IndexingRun run) throws IOException {
        ensureRunLocator(run.id(), run.projectId());
        Path target = runFile(run.projectId(), run.id());
        DurableAtomicFile.ensureDirectory(target.getParent(), "project run partition");
        if (regularFileExists(target, "partitioned indexing run")) {
            DurableAtomicFile.deleteIfExists(legacy, "legacy index run migration cleanup");
            return;
        }
        if (!regularFileExists(legacy, "legacy indexing run")) return;
        try {
            DurableAtomicFile.replace(legacy, target, "legacy index run migration");
        } catch (NoSuchFileException racedMigration) {
            if (!regularFileExists(target, "partitioned indexing run")) throw racedMigration;
        }
    }

    private void migrateRunLocators() throws IOException {
        Path ready = runLocatorRoot.resolve(RUN_LOCATOR_READY);
        if (regularFileExists(ready, "run locator migration marker")) return;
        try (var projects = Files.list(runRoot)) {
            for (Path projectDirectory : projects.toList()) {
                if (projectDirectory.equals(runLocatorRoot)) continue;
                UUID projectId;
                try {
                    projectId = UUID.fromString(projectDirectory.getFileName().toString());
                } catch (IllegalArgumentException notAProjectPartition) {
                    continue;
                }
                if (!directoryExists(projectDirectory, "index run project partition")) continue;
                for (Path runFile : propertyFiles(projectDirectory, "index run project partition")) {
                    try {
                        ensureRunLocator(idFromPropertiesFile(runFile), projectId);
                    } catch (IllegalStateException corruptName) {
                        // A malformed filename remains visible to project-scoped listing but does
                        // not prevent independent valid histories from receiving O(1) locators.
                    }
                }
            }
        }
        Path temporary = Files.createTempFile(runLocatorRoot, ".ready-", ".tmp");
        try {
            Files.writeString(temporary, "format=1\n");
            DurableAtomicFile.replace(temporary, ready, "run locator migration marker");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void ensureRunLocator(UUID runId, UUID projectId) throws IOException {
        Path locator = runLocatorFile(runId);
        if (regularFileExists(locator, "index run locator")) {
            UUID current = readRunLocator(runId).orElseThrow();
            if (!current.equals(projectId)) {
                throw new IOException("index run id belongs to another project partition: " + runId
                        + " existing=" + current + " requested=" + projectId);
            }
            return;
        }
        Properties properties = new Properties();
        properties.setProperty(PROJECT_ID_PROPERTY, projectId.toString());
        storeIo(locator, properties, "MINOS run locator");
    }

    private Optional<UUID> readRunLocator(UUID runId) throws IOException {
        Path locator = runLocatorFile(runId);
        if (!regularFileExists(locator, "index run locator")) return Optional.empty();
        Properties properties = load(locator);
        try {
            return Optional.of(UUID.fromString(required(properties, PROJECT_ID_PROPERTY, locator)));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("invalid project id in run locator: " + locator, invalid);
        }
    }

    private Path projectRunDirectory(UUID projectId) {
        return runRoot.resolve(projectId.toString());
    }

    private Path runFile(UUID projectId, UUID runId) {
        return projectRunDirectory(projectId).resolve(runId + ".properties");
    }

    private Path runLocatorFile(UUID runId) {
        return runLocatorRoot.resolve(runId + ".properties");
    }

    private static boolean directoryExists(Path directory, String label) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a non-symlink directory: " + directory);
        }
        return true;
    }

    private static boolean regularFileExists(Path file, String label) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " must be a regular non-symlink file: " + file);
        }
        return true;
    }

    private static List<Path> propertyFiles(Path directory, String label) throws IOException {
        if (!directoryExists(directory, label)) return List.of();
        List<Path> files = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path path : paths
                    .filter(candidate -> candidate.getFileName().toString().endsWith(".properties"))
                    .toList()) {
                regularFileExists(path, "index-state metadata");
                files.add(path);
            }
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return List.copyOf(files);
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
            storeIo(file, properties, comment);
        } catch (IOException exception) {
            throw new UncheckedIOException("cannot write MINOS index state: " + file, exception);
        }
    }

    private static void storeIo(Path file, Properties properties, String comment) throws IOException {
        DurableAtomicFile.ensureDirectory(file.getParent(), "index metadata directory");
        Path temporary = Files.createTempFile(file.getParent(), ".state-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, comment);
            }
            DurableAtomicFile.replace(temporary, file, "index metadata replacement");
        } finally {
            Files.deleteIfExists(temporary);
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

    private static final class HeldProjectLease {
        private final ProjectIndexLease physical;
        private int depth = 1;

        private HeldProjectLease(ProjectIndexLease physical) {
            this.physical = physical;
        }
    }
}
