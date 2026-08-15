package com.minos.registry;

import com.minos.io.BoundedProperties;
import com.minos.io.DurableAtomicFile;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * Registre local file-backed pour les identités projet/workspace de M1.
 *
 * <p>Depuis M29, lorsqu'un mapping runtime est configuré, les UUID restent les identités
 * autoritatives et le registre persiste uniquement une racine projet relative portable.
 * Le chemin physique est résolu à l'ouverture pour le runtime natif ou Docker courant.</p>
 */
public final class LocalProjectRegistry implements ProjectRegistry {

    private static final String PROJECTS_DIRECTORY = "projects";
    private static final String WORKSPACES_DIRECTORY = "workspaces";
    private static final String LEGACY_ROOT_PATH = "rootPath";
    private static final String PORTABLE_ROOT_PATH = "rootRelativePath";
    private static final long MAX_METADATA_BYTES = 128L * 1024L;
    private static final int MAX_METADATA_ENTRIES = 16;

    private final Path storageRoot;
    private final Path projectsDirectory;
    private final Path workspacesDirectory;
    private final Optional<ProjectPathMapping> pathMapping;
    private final ProjectPathMapping.RuntimeLocation runtimeLocation;

    public LocalProjectRegistry(Path storageRoot) throws IOException {
        this(
                storageRoot,
                loadPathMapping(storageRoot),
                ProjectPathMapping.resolveRuntimeLocation(System.getenv(), System.getProperties())
        );
    }

    public LocalProjectRegistry(
            Path storageRoot,
            ProjectPathMapping pathMapping,
            ProjectPathMapping.RuntimeLocation runtimeLocation
    ) throws IOException {
        this(storageRoot, Optional.of(Objects.requireNonNull(pathMapping, "pathMapping")), runtimeLocation);
    }

