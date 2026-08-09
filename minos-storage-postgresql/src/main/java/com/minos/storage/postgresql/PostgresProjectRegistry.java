package com.minos.storage.postgresql;

import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappingStore;
import com.minos.registry.ProjectRegistry;
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
        Path canonical = canonicalExistingDirectory(rootPath);
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName must not be blank");
        RootIdentity root = rootIdentity(canonical);
        Instant now = Instant.now();
        UUID candidateId = UUID.randomUUID();
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO projects(id,root_value,root_portable,display_name,workspace_id,created_at,updated_at) "
                                + "VALUES (?,?,?,?,NULL,?,?) "
                                + "ON CONFLICT(root_value,root_portable) DO UPDATE SET root_value=projects.root_value "
                                + "RETURNING id,root_value,root_portable,display_name,workspace_id,created_at,updated_at")) {
                    s.setObject(1, candidateId);
                    s.setString(2, root.value());
                    s.setBoolean(3, root.portable());
                    s.setString(4, displayName);
                    s.setObject(5, sqlTimestamp(now));
                    s.setObject(6, sqlTimestamp(now));
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) throw new SQLException("project registration did not return a row");
                        return readProject(r);
                    }
                }
            });
        } catch (SQLException e) {
            throw io("register project", e);
        }
    }

    @Override
    public RegisteredWorkspace createWorkspace(String name) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name must not be blank");
        Instant now = Instant.now();
        RegisteredWorkspace workspace = new RegisteredWorkspace(UUID.randomUUID(), name, List.of(), now, now);
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO workspaces(id,name,created_at,updated_at) VALUES (?,?,?,?)")) {
                    s.setObject(1, workspace.id());
                    s.setString(2, workspace.name());
                    s.setObject(3, sqlTimestamp(workspace.createdAt()));
                    s.setObject(4, sqlTimestamp(workspace.updatedAt()));
                    s.executeUpdate();
                    return workspace;
                }
            });
        } catch (SQLException e) { throw io("create workspace", e); }
    }

    @Override
    public RegisteredProject assignProjectToWorkspace(UUID projectId, UUID workspaceId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(workspaceId, "workspaceId");
        RegisteredProject project = findProject(projectId).orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
        findWorkspace(workspaceId).orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
        if (project.workspaceId().filter(workspaceId::equals).isPresent()) return project;
        RegisteredProject updated = new RegisteredProject(project.id(), project.rootPath(), project.displayName(), Optional.of(workspaceId), project.createdAt(), Instant.now());
        writeProject(updated); return updated;
    }

    @Override
    public RegisteredProject removeProjectFromWorkspace(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        RegisteredProject project = findProject(projectId).orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectId));
        if (project.workspaceId().isEmpty()) return project;
        RegisteredProject updated = new RegisteredProject(project.id(), project.rootPath(), project.displayName(), Optional.empty(), project.createdAt(), Instant.now());
        writeProject(updated); return updated;
    }

    @Override
    public Optional<RegisteredProject> findProject(UUID projectId) throws IOException {
        Objects.requireNonNull(projectId, "projectId");
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT id,root_value,root_portable,display_name,workspace_id,created_at,updated_at FROM projects WHERE id=?")) {
                    s.setObject(1, projectId);
                    try (ResultSet r = s.executeQuery()) {
                        return r.next() ? Optional.of(readProject(r)) : Optional.empty();
                    }
                }
            });
        } catch (SQLException e) { throw io("find project", e); }
    }

    @Override
    public Optional<RegisteredWorkspace> findWorkspace(UUID workspaceId) throws IOException {
        Objects.requireNonNull(workspaceId, "workspaceId");
        try {
            return connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        WORKSPACE_WITH_PROJECTS_SELECT + " WHERE w.id=? ORDER BY p.id")) {
                    s.setObject(1, workspaceId);
                    try (ResultSet r = s.executeQuery()) {
                        if (!r.next()) return Optional.empty();
                        MutableWorkspace workspace = workspace(r);
                        addProjectId(workspace, r);
                        while (r.next()) addProjectId(workspace, r);
                        return Optional.of(workspace.toRegistered());
                    }
                }
            });
        } catch (SQLException e) { throw io("find workspace", e); }
    }

    @Override
    public List<RegisteredProject> listProjects() throws IOException {
        try {
            return connections.withConnection(c -> {
                List<RegisteredProject> values = new ArrayList<>();
                try (PreparedStatement s = c.prepareStatement(
                        "SELECT id,root_value,root_portable,display_name,workspace_id,created_at,updated_at FROM projects ORDER BY id");
                     ResultSet r = s.executeQuery()) {
                    while (r.next()) values.add(readProject(r));
                }
                return List.copyOf(values);
            });
        } catch (SQLException e) { throw io("list projects", e); }
    }

    @Override
    public List<RegisteredWorkspace> listWorkspaces() throws IOException {
        try {
            return connections.withConnection(c -> {
                Map<UUID, MutableWorkspace> grouped = new LinkedHashMap<>();
                try (PreparedStatement s = c.prepareStatement(
                        WORKSPACE_WITH_PROJECTS_SELECT + " ORDER BY w.id,p.id");
                     ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        UUID id = (UUID) r.getObject(1);
                        MutableWorkspace workspace = grouped.get(id);
                        if (workspace == null) {
                            workspace = workspace(r);
                            grouped.put(id, workspace);
                        }
                        addProjectId(workspace, r);
                    }
                }
                return grouped.values().stream().map(MutableWorkspace::toRegistered).toList();
            });
        } catch (SQLException e) { throw io("list workspaces", e); }
    }

    private RootIdentity rootIdentity(Path physicalRoot) throws IOException {
        boolean portable = mapping.isPresent();
        try {
            String rootValue = portable
                    ? mapping.orElseThrow().relativeForPhysical(physicalRoot, runtimeLocation)
                    : physicalRoot.toString();
            return new RootIdentity(rootValue, portable);
        } catch (IllegalArgumentException e) {
            throw new IOException("project path cannot be represented by configured runtime mapping", e);
        }
    }

    private void writeProject(RegisteredProject project) throws IOException {
        RootIdentity root = rootIdentity(project.rootPath());
        try {
            connections.withConnection(c -> {
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO projects(id,root_value,root_portable,display_name,workspace_id,created_at,updated_at) VALUES (?,?,?,?,?,?,?) " +
                                "ON CONFLICT(id) DO UPDATE SET root_value=EXCLUDED.root_value,root_portable=EXCLUDED.root_portable,display_name=EXCLUDED.display_name,workspace_id=EXCLUDED.workspace_id,updated_at=EXCLUDED.updated_at")) {
                    s.setObject(1, project.id());
                    s.setString(2, root.value());
                    s.setBoolean(3, root.portable());
                    s.setString(4, project.displayName());
                    s.setObject(5, project.workspaceId().orElse(null));
                    s.setObject(6, sqlTimestamp(project.createdAt()));
                    s.setObject(7, sqlTimestamp(project.updatedAt()));
                    s.executeUpdate();
                    return null;
                }
            });
        } catch (SQLException e) { throw io("write project", e); }
    }

    private RegisteredProject readProject(ResultSet r) throws SQLException, IOException {
        UUID id = (UUID) r.getObject(1); String rootValue = r.getString(2); boolean portable = r.getBoolean(3);
        Path root;
        if (portable) {
            ProjectPathMapping configured = mapping.orElseThrow(() -> new IOException("portable PostgreSQL registry requires runtime path mapping"));
            root = configured.resolve(rootValue, runtimeLocation);
        } else root = Path.of(rootValue).toAbsolutePath().normalize();
        UUID workspace = (UUID) r.getObject(5);
        return new RegisteredProject(id, root, r.getString(4), Optional.ofNullable(workspace),
                r.getObject(6, OffsetDateTime.class).toInstant(), r.getObject(7, OffsetDateTime.class).toInstant());
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
        if (projectId != null) workspace.projectIds.add(projectId);
    }

    private static OffsetDateTime sqlTimestamp(Instant value) {
        return Objects.requireNonNull(value, "timestamp").atOffset(ZoneOffset.UTC);
    }

    private static Path canonicalExistingDirectory(Path rootPath) throws IOException {
        Objects.requireNonNull(rootPath, "rootPath"); Path canonical = rootPath.toRealPath();
        if (!Files.isDirectory(canonical)) throw new IllegalArgumentException("rootPath must be an existing directory: " + rootPath);
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

    private static IOException io(String action, SQLException e) { return new IOException("PostgreSQL project registry failed to " + action, e); }
}
