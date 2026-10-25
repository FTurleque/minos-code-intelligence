package com.minos.storage.local.registry;

import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import com.minos.io.PrivateLocalStorage;
import com.minos.io.BoundedProperties;
import com.minos.io.ConfinedFileOpener;
import com.minos.io.DurableAtomicFile;
import com.minos.registry.DegradedEntry;
import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.ProjectRegistryLimits;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import static com.minos.diagnostics.PublicErrorMessages.leafName;

/**
 * Local file-backed registry for project/workspace identities.
 *
 * <p>When a runtime mapping is configured, the UUIDs remain the authoritative identities
 * and the registry persists only a portable relative project root. The physical path is
 * resolved when opening, for the current native or Docker runtime.</p>
 */
public final class LocalProjectRegistry implements ProjectRegistry {

    private static final String PROJECTS_DIRECTORY = "projects";
    private static final String WORKSPACES_DIRECTORY = "workspaces";
    private static final String PROPERTIES_SUFFIX = ".properties";
    private static final String LEGACY_ROOT_PATH = "rootPath";
    private static final String PORTABLE_ROOT_PATH = "rootRelativePath";
    private static final long MAX_METADATA_BYTES = 128L * 1024L;
    private static final int MAX_METADATA_ENTRIES = 16;

    /** A registry entry is a few lines of properties: a legacy one this large is not one. */
    private static final int MAX_LEGACY_ENTRY_BYTES = 1024 * 1024;

    private final Path storageRoot;
    private final Path projectsDirectory;
    private final Path workspacesDirectory;
    private final Optional<ProjectPathMapping> pathMapping;
    private final ProjectPathMapping.RuntimeLocation runtimeLocation;
    /** False for an instance that only reads: a legacy entry is then resolved in memory, never rewritten. */
    private final boolean migrateLegacyEntriesOnRead;
    /**
     * Serialises the operations of this instance. Private, so that code holding a reference to the registry cannot
     * take it and stall every other caller (SpotBugs USO_UNSAFE_METHOD_SYNCHRONIZATION).
     */
    private final Object lock = new Object();

    public LocalProjectRegistry(Path storageRoot) throws IOException {
        this(
                storageRoot,
                loadPathMapping(storageRoot),
                ProjectPathMapping.resolveRuntimeLocation(System.getenv(), System.getProperties()),
                true
        );
    }

    /**
     * A registry for <strong>reads that write nothing</strong>: a legacy entry is resolved in memory and
     * returned as usual, and is left on disk for an instance that migrates (one used under the exclusive
     * registry lease) to rewrite. For a caller that only holds a reader's lease, or whose storage cannot be
     * written. Its mutating methods behave as usual and are not meant to be called.
     */
    static LocalProjectRegistry readingWithoutMigration(Path storageRoot) throws IOException {
        return new LocalProjectRegistry(
                storageRoot,
                loadPathMapping(storageRoot),
                ProjectPathMapping.resolveRuntimeLocation(System.getenv(), System.getProperties()),
                false
        );
    }

    public LocalProjectRegistry(
            Path storageRoot,
            ProjectPathMapping pathMapping,
            ProjectPathMapping.RuntimeLocation runtimeLocation
    ) throws IOException {
        this(storageRoot, Optional.of(Objects.requireNonNull(pathMapping, "pathMapping")), runtimeLocation, true);
    }

    private LocalProjectRegistry(
            Path storageRoot,
            Optional<ProjectPathMapping> pathMapping,
            ProjectPathMapping.RuntimeLocation runtimeLocation,
            boolean migrateLegacyEntriesOnRead
    ) throws IOException {
        this.migrateLegacyEntriesOnRead = migrateLegacyEntriesOnRead;
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        DurableAtomicFile.ensureDirectory(this.storageRoot, "local project registry root");
        this.projectsDirectory = this.storageRoot.resolve(PROJECTS_DIRECTORY);
        this.workspacesDirectory = this.storageRoot.resolve(WORKSPACES_DIRECTORY);
        this.pathMapping = Objects.requireNonNull(pathMapping, "pathMapping");
        this.runtimeLocation = Objects.requireNonNull(runtimeLocation, "runtimeLocation");
        DurableAtomicFile.ensureDirectory(projectsDirectory, "project registry directory");
        DurableAtomicFile.ensureDirectory(workspacesDirectory, "workspace registry directory");
    }

