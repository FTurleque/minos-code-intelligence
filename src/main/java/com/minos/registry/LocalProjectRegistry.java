package com.minos.registry;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
 * <p>Les UUID sont attribués aléatoirement puis persistés. Le chemin d'un projet
 * sert uniquement à retrouver un enregistrement existant et n'est jamais
 * transformé en identifiant métier.</p>
 */
public final class LocalProjectRegistry {

    private static final String PROJECTS_DIRECTORY = "projects";
    private static final String WORKSPACES_DIRECTORY = "workspaces";

    private final Path storageRoot;
    private final Path projectsDirectory;
    private final Path workspacesDirectory;

    public LocalProjectRegistry(Path storageRoot) throws IOException {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot")
                .toAbsolutePath()
                .normalize();
        this.projectsDirectory = this.storageRoot.resolve(PROJECTS_DIRECTORY);
        this.workspacesDirectory = this.storageRoot.resolve(WORKSPACES_DIRECTORY);
        Files.createDirectories(projectsDirectory);
        Files.createDirectories(workspacesDirectory);
    }

    public synchronized RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
        Path canonicalRoot = canonicalExistingDirectory(rootPath);
        validateName(displayName, "displayName");

        Optional<RegisteredProject> existing = listProjects().stream()
                .filter(project -> project.rootPath().equals(canonicalRoot))
                .findFirst();
        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = Instant.now();
        RegisteredProject project = new RegisteredProject(
                UUID.randomUUID(),
                canonicalRoot,
                displayName,
                Optional.empty(),
                now,
                now
        );
        writeProject(project);
        return project;
    }

    public synchronized RegisteredWorkspace createWorkspace(String name) throws IOException {
        validateName(name, "name");
        Instant now = Instant.now();
        RegisteredWorkspace workspace = new RegisteredWorkspace(
                UUID.randomUUID(),
                name,
                List.of(),
                now,
                now
        );
        writeWorkspace(workspace);
        return workspace;
    }

    public synchronized RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(workspaceId, "workspaceId");

        RegisteredProject project = findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
        findWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));

        if (project.workspaceId().filter(workspaceId::equals).isPresent()) {
            return project;
        }

        RegisteredProject updated = new RegisteredProject(
                project.id(),
                project.rootPath(),
                project.displayName(),
                Optional.of(workspaceId),
                project.createdAt(),
                Instant.now()
        );
        writeProject(updated);
        return updated;
    }

    public synchronized RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        RegisteredProject project = findProject(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));

        if (project.workspaceId().isEmpty()) {
            return project;
        }

        RegisteredProject updated = new RegisteredProject(
                project.id(),
                project.rootPath(),
                project.displayName(),
                Optional.empty(),
                project.createdAt(),
                Instant.now()
        );
        writeProject(updated);
        return updated;
    }

    public synchronized Optional<RegisteredProject> findProject(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Path file = projectsDirectory.resolve(projectId + ".properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.of(readProject(file));
    }

    public synchronized Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Path file = workspacesDirectory.resolve(workspaceId + ".properties");
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        WorkspaceMetadata metadata = readWorkspaceMetadata(file);
        List<UUID> projectIds = listProjects().stream()
                .filter(project -> project.workspaceId().filter(workspaceId::equals).isPresent())
                .map(RegisteredProject::id)
                .toList();
        return Optional.of(new RegisteredWorkspace(
                metadata.id(),
                metadata.name(),
                projectIds,
                metadata.createdAt(),
                metadata.updatedAt()
        ));
    }

    public synchronized List<RegisteredProject> listProjects() throws IOException {
        List<RegisteredProject> projects = new ArrayList<>();
        for (Path file : propertyFiles(projectsDirectory)) {
            projects.add(readProject(file));
        }
        projects.sort(Comparator.comparing(project -> project.id().toString()));
        return List.copyOf(projects);
    }

    public synchronized List<RegisteredWorkspace> listWorkspaces() throws IOException {
        List<RegisteredProject> projects = listProjects();
        List<RegisteredWorkspace> workspaces = new ArrayList<>();
        for (Path file : propertyFiles(workspacesDirectory)) {
            WorkspaceMetadata metadata = readWorkspaceMetadata(file);
            List<UUID> projectIds = projects.stream()
                    .filter(project -> project.workspaceId().filter(metadata.id()::equals).isPresent())
                    .map(RegisteredProject::id)
                    .toList();
            workspaces.add(new RegisteredWorkspace(
                    metadata.id(),
                    metadata.name(),
                    projectIds,
                    metadata.createdAt(),
                    metadata.updatedAt()
            ));
        }
        workspaces.sort(Comparator.comparing(workspace -> workspace.id().toString()));
        return List.copyOf(workspaces);
    }

    public Path storageRoot() {
        return storageRoot;
    }

    private void writeProject(RegisteredProject project) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("id", project.id().toString());
        properties.setProperty("rootPath", project.rootPath().toString());
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

    private static RegisteredProject readProject(Path file) throws IOException {
        Properties properties = readProperties(file);
        UUID id = UUID.fromString(required(properties, "id", file));
        Path rootPath = Path.of(required(properties, "rootPath", file)).toAbsolutePath().normalize();
        String displayName = required(properties, "displayName", file);
        String rawWorkspaceId = properties.getProperty("workspaceId", "").trim();
        Optional<UUID> workspaceId = rawWorkspaceId.isEmpty()
                ? Optional.empty()
                : Optional.of(UUID.fromString(rawWorkspaceId));
        Instant createdAt = Instant.parse(required(properties, "createdAt", file));
        Instant updatedAt = Instant.parse(required(properties, "updatedAt", file));
        return new RegisteredProject(id, rootPath, displayName, workspaceId, createdAt, updatedAt);
    }

    private static WorkspaceMetadata readWorkspaceMetadata(Path file) throws IOException {
        Properties properties = readProperties(file);
        return new WorkspaceMetadata(
                UUID.fromString(required(properties, "id", file)),
                required(properties, "name", file),
                Instant.parse(required(properties, "createdAt", file)),
                Instant.parse(required(properties, "updatedAt", file))
        );
    }

    private static Properties readProperties(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
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
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".properties"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
    }

    private static void writePropertiesAtomically(Path target, Properties properties) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString() + ".", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.TRUNCATE_EXISTING
            )) {
                properties.store(writer, "MINOS local registry");
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
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

    private static void validateName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private record WorkspaceMetadata(UUID id, String name, Instant createdAt, Instant updatedAt) {
    }
}
