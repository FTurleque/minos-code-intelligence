package com.minos.storage.postgresql;

import com.minos.io.CommitUncertainException;
import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappingStore;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.ProjectRegistryLimits;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

final class PostgresProjectRegistry implements ProjectRegistry {
    private static final String PROJECT_ID_LABEL = "projectId";
    private static final String WORKSPACE_WITH_PROJECTS_SELECT = """
            SELECT w.id,w.name,w.created_at,w.updated_at,p.id AS project_id
            FROM workspaces w
            LEFT JOIN projects p ON p.workspace_id=w.id
            """;

    private final PostgresConnectionFactory connections;
    private final Optional<ProjectPathMapping> mapping;
    private final ProjectPathMapping.RuntimeLocation runtimeLocation;

    PostgresProjectRegistry(PostgresConnectionFactory connections, Path home) throws IOException {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.mapping = new ProjectPathMappingStore(home).loadOptional();
        this.runtimeLocation = ProjectPathMapping.resolveRuntimeLocation(System.getenv(), System.getProperties());
    }

    @Override
    public RegisteredProject registerProject(Path rootPath, String displayName) throws IOException {
        return registerProjectWithResult(rootPath, displayName).project();
    }

    @Override
    public RegistrationResult registerProjectWithResult(Path rootPath, String displayName) throws IOException {
        Path canonical = canonicalExistingDirectory(rootPath);
        ProjectRegistryLimits.requireName(displayName, "displayName");
        RootIdentity root = rootIdentity(canonical);
        Instant now = Instant.now();
        UUID candidateId = UUID.randomUUID();
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO projects(id,root_value,root_portable,display_name,workspace_id,created_at,updated_at) "
                                + "VALUES (?,?,?,?,NULL,?,?) "
                                + "ON CONFLICT(root_value,root_portable) DO UPDATE SET root_value=projects.root_value "
                                + "RETURNING id,root_value,root_portable,display_name,workspace_id,created_at,updated_at")) {
                    statement.setObject(1, candidateId);
                    statement.setString(2, root.value());
                    statement.setBoolean(3, root.portable());
                    statement.setString(4, displayName);
                    statement.setObject(5, sqlTimestamp(now));
                    statement.setObject(6, sqlTimestamp(now));
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            throw new SQLException("project registration did not return a row");
                        }
                        RegisteredProject project = readProject(result);
                        return new RegistrationResult(project, candidateId.equals(project.id()));
                    }
                }
            });
        } catch (SQLException exception) {
            throw io("register project", exception);
        }
    }

    @Override
    public RegisteredWorkspace createWorkspace(String name) throws IOException {
        return createWorkspaceWithResult(name).workspace();
    }

    @Override
    public WorkspaceRegistrationResult createWorkspaceWithResult(String name) throws IOException {
        ProjectRegistryLimits.requireName(name, "name");
        Instant now = Instant.now();
        UUID candidateId = UUID.randomUUID();
        try {
            return connections.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO workspaces(id,name,created_at,updated_at) VALUES (?,?,?,?) "
                                + "ON CONFLICT(name) DO UPDATE SET name=workspaces.name "
                                + "RETURNING id,name,created_at,updated_at")) {
                    statement.setObject(1, candidateId);
                    statement.setString(2, name);
                    statement.setObject(3, sqlTimestamp(now));
                    statement.setObject(4, sqlTimestamp(now));
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) throw new SQLException("workspace registration did not return a row");
                        RegisteredWorkspace workspace = readWorkspace(result);
                        return new WorkspaceRegistrationResult(workspace, candidateId.equals(workspace.id()));
                    }
                }
            });
        } catch (CommitUncertainException uncertain) {
            try {
                Optional<RegisteredWorkspace> observed = findWorkspaceByExactName(name);
                if (observed.isPresent()) {
                    RegisteredWorkspace workspace = observed.orElseThrow();
                    return new WorkspaceRegistrationResult(workspace, candidateId.equals(workspace.id()));
                }
            } catch (IOException observationFailure) {
                uncertain.addSuppressed(observationFailure);
            }
            throw uncertain;
        } catch (SQLException exception) {
            throw io("create workspace", exception);
        }
    }

    @Override
    public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        Objects.requireNonNull(workspaceId, "workspaceId");
        try {
            return connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                RegisteredProject project = findProject(projectId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
                findWorkspace(workspaceId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
                if (project.workspaceId().filter(workspaceId::equals).isPresent()) return project;

                Instant updatedAt = Instant.now();
                updateProjectWorkspace(connection, projectId, workspaceId, updatedAt);
                return new RegisteredProject(
                        project.id(),
                        project.rootPath(),
                        project.displayName(),
                        Optional.of(workspaceId),
                        project.createdAt(),
                        updatedAt);
            });
        } catch (SQLException exception) {
            throw io("assign project to workspace", exception);
        }
    }

    @Override
    public RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        try {
            return connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                RegisteredProject project = findProject(projectId)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
                if (project.workspaceId().isEmpty()) return project;

                Instant updatedAt = Instant.now();
                updateProjectWorkspace(connection, projectId, null, updatedAt);
                return new RegisteredProject(
                        project.id(),
                        project.rootPath(),
                        project.displayName(),
                        Optional.empty(),
                        project.createdAt(),
                        updatedAt);
            });
        } catch (SQLException exception) {
            throw io("remove project from workspace", exception);
        }
    }

    @Override
    public Optional<RegisteredProject> findProject(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id,root_value,root_portable,display_name,workspace_id,created_at,updated_at "
                                + "FROM projects WHERE id=?")) {
                    statement.setObject(1, projectId);
                    try (ResultSet result = statement.executeQuery()) {
                        return result.next() ? Optional.of(readProject(result)) : Optional.empty();
                    }
                }
            });
        } catch (SQLException exception) {
            throw io("find project", exception);
        }
    }

    @Override
    public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        WORKSPACE_WITH_PROJECTS_SELECT + " WHERE w.id=? ORDER BY p.id")) {
                    statement.setObject(1, workspaceId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            return Optional.empty();
                        }
                        MutableWorkspace workspace = workspace(result);
                        addProjectId(workspace, result);
                        while (result.next()) {
                            addProjectId(workspace, result);
                        }
                        return Optional.of(workspace.toRegistered());
                    }
                }
            });
        } catch (SQLException exception) {
            throw io("find workspace", exception);
        }
    }

    private Optional<RegisteredWorkspace> findWorkspaceByExactName(String name) throws IOException {
        try {
            return connections.withConnection(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id,name,created_at,updated_at FROM workspaces WHERE name=?")) {
                    statement.setString(1, name);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return Optional.empty();
                        RegisteredWorkspace workspace = readWorkspace(result);
                        if (result.next()) throw new SQLException("workspace name uniqueness invariant violated: " + name);
                        return Optional.of(workspace);
                    }
                }
            });
        } catch (SQLException exception) {
            throw io("find workspace by name", exception);
        }
    }

    private static RegisteredWorkspace readWorkspace(ResultSet result) throws SQLException {
        return new RegisteredWorkspace(
                (UUID) result.getObject(1),
                result.getString(2),
                List.of(),
                result.getObject(3, OffsetDateTime.class).toInstant(),
                result.getObject(4, OffsetDateTime.class).toInstant());
    }

    @Override
    public List<RegisteredProject> listProjects() throws IOException {
        try {
            return connections.withConnection(connection -> {
                List<RegisteredProject> values = new ArrayList<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id,root_value,root_portable,display_name,workspace_id,created_at,updated_at "
                                + "FROM projects ORDER BY id");
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        values.add(readProject(result));
                    }
                }
                return List.copyOf(values);
            });
        } catch (SQLException exception) {
            throw io("list projects", exception);
        }
    }

    @Override
    public List<RegisteredWorkspace> listWorkspaces() throws IOException {
        try {
            return connections.withConnection(connection -> {
                Map<UUID, MutableWorkspace> grouped = new LinkedHashMap<>();
                try (PreparedStatement statement = connection.prepareStatement(
                        WORKSPACE_WITH_PROJECTS_SELECT + " ORDER BY w.id,p.id");
                     ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID id = (UUID) result.getObject(1);
                        MutableWorkspace workspace = grouped.get(id);
                        if (workspace == null) {
                            workspace = workspace(result);
                            grouped.put(id, workspace);
                        }
                        addProjectId(workspace, result);
                    }
                }
                return grouped.values().stream().map(MutableWorkspace::toRegistered).toList();
            });
        } catch (SQLException exception) {
            throw io("list workspaces", exception);
        }
    }

    @Override
    public boolean deleteProject(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, PROJECT_ID_LABEL);
        try {
            return connections.inTransaction(connection -> {
                PostgresProjectMutationLock.acquire(connection, projectId);
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM projects WHERE id=?")) {
                    statement.setObject(1, projectId);
                    return statement.executeUpdate() > 0;
                }
            });
        } catch (SQLException exception) {
            throw io("delete project", exception);
        }
    }

    private RootIdentity rootIdentity(Path physicalRoot) throws IOException {
        boolean portable = mapping.isPresent();
        try {
            String rootValue = portable
                    ? mapping.orElseThrow().relativeForPhysical(physicalRoot, runtimeLocation)
                    : physicalRoot.toString();
            return new RootIdentity(rootValue, portable);
        } catch (IllegalArgumentException exception) {
            throw new IOException("project path cannot be represented by configured runtime mapping", exception);
        }
    }

    private static void updateProjectWorkspace(
            java.sql.Connection connection,
            UUID projectId,
            UUID workspaceId,
            Instant updatedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE projects SET workspace_id=?,updated_at=? WHERE id=?")) {
            statement.setObject(1, workspaceId);
            statement.setObject(2, sqlTimestamp(updatedAt));
            statement.setObject(3, projectId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("project disappeared during workspace mutation: " + projectId);
            }
        }
    }

    private RegisteredProject readProject(ResultSet result) throws SQLException, IOException {
        UUID id = (UUID) result.getObject(1);
        String rootValue = result.getString(2);
        boolean portable = result.getBoolean(3);
        Path root;
        if (portable) {
            ProjectPathMapping configured = mapping.orElseThrow(
                    () -> new IOException("portable PostgreSQL registry requires runtime path mapping"));
            root = configured.resolve(rootValue, runtimeLocation);
        } else {
            root = Path.of(rootValue).toAbsolutePath().normalize();
        }
        UUID workspace = (UUID) result.getObject(5);
        return new RegisteredProject(
                id,
                root,
                result.getString(4),
                Optional.ofNullable(workspace),
                result.getObject(6, OffsetDateTime.class).toInstant(),
                result.getObject(7, OffsetDateTime.class).toInstant());
    }

    private static MutableWorkspace workspace(ResultSet result) throws SQLException {
        return new MutableWorkspace(
                (UUID) result.getObject(1),
                result.getString(2),
                result.getObject(3, OffsetDateTime.class).toInstant(),
                result.getObject(4, OffsetDateTime.class).toInstant());
    }

    private static void addProjectId(MutableWorkspace workspace, ResultSet result) throws SQLException {
        UUID projectId = (UUID) result.getObject(5);
        if (projectId != null) {
            workspace.projectIds.add(projectId);
        }
    }

    private static OffsetDateTime sqlTimestamp(Instant value) {
        return Objects.requireNonNull(value, "timestamp").atOffset(ZoneOffset.UTC);
    }

    private static Path canonicalExistingDirectory(Path rootPath) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath");
        Path canonical = rootPath.toRealPath();
        if (!Files.isDirectory(canonical)) {
            throw new IllegalArgumentException("rootPath must be an existing directory: " + rootPath);
        }
        return canonical;
    }

    private record RootIdentity(String value, boolean portable) { }

    private static final class MutableWorkspace {
        private final UUID id;
        private final String name;
        private final Instant createdAt;
        private final Instant updatedAt;
        private final List<UUID> projectIds = new ArrayList<>();

        private MutableWorkspace(UUID id, String name, Instant createdAt, Instant updatedAt) {
            this.id = id;
            this.name = name;
            this.createdAt = createdAt;
            this.updatedAt = updatedAt;
        }

        private RegisteredWorkspace toRegistered() {
            return new RegisteredWorkspace(id, name, List.copyOf(projectIds), createdAt, updatedAt);
        }
    }

    private static IOException io(String action, SQLException exception) {
        return new IOException("PostgreSQL project registry failed to " + action, exception);
    }
}