    @Override
    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
        synchronized (lock) {
            Path canonicalRoot = canonicalExistingDirectory(rootPath);
            validateName(displayName, "displayName");

            Optional<RegisteredProject> existing = listProjects().stream()
                    .filter(project -> project.rootPath().equals(canonicalRoot))
                    .findFirst();
            if (existing.isPresent()) return existing.get();

            Instant now = Instant.now();
            RegisteredProject project = new RegisteredProject(
                    UUID.randomUUID(), canonicalRoot, displayName, Optional.empty(), now, now);
            writeProject(project);
            return project;
        }
    }

    @Override
    public RegisteredWorkspace createWorkspace(String name) throws IOException {
        synchronized (lock) {
            return createWorkspaceWithResult(name).workspace();
        }
    }

    @Override
    public WorkspaceRegistrationResult createWorkspaceWithResult(String name) throws IOException {
        synchronized (lock) {
            ProjectRegistryLimits.requireName(name, "name");
            List<RegisteredWorkspace> matches = listWorkspaces().stream()
                    .filter(workspace -> workspace.name().equals(name))
                    .toList();
            if (matches.size() > 1) {
                throw new IOException("duplicate workspace names in local registry: " + name);
            }
            if (!matches.isEmpty()) {
                return new WorkspaceRegistrationResult(matches.getFirst(), false);
            }
            Instant now = Instant.now();
            RegisteredWorkspace workspace = new RegisteredWorkspace(
                    UUID.randomUUID(), name, List.of(), now, now);
            writeWorkspace(workspace);
            return new WorkspaceRegistrationResult(workspace, true);
        }
    }

    @Override
    public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException {
        synchronized (lock) {
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(workspaceId, "workspaceId");
            RegisteredProject project = findProject(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
            findWorkspace(workspaceId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
            if (project.workspaceId().filter(workspaceId::equals).isPresent()) return project;
            RegisteredProject updated = new RegisteredProject(
                    project.id(), project.rootPath(), project.displayName(), Optional.of(workspaceId),
                    project.createdAt(), Instant.now());
            writeProject(updated);
            return updated;
        }
    }

    @Override
    public RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException {
        synchronized (lock) {
            Objects.requireNonNull(projectId, "projectId");
            RegisteredProject project = findProject(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
            if (project.workspaceId().isEmpty()) return project;
            RegisteredProject updated = new RegisteredProject(
                    project.id(), project.rootPath(), project.displayName(), Optional.empty(),
                    project.createdAt(), Instant.now());
            writeProject(updated);
            return updated;
        }
    }

    @Override
    public Optional<RegisteredProject> findProject(UUID projectId) throws IOException {
        synchronized (lock) {
            Objects.requireNonNull(projectId, "projectId");
            Path file = projectsDirectory.resolve(projectId + PROPERTIES_SUFFIX);
            if (!Files.isRegularFile(file)) return Optional.empty();
            return Optional.of(readProject(file, projectId));
        }
    }

    @Override
    public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        synchronized (lock) {
            Objects.requireNonNull(workspaceId, "workspaceId");
            Path file = workspacesDirectory.resolve(workspaceId + PROPERTIES_SUFFIX);
            if (!Files.isRegularFile(file)) return Optional.empty();
            WorkspaceMetadata metadata = readWorkspaceMetadata(file, workspaceId);
            List<UUID> projectIds = listProjects().stream()
                    .filter(project -> project.workspaceId().filter(workspaceId::equals).isPresent())
                    .map(RegisteredProject::id)
                    .toList();
            return Optional.of(new RegisteredWorkspace(
                    metadata.id(), metadata.name(), projectIds, metadata.createdAt(), metadata.updatedAt()));
        }
    }

    @Override
    public List<RegisteredProject> listProjects() throws IOException {
        synchronized (lock) {
            Scan scan = scanProjects();
            scan.rethrowFirstReadFailure();
            return scan.projects();
        }
    }

    @Override
    public Inventory inventory() throws IOException {
        synchronized (lock) {
            Scan scan = scanProjects();
            return new Inventory(scan.projects(), scan.degraded());
        }
    }

    @Override
    public List<RegisteredWorkspace> listWorkspaces() throws IOException {
        synchronized (lock) {
            List<RegisteredProject> projects = listProjects();
            List<RegisteredWorkspace> workspaces = new ArrayList<>();
            for (Path file : propertyFiles(workspacesDirectory)) {
                workspaces.add(workspaceOf(readWorkspaceMetadata(file, idFromPropertiesFile(file)), projects));
            }
            workspaces.sort(Comparator.comparing(workspace -> workspace.id().toString()));
            return List.copyOf(workspaces);
        }
    }

    /**
     * The workspaces as an inventory (Q26): a damaged project file bounds its damage to its entry (the membership
     * of the others stays established, the entry is counted), a damaged workspace file is a workspace missing from
     * the list and counted. {@link #listWorkspaces()} fails on the first damaged entry as before; a directory that
     * cannot be listed fails here too.
     */
    @Override
    public WorkspaceInventory workspaceInventory() throws IOException {
        synchronized (lock) {
            Scan projects = scanProjects();
            List<RegisteredWorkspace> workspaces = new ArrayList<>();
            List<EntryFailure> failures = new ArrayList<>();
            for (Path file : propertyEntries(workspacesDirectory)) {
                if (!Files.isRegularFile(file)) {
                    failures.add(new EntryFailure(file, null));
                    continue;
                }
                try {
                    workspaces.add(workspaceOf(readWorkspaceMetadata(file, idFromPropertiesFile(file)), projects.projects()));
                } catch (IOException | RuntimeException failure) {
                    // An interruption is not a damaged entry (see scanProjects).
                    if (Thread.currentThread().isInterrupted()) throw failure;
                    failures.add(new EntryFailure(file, failure));
                }
            }
            workspaces.sort(Comparator.comparing(workspace -> workspace.id().toString()));
            return new WorkspaceInventory(workspaces, failures.stream().map(Scan::describe).toList(), projects.degraded());
        }
    }

    private static RegisteredWorkspace workspaceOf(WorkspaceMetadata metadata, List<RegisteredProject> projects) {
        List<UUID> projectIds = projects.stream()
                .filter(project -> project.workspaceId().filter(metadata.id()::equals).isPresent())
                .map(RegisteredProject::id)
                .toList();
        return new RegisteredWorkspace(
                metadata.id(), metadata.name(), projectIds, metadata.createdAt(), metadata.updatedAt());
    }

    public Path storageRoot() {
        return storageRoot;
    }

    public Optional<ProjectPathMapping> pathMapping() {
        return pathMapping;
    }

    public ProjectPathMapping.RuntimeLocation runtimeLocation() {
        return runtimeLocation;
    }

    private void writeProject(RegisteredProject project) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("id", project.id().toString());
        if (pathMapping.isPresent()) {
            try {
                properties.setProperty(
                        PORTABLE_ROOT_PATH,
                        pathMapping.orElseThrow().relativeForPhysical(project.rootPath(), runtimeLocation));
            } catch (IllegalArgumentException exception) {
                throw new IOException("project path cannot be represented by configured runtime mapping: "
                        + project.rootPath(), exception);
            }
        } else {
            properties.setProperty(LEGACY_ROOT_PATH, project.rootPath().toString());
        }
        properties.setProperty("displayName", project.displayName());
        properties.setProperty("workspaceId", project.workspaceId().map(UUID::toString).orElse(""));
        properties.setProperty("createdAt", project.createdAt().toString());
        properties.setProperty("updatedAt", project.updatedAt().toString());
        writePropertiesAtomically(projectsDirectory.resolve(project.id() + PROPERTIES_SUFFIX), properties);
    }

    private void writeWorkspace(RegisteredWorkspace workspace) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("id", workspace.id().toString());
        properties.setProperty("name", workspace.name());
        properties.setProperty("createdAt", workspace.createdAt().toString());
        properties.setProperty("updatedAt", workspace.updatedAt().toString());
        writePropertiesAtomically(workspacesDirectory.resolve(workspace.id() + PROPERTIES_SUFFIX), properties);
    }

    private RegisteredProject readProject(Path file, UUID expectedId) throws IOException {
        Properties properties = readProperties(file);
        UUID id = UUID.fromString(required(properties, "id", file));
        requireIdentity(expectedId, id, file, "project");
        String relativeRoot = properties.getProperty(PORTABLE_ROOT_PATH, "").trim();
        String legacyRoot = properties.getProperty(LEGACY_ROOT_PATH, "").trim();
        if (!relativeRoot.isEmpty() && !legacyRoot.isEmpty()) {
            throw new IOException("registry project contains both legacy and portable roots: " + leafName(file));
        }

        Path rootPath;
        boolean migrateLegacy = false;
        if (!relativeRoot.isEmpty()) {
            ProjectPathMapping mapping = pathMapping.orElseThrow(() ->
                    new IOException("portable project registry requires runtime path mapping: " + leafName(file)));
            try {
                rootPath = mapping.resolve(relativeRoot, runtimeLocation);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid portable project root in " + leafName(file), exception);
            }
        } else {
            if (legacyRoot.isEmpty()) throw new IOException("missing registry project root in " + leafName(file));
            if (pathMapping.isPresent()) {
                try {
                    String migratedRelative = pathMapping.orElseThrow().relativeForLegacyPath(legacyRoot);
                    rootPath = pathMapping.orElseThrow().resolve(migratedRelative, runtimeLocation);
                    relativeRoot = migratedRelative;
                    migrateLegacy = true;
                } catch (IllegalArgumentException exception) {
                    throw new IOException("legacy project root cannot be migrated with configured mapping: "
                            + legacyRoot, exception);
                }
            } else {
                rootPath = Path.of(legacyRoot).toAbsolutePath().normalize();
            }
        }

        String displayName = required(properties, "displayName", file);
        String rawWorkspaceId = properties.getProperty("workspaceId", "").trim();
        Optional<UUID> workspaceId = rawWorkspaceId.isEmpty()
                ? Optional.empty() : Optional.of(UUID.fromString(rawWorkspaceId));
        Instant createdAt = Instant.parse(required(properties, "createdAt", file));
        Instant updatedAt = Instant.parse(required(properties, "updatedAt", file));
        RegisteredProject project = new RegisteredProject(
                id, rootPath, displayName, workspaceId, createdAt, updatedAt);
        if (migrateLegacy && migrateLegacyEntriesOnRead) migrateLegacyProject(file, project, relativeRoot);
        return project;
    }

    /**
     * Saves the legacy entry beside itself, once: read without following a link, written to a private temporary of the
     * same folder and published exclusively, so the backup is complete or absent, private, and a copy that exists is
     * never replaced. A lost race (another process saved it first) is the same outcome as finding it saved. The
     * modification date of the entry is not copied: nothing reads it.
     */
    private static void backupOnce(Path file, Path backup) throws IOException {
        backupOnce(file, backup, MAX_LEGACY_ENTRY_BYTES);
    }

    /** The limit is a parameter for the tests: the registry reader refuses a larger entry before it gets here. */
    static void backupOnce(Path file, Path backup, int maxBytes) throws IOException {
        if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) return;
        byte[] content;
        try (InputStream input = ConfinedFileOpener.openRegularFileNoFollow(file)) {
            content = input.readNBytes(maxBytes + 1);
        }
        if (content.length > maxBytes) {
            throw new IOException("legacy registry entry exceeds the backup byte limit");
        }
        Path backupFolder = Objects.requireNonNull(backup.getParent(), "backup folder");
        String backupName = Objects.requireNonNull(backup.getFileName(), "backup name").toString();
        Path temporary = PrivateLocalStorage.createPrivateTempFile(backupFolder, backupName + ".", ".tmp");
        try {
            PrivateLocalStorage.writePrivateFile(temporary, content);
            DurableAtomicFile.publish(temporary, backup, "legacy registry entry backup");
        } catch (FileAlreadyExistsException alreadySaved) {
            // Another process saved it first: the backup exists, which is all that is asked.
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void migrateLegacyProject(Path file, RegisteredProject project, String relativeRoot) throws IOException {
        Path backup = file.resolveSibling(file.getFileName().toString() + ".m29-v1.bak");
        backupOnce(file, backup);
        Properties properties = new Properties();
        properties.setProperty("id", project.id().toString());
        properties.setProperty(PORTABLE_ROOT_PATH, relativeRoot);
        properties.setProperty("displayName", project.displayName());
        properties.setProperty("workspaceId", project.workspaceId().map(UUID::toString).orElse(""));
        properties.setProperty("createdAt", project.createdAt().toString());
        properties.setProperty("updatedAt", project.updatedAt().toString());
        writePropertiesAtomically(file, properties);
    }

    private static WorkspaceMetadata readWorkspaceMetadata(Path file, UUID expectedId) throws IOException {
        Properties properties = readProperties(file);
        UUID id = UUID.fromString(required(properties, "id", file));
        requireIdentity(expectedId, id, file, "workspace");
        return new WorkspaceMetadata(
                id,
                required(properties, "name", file),
                Instant.parse(required(properties, "createdAt", file)),
                Instant.parse(required(properties, "updatedAt", file)));
    }

    private static UUID idFromPropertiesFile(Path file) throws IOException {
        String name = file.getFileName().toString();
        String suffix = PROPERTIES_SUFFIX;
        if (!name.endsWith(suffix)) {
            throw new IOException("unexpected registry metadata filename: " + leafName(file));
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - suffix.length()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("registry metadata filename is not a UUID: " + leafName(file), exception);
        }
    }

    private static void requireIdentity(UUID expected, UUID actual, Path file, String label) throws IOException {
        if (!expected.equals(actual)) {
            throw new IOException(label + " identity mismatch in " + leafName(file)
                    + ": expected=" + expected + " persisted=" + actual);
        }
    }

    private static Properties readProperties(Path file) throws IOException {
        return BoundedProperties.load(
                file,
                MAX_METADATA_BYTES,
                MAX_METADATA_ENTRIES,
                128,
                16 * 1024,
                "local registry metadata"
        );
    }

    private static String required(Properties properties, String key, Path file) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing registry property '" + key + "' in " + leafName(file));
        }
        return value;
    }

    /** Every entry named {@code *.properties}, whatever it is: what is not a regular file is reported, not skipped. */
    private static List<Path> propertyEntries(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(PROPERTIES_SUFFIX))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static List<Path> propertyFiles(Path directory) throws IOException {
        return propertyEntries(directory).stream().filter(Files::isRegularFile).toList();
    }

    /**
     * The only place that reads every project entry and bounds the damage to the entry that carries it (Q8):
     * {@link #listProjects()} fails on the first damaged entry as before, {@link #inventory()} reports it.
     */
    private Scan scanProjects() throws IOException {
        List<RegisteredProject> projects = new ArrayList<>();
        List<EntryFailure> failures = new ArrayList<>();
        for (Path file : propertyEntries(projectsDirectory)) {
            if (!Files.isRegularFile(file)) {
                failures.add(new EntryFailure(file, null));
                continue;
            }
            try {
                projects.add(readProject(file, idFromPropertiesFile(file)));
            } catch (IOException | RuntimeException failure) {
                // An interruption is not a damaged entry: reading every entry with an interrupted thread would
                // degrade them all, silently.
                if (Thread.currentThread().isInterrupted()) throw failure;
                failures.add(new EntryFailure(file, failure));
            }
        }
        projects.sort(Comparator.comparing(project -> project.id().toString()));
        return new Scan(List.copyOf(projects), List.copyOf(failures));
    }

    private static void writePropertiesAtomically(Path target, Properties properties) throws IOException {
        DurableAtomicFile.ensureDirectory(target.getParent(), "local registry metadata directory");
        Path temporary = PrivateLocalStorage.createPrivateTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            try (Writer writer = new OutputStreamWriter(content, StandardCharsets.UTF_8)) {
                properties.store(writer, "MINOS local registry");
            }
            PrivateLocalStorage.writePrivateFile(temporary, content.toByteArray());
            DurableAtomicFile.replace(temporary, target, "local registry metadata replacement");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path canonicalExistingDirectory(Path rootPath) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        Path canonical = rootPath.toRealPath();
        if (!Files.isDirectory(canonical)) {
            throw new IllegalArgumentException("rootPath must be an existing directory: " + leafName(rootPath));
        }
        return canonical;
    }

    private static Optional<ProjectPathMapping> loadPathMapping(Path storageRoot) throws IOException {
        Path absoluteStorage = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        Path home = absoluteStorage.getParent();
        return home == null ? Optional.empty() : new ProjectPathMappingStore(home).loadOptional();
    }

    private static void validateName(String value, String label) {
        ProjectRegistryLimits.requireName(value, label);
    }

    private record WorkspaceMetadata(UUID id, String name, Instant createdAt, Instant updatedAt) {
    }

    /** {@code cause == null}: the entry has the name of a registry entry without being a regular file. */
    private record EntryFailure(Path file, Exception cause) {
    }

    private record Scan(List<RegisteredProject> projects, List<EntryFailure> failures) {

        private List<DegradedEntry> degraded() {
            return failures.stream().map(Scan::describe).toList();
        }

        /** Strict listing: what failed while reading an entry is rethrown as it always was. */
        private void rethrowFirstReadFailure() throws IOException {
            for (EntryFailure failure : failures) {
                if (failure.cause() instanceof IOException io) throw io;
                if (failure.cause() instanceof RuntimeException runtime) throw runtime;
            }
        }

        private static String entryName(Path file) {
            String name = file.getFileName().toString();
            return name.substring(0, name.length() - PROPERTIES_SUFFIX.length());
        }

        private static DegradedEntry describe(EntryFailure failure) {
            String entry = entryName(failure.file());
            return failure.cause() == null
                    ? DegradedEntry.of(entry, "registry entry is not a regular file")
                    : DegradedEntry.of(entry, "registry entry is unreadable", failure.cause());
        }
    }
}