    private LocalProjectRegistry(
            Path storageRoot,
            Optional<ProjectPathMapping> pathMapping,
            ProjectPathMapping.RuntimeLocation runtimeLocation
    ) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.projectsDirectory = this.storageRoot.resolve(PROJECTS_DIRECTORY);
        this.workspacesDirectory = this.storageRoot.resolve(WORKSPACES_DIRECTORY);
        this.pathMapping = Objects.requireNonNull(pathMapping, "pathMapping");
        this.runtimeLocation = Objects.requireNonNull(runtimeLocation, "runtimeLocation");
        DurableAtomicFile.ensureDirectory(projectsDirectory, "project registry directory");
        DurableAtomicFile.ensureDirectory(workspacesDirectory, "workspace registry directory");
    }

    @Override
    public synchronized RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
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

    @Override
    public synchronized RegisteredWorkspace createWorkspace(String name) throws IOException {
        return createWorkspaceWithResult(name).workspace();
    }

    @Override
    public synchronized WorkspaceRegistrationResult createWorkspaceWithResult(String name) throws IOException {
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

    @Override
    public synchronized RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException {
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

    @Override
    public synchronized RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException {
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

    @Override
    public synchronized Optional<RegisteredProject> findProject(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Path file = projectsDirectory.resolve(projectId + ".properties");
        if (!Files.isRegularFile(file)) return Optional.empty();
        return Optional.of(readProject(file, projectId));
    }

    @Override
    public synchronized Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Path file = workspacesDirectory.resolve(workspaceId + ".properties");
        if (!Files.isRegularFile(file)) return Optional.empty();
        WorkspaceMetadata metadata = readWorkspaceMetadata(file, workspaceId);
        List<UUID> projectIds = listProjects().stream()
                .filter(project -> project.workspaceId().filter(workspaceId::equals).isPresent())
                .map(RegisteredProject::id)
                .toList();
        return Optional.of(new RegisteredWorkspace(
                metadata.id(), metadata.name(), projectIds, metadata.createdAt(), metadata.updatedAt()));
    }

    @Override
    public synchronized List<RegisteredProject> listProjects() throws IOException {
        List<RegisteredProject> projects = new ArrayList<>();
        for (Path file : propertyFiles(projectsDirectory)) {
            projects.add(readProject(file, idFromPropertiesFile(file)));
        }
        projects.sort(Comparator.comparing(project -> project.id().toString()));
        return List.copyOf(projects);
    }

    @Override
    public synchronized List<RegisteredWorkspace> listWorkspaces() throws IOException {
        List<RegisteredProject> projects = listProjects();
        List<RegisteredWorkspace> workspaces = new ArrayList<>();
        for (Path file : propertyFiles(workspacesDirectory)) {
            WorkspaceMetadata metadata = readWorkspaceMetadata(file, idFromPropertiesFile(file));
            List<UUID> projectIds = projects.stream()
                    .filter(project -> project.workspaceId().filter(metadata.id()::equals).isPresent())
                    .map(RegisteredProject::id)
                    .toList();
            workspaces.add(new RegisteredWorkspace(
                    metadata.id(), metadata.name(), projectIds, metadata.createdAt(), metadata.updatedAt()));
        }
        workspaces.sort(Comparator.comparing(workspace -> workspace.id().toString()));
        return List.copyOf(workspaces);
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
        writePropertiesAtomically(projectsDirectory.resolve(project.id() + ".properties"), properties);
    }

    private void writeWorkspace(RegisteredWorkspace workspace) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("id", workspace.id().toString());
        properties.setProperty("name", workspace.name());
        properties.setProperty("createdAt", workspace.createdAt().toString());
        properties.setProperty("updatedAt", workspace.updatedAt().toString());
        writePropertiesAtomically(workspacesDirectory.resolve(workspace.id() + ".properties"), properties);
    }

    private RegisteredProject readProject(Path file, UUID expectedId) throws IOException {
        Properties properties = readProperties(file);
        UUID id = UUID.fromString(required(properties, "id", file));
        requireIdentity(expectedId, id, file, "project");
        String relativeRoot = properties.getProperty(PORTABLE_ROOT_PATH, "").trim();
        String legacyRoot = properties.getProperty(LEGACY_ROOT_PATH, "").trim();
        if (!relativeRoot.isEmpty() && !legacyRoot.isEmpty()) {
            throw new IOException("registry project contains both legacy and portable roots: " + file);
        }

        Path rootPath;
        boolean migrateLegacy = false;
        if (!relativeRoot.isEmpty()) {
            ProjectPathMapping mapping = pathMapping.orElseThrow(() ->
                    new IOException("portable project registry requires runtime path mapping: " + file));
            try {
                rootPath = mapping.resolve(relativeRoot, runtimeLocation);
            } catch (IllegalArgumentException exception) {
                throw new IOException("invalid portable project root in " + file, exception);
            }
        } else {
            if (legacyRoot.isEmpty()) throw new IOException("missing registry project root in " + file);
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
        if (migrateLegacy) migrateLegacyProject(file, project, relativeRoot);
        return project;
    }

    private void migrateLegacyProject(Path file, RegisteredProject project, String relativeRoot) throws IOException {
        Path backup = file.resolveSibling(file.getFileName().toString() + ".m29-v1.bak");
        if (!Files.exists(backup)) Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
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
        String suffix = ".properties";
        if (!name.endsWith(suffix)) {
            throw new IOException("unexpected registry metadata filename: " + file);
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - suffix.length()));
        } catch (IllegalArgumentException exception) {
            throw new IOException("registry metadata filename is not a UUID: " + file, exception);
        }
    }

    private static void requireIdentity(UUID expected, UUID actual, Path file, String label) throws IOException {
        if (!expected.equals(actual)) {
            throw new IOException(label + " identity mismatch in " + file
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
            throw new IllegalStateException("Missing registry property '" + key + "' in " + file);
        }
        return value;
    }

    private static List<Path> propertyFiles(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static void writePropertiesAtomically(Path target, Properties properties) throws IOException {
        DurableAtomicFile.ensureDirectory(target.getParent(), "local registry metadata directory");
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                properties.store(writer, "MINOS local registry");
            }
            DurableAtomicFile.replace(temporary, target, "local registry metadata replacement");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path canonicalExistingDirectory(Path rootPath) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        Path canonical = rootPath.toRealPath();
        if (!Files.isDirectory(canonical)) {
            throw new IllegalArgumentException("rootPath must be an existing directory: " + rootPath);
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
}
