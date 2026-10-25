package com.minos.storage.postgresql.project;

import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.storage.local.registry.ProjectPathMappingStore;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.storage.postgresql.support.PostgresConnectionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresProjectRegistryLifecycleTest extends PostgresTestSupport {

    @TempDir Path temp;

    @Test
    void registrationReportsWhetherThisCallCreatedTheProject() throws Exception {
        PostgresProjectRegistry registry = registry();
        Path root = Files.createDirectories(temp.resolve("project"));

        ProjectRegistry.RegistrationResult created = registry.registerProjectWithResult(root, "Service");
        ProjectRegistry.RegistrationResult again = registry.registerProjectWithResult(root, "Renamed");

        assertTrue(created.createdByThisCall());
        assertFalse(again.createdByThisCall());
        assertEquals(created.project(), again.project());
        assertEquals(root.toRealPath(), created.project().rootPath());
        assertEquals(Optional.empty(), created.project().workspaceId());
        assertEquals(Optional.of(created.project()), registry.findProject(created.project().id()));
        assertEquals(Optional.empty(), registry.findProject(UUID.randomUUID()));
        assertFalse(storedPortable(created.project().id()), "without a runtime mapping the root is stored as a physical path");
    }

    @Test
    void workspaceCreationIsIdempotentByName() throws Exception {
        PostgresProjectRegistry registry = registry();

        ProjectRegistry.WorkspaceRegistrationResult created = registry.createWorkspaceWithResult("Team");
        ProjectRegistry.WorkspaceRegistrationResult again = registry.createWorkspaceWithResult("Team");

        assertTrue(created.createdByThisCall());
        assertFalse(again.createdByThisCall());
        assertEquals(created.workspace(), again.workspace());
        assertEquals(List.of(), created.workspace().projectIds());
        assertEquals(Optional.of(created.workspace()), registry.findWorkspace(created.workspace().id()));
        assertEquals(Optional.empty(), registry.findWorkspace(UUID.randomUUID()));
        assertEquals(List.of(created.workspace()), registry.listWorkspaces());
    }

    @Test
    void aProjectMovesBetweenWorkspacesAndCanBeDetached() throws Exception {
        PostgresProjectRegistry registry = registry();
        RegisteredProject project = registry.registerProject(Files.createDirectories(temp.resolve("project")), "Service");
        RegisteredWorkspace alpha = registry.createWorkspace("Alpha");
        RegisteredWorkspace beta = registry.createWorkspace("Beta");

        RegisteredProject inAlpha = registry.assignProjectToWorkspace(project.id(), alpha.id());
        assertEquals(Optional.of(alpha.id()), inAlpha.workspaceId());
        assertEquals(Optional.of(alpha.id()), registry.findProject(project.id()).orElseThrow().workspaceId());

        RegisteredProject inBeta = registry.assignProjectToWorkspace(project.id(), beta.id());
        assertEquals(Optional.of(beta.id()), inBeta.workspaceId());
        assertEquals(List.of(), registry.findWorkspace(alpha.id()).orElseThrow().projectIds());
        assertEquals(List.of(project.id()), registry.findWorkspace(beta.id()).orElseThrow().projectIds());

        RegisteredProject detached = registry.removeProjectFromWorkspace(project.id());
        assertEquals(Optional.empty(), detached.workspaceId());
        assertEquals(Optional.empty(), registry.findProject(project.id()).orElseThrow().workspaceId());
        assertEquals(List.of(), registry.findWorkspace(beta.id()).orElseThrow().projectIds());
        assertEquals(project.createdAt(), detached.createdAt());
        assertEquals("Service", detached.displayName());
    }

    @Test
    void repeatingAnAssignmentOrADetachmentChangesNothing() throws Exception {
        PostgresProjectRegistry registry = registry();
        RegisteredProject project = registry.registerProject(Files.createDirectories(temp.resolve("project")), "Service");
        RegisteredWorkspace alpha = registry.createWorkspace("Alpha");

        RegisteredProject unassigned = registry.findProject(project.id()).orElseThrow();
        assertEquals(unassigned, registry.removeProjectFromWorkspace(project.id()),
                "detaching a project that has no workspace must not touch its row");

        registry.assignProjectToWorkspace(project.id(), alpha.id());
        RegisteredProject assigned = registry.findProject(project.id()).orElseThrow();
        assertEquals(assigned, registry.assignProjectToWorkspace(project.id(), alpha.id()),
                "assigning a project to its current workspace must not touch its row");
        assertEquals(assigned, registry.findProject(project.id()).orElseThrow());
    }

    @Test
    void unknownProjectsAndWorkspacesAreRefusedAndNothingIsChanged() throws Exception {
        PostgresProjectRegistry registry = registry();
        RegisteredProject project = registry.registerProject(Files.createDirectories(temp.resolve("project")), "Service");
        RegisteredWorkspace workspace = registry.createWorkspace("Alpha");
        UUID unknownProject = UUID.randomUUID();
        UUID knownProject = project.id();
        UUID knownWorkspace = workspace.id();
        UUID unknownWorkspace = UUID.randomUUID();

        IllegalArgumentException assignUnknownProject = assertThrows(IllegalArgumentException.class,
                () -> registry.assignProjectToWorkspace(unknownProject, knownWorkspace));
        IllegalArgumentException assignUnknownWorkspace = assertThrows(IllegalArgumentException.class,
                () -> registry.assignProjectToWorkspace(knownProject, unknownWorkspace));
        IllegalArgumentException removeUnknownProject = assertThrows(IllegalArgumentException.class,
                () -> registry.removeProjectFromWorkspace(unknownProject));

        assertEquals("Unknown project: " + unknownProject, assignUnknownProject.getMessage());
        assertEquals("Unknown workspace: " + unknownWorkspace, assignUnknownWorkspace.getMessage());
        assertEquals("Unknown project: " + unknownProject, removeUnknownProject.getMessage());
        assertEquals(Optional.empty(), registry.findProject(project.id()).orElseThrow().workspaceId());
        assertEquals(List.of(), registry.findWorkspace(workspace.id()).orElseThrow().projectIds());
        assertEquals(0, connections.poolStats().leased(), "a refused mutation must release its transaction connection");
    }

    @Test
    void aDeletedProjectIsGoneAndItsRootCanBeRegisteredAgainUnderANewIdentity() throws Exception {
        PostgresProjectRegistry registry = registry();
        Path root = Files.createDirectories(temp.resolve("project"));
        RegisteredProject project = registry.registerProject(root, "Service");
        RegisteredWorkspace workspace = registry.createWorkspace("Alpha");
        registry.assignProjectToWorkspace(project.id(), workspace.id());

        assertTrue(registry.deleteProject(project.id()));
        assertFalse(registry.deleteProject(project.id()), "deleting twice reports that nothing was deleted");
        assertFalse(registry.deleteProject(UUID.randomUUID()));

        assertEquals(Optional.empty(), registry.findProject(project.id()));
        assertEquals(List.of(), registry.listProjects());
        assertEquals(List.of(), registry.findWorkspace(workspace.id()).orElseThrow().projectIds());
        ProjectRegistry.RegistrationResult recreated = registry.registerProjectWithResult(root, "Service");
        assertTrue(recreated.createdByThisCall());
        assertNotEquals(project.id(), recreated.project().id());
    }

    @Test
    void aRootThatIsNotAnExistingDirectoryIsRefusedAndNothingIsRegistered() throws Exception {
        PostgresProjectRegistry registry = registry();
        Path file = Files.writeString(temp.resolve("not-a-directory.txt"), "content");
        Path missing = temp.resolve("missing");
        Path directory = Files.createDirectories(temp.resolve("project"));

        IllegalArgumentException notADirectory = assertThrows(IllegalArgumentException.class,
                () -> registry.registerProject(file, "File"));
        assertThrows(IOException.class, () -> registry.registerProject(missing, "Missing"));
        assertThrows(NullPointerException.class, () -> registry.registerProject(null, "Null"));
        IllegalArgumentException badName = assertThrows(IllegalArgumentException.class,
                () -> registry.registerProject(directory, "bad\0name"));
        IllegalArgumentException badWorkspace = assertThrows(IllegalArgumentException.class,
                () -> registry.createWorkspace("bad\0name"));

        assertTrue(notADirectory.getMessage().startsWith("rootPath must be an existing directory: "),
                "unexpected refusal: " + notADirectory.getClass());
        assertFalse(notADirectory.getMessage().contains(temp.toString()), notADirectory.getMessage());
        assertEquals("displayName must not contain NUL", badName.getMessage());
        assertEquals("name must not contain NUL", badWorkspace.getMessage());
        assertEquals(List.of(), registry.listProjects());
        assertEquals(List.of(), registry.listWorkspaces());
    }

    @Test
    void missingIdentifiersAreRefused() throws Exception {
        PostgresProjectRegistry registry = registry();
        UUID id = UUID.randomUUID();

        assertThrows(NullPointerException.class, () -> registry.findProject(null));
        assertThrows(NullPointerException.class, () -> registry.findWorkspace(null));
        assertThrows(NullPointerException.class, () -> registry.deleteProject(null));
        assertThrows(NullPointerException.class, () -> registry.removeProjectFromWorkspace(null));
        assertThrows(NullPointerException.class, () -> registry.assignProjectToWorkspace(null, id));
        assertThrows(NullPointerException.class, () -> registry.assignProjectToWorkspace(id, null));
        assertThrows(NullPointerException.class, () -> new PostgresProjectRegistry(null, temp));
        assertEquals(0, connections.poolStats().leased());
    }

    @Test
    void withARuntimeMappingTheRootIsStoredRelativeAndResolvedBackToItsPhysicalLocation() throws Exception {
        Path projects = Files.createDirectories(temp.resolve("projects")).toRealPath();
        PostgresProjectRegistry registry = new PostgresProjectRegistry(connections, mappedHome("mapped-home", projects));
        Path root = Files.createDirectories(projects.resolve("team").resolve("service"));

        RegisteredProject project = registry.registerProject(root, "Service");

        assertEquals(root.toRealPath(), project.rootPath());
        assertEquals("team/service", storedRootValue(project.id()),
                "the shared database must hold the portable relative root, never a host path");
        assertTrue(storedPortable(project.id()));
        assertEquals(Optional.of(project), registry.findProject(project.id()));
        assertEquals(List.of(project), registry.listProjects());
        assertEquals(project.id(), registry.registerProject(root, "Again").id(), "the portable root is the identity");
    }

    @Test
    void aRootOutsideTheMappedRootIsRefusedAndNothingIsStored() throws Exception {
        Path projects = Files.createDirectories(temp.resolve("projects")).toRealPath();
        PostgresProjectRegistry registry = new PostgresProjectRegistry(connections, mappedHome("mapped-home", projects));
        Path outside = Files.createDirectories(temp.resolve("outside"));

        IOException refusal = assertThrows(IOException.class, () -> registry.registerProject(outside, "Outside"));

        assertEquals("project path cannot be represented by configured runtime mapping", refusal.getMessage());
        assertEquals(List.of(), registry.listProjects());
    }

    @Test
    void aPortableRowCannotBeReadWithoutTheRuntimeMapping() throws Exception {
        Path projects = Files.createDirectories(temp.resolve("projects")).toRealPath();
        PostgresProjectRegistry mapped = new PostgresProjectRegistry(connections, mappedHome("mapped-home", projects));
        RegisteredProject project = mapped.registerProject(Files.createDirectories(projects.resolve("service")), "Service");
        PostgresProjectRegistry unmapped = registry();

        IOException find = assertThrows(IOException.class, () -> unmapped.findProject(project.id()));
        IOException list = assertThrows(IOException.class, unmapped::listProjects);

        assertEquals("portable PostgreSQL registry requires runtime path mapping", find.getMessage());
        assertEquals("portable PostgreSQL registry requires runtime path mapping", list.getMessage());
    }

    @Test
    void aStorageFailureIsReportedByOperationWithoutAnyPathInItsMessage() throws Exception {
        PostgresConnectionFactory closed = createFactory("minos");
        closed.close();
        PostgresProjectRegistry registry = new PostgresProjectRegistry(closed, Files.createDirectories(temp.resolve("home")));
        Path root = Files.createDirectories(temp.resolve("project"));
        UUID id = UUID.randomUUID();

        IOException register = assertThrows(IOException.class, () -> registry.registerProject(root, "Service"));
        IOException createWorkspace = assertThrows(IOException.class, () -> registry.createWorkspace("Alpha"));
        IOException assign = assertThrows(IOException.class, () -> registry.assignProjectToWorkspace(id, id));
        IOException remove = assertThrows(IOException.class, () -> registry.removeProjectFromWorkspace(id));
        IOException findProject = assertThrows(IOException.class, () -> registry.findProject(id));
        IOException findWorkspace = assertThrows(IOException.class, () -> registry.findWorkspace(id));
        IOException listProjects = assertThrows(IOException.class, registry::listProjects);
        IOException listWorkspaces = assertThrows(IOException.class, registry::listWorkspaces);
        IOException delete = assertThrows(IOException.class, () -> registry.deleteProject(id));

        assertEquals("PostgreSQL project registry failed to register project", register.getMessage());
        assertEquals("PostgreSQL project registry failed to create workspace", createWorkspace.getMessage());
        assertEquals("PostgreSQL project registry failed to assign project to workspace", assign.getMessage());
        assertEquals("PostgreSQL project registry failed to remove project from workspace", remove.getMessage());
        assertEquals("PostgreSQL project registry failed to find project", findProject.getMessage());
        assertEquals("PostgreSQL project registry failed to find workspace", findWorkspace.getMessage());
        assertEquals("PostgreSQL project registry failed to list projects", listProjects.getMessage());
        assertEquals("PostgreSQL project registry failed to list workspaces", listWorkspaces.getMessage());
        assertEquals("PostgreSQL project registry failed to delete project", delete.getMessage());
        for (IOException failure : List.of(register, createWorkspace, assign, remove, findProject, findWorkspace,
                listProjects, listWorkspaces, delete)) {
            assertInstanceOf(SQLException.class, failure.getCause());
            assertFalse(failure.getMessage().contains(temp.toString()), failure.getMessage());
        }
        assertEquals(List.of(), registry().listProjects(), "nothing may be written through a closed pool");
        assertEquals(List.of(), registry().listWorkspaces());
    }

    private PostgresProjectRegistry registry() throws IOException {
        return new PostgresProjectRegistry(connections, Files.createDirectories(temp.resolve("home")));
    }

    /** A MINOS home whose runtime mapping exposes {@code projects} at the same location on host and container. */
    private Path mappedHome(String name, Path projects) throws IOException {
        Path home = Files.createDirectories(temp.resolve(name));
        new ProjectPathMappingStore(home).save(new ProjectPathMapping(projects.toString(), projects.toString()));
        return home;
    }

    private String storedRootValue(UUID projectId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT root_value FROM projects WHERE id=?")) {
                statement.setObject(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getString(1);
                }
            }
        });
    }

    private boolean storedPortable(UUID projectId) throws Exception {
        return connections.withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT root_portable FROM projects WHERE id=?")) {
                statement.setObject(1, projectId);
                try (ResultSet result = statement.executeQuery()) {
                    result.next();
                    return result.getBoolean(1);
                }
            }
        });
    }
}
