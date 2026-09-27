package com.minos.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalProjectRegistryTest {

    @Test
    void persistsProjectIdentityAcrossRegistryInstances(@TempDir Path temp) throws IOException {
        Path storage = temp.resolve("registry");
        Path firstRoot = Files.createDirectories(temp.resolve("projects/first"));
        Path secondRoot = Files.createDirectories(temp.resolve("projects/second"));

        LocalProjectRegistry firstRegistry = new LocalProjectRegistry(storage);
        RegisteredProject first = firstRegistry.registerProject(firstRoot, "First project");
        RegisteredProject second = firstRegistry.registerProject(secondRoot, "Second project");

        assertNotEquals(first.id(), second.id());
        assertTrue(Files.isRegularFile(storage.resolve("projects/" + first.id() + ".properties")));

        LocalProjectRegistry reloadedRegistry = new LocalProjectRegistry(storage);
        RegisteredProject reloaded = reloadedRegistry.registerProject(firstRoot, "Different display name");

        assertEquals(first.id(), reloaded.id());
        assertEquals("First project", reloaded.displayName());
        assertEquals(first.rootPath(), reloaded.rootPath());
        assertEquals(2, reloadedRegistry.listProjects().size());
    }

    @Test
    void persistsWorkspaceMembershipFromProjectRecord(@TempDir Path temp) throws IOException {
        Path storage = temp.resolve("registry");
        Path projectRoot = Files.createDirectories(temp.resolve("project"));

        LocalProjectRegistry registry = new LocalProjectRegistry(storage);
        RegisteredProject project = registry.registerProject(projectRoot, "Demo");
        RegisteredWorkspace workspace = registry.createWorkspace("Workspace A");

        RegisteredProject assigned = registry.assignProjectToWorkspace(project.id(), workspace.id());
        assertEquals(workspace.id(), assigned.workspaceId().orElseThrow());

        LocalProjectRegistry reloaded = new LocalProjectRegistry(storage);
        RegisteredProject persistedProject = reloaded.findProject(project.id()).orElseThrow();
        RegisteredWorkspace persistedWorkspace = reloaded.findWorkspace(workspace.id()).orElseThrow();

        assertEquals(workspace.id(), persistedProject.workspaceId().orElseThrow());
        assertEquals(java.util.List.of(project.id()), persistedWorkspace.projectIds());

        reloaded.removeProjectFromWorkspace(project.id());
        assertTrue(reloaded.findProject(project.id()).orElseThrow().workspaceId().isEmpty());
        assertTrue(reloaded.findWorkspace(workspace.id()).orElseThrow().projectIds().isEmpty());
    }

    @Test
    void rejectsProjectMetadataWhoseEmbeddedIdentityDoesNotMatchFilename(@TempDir Path temp) throws IOException {
        Path storage = temp.resolve("project-identity-registry");
        Path projectRoot = Files.createDirectories(temp.resolve("project-identity-root"));
        LocalProjectRegistry registry = new LocalProjectRegistry(storage);
        RegisteredProject project = registry.registerProject(projectRoot, "Identity project");
        UUID otherId = UUID.randomUUID();

        Path file = storage.resolve("projects").resolve(project.id() + ".properties");
        Files.writeString(file, Files.readString(file).replace(
                "id=" + project.id(),
                "id=" + otherId));

        IOException lookupFailure = assertThrows(IOException.class, () -> registry.findProject(project.id()));
        assertTrue(lookupFailure.getMessage().contains("identity mismatch"));
        assertThrows(IOException.class, registry::listProjects);
    }

    @Test
    void rejectsWorkspaceMetadataWhoseEmbeddedIdentityDoesNotMatchFilename(@TempDir Path temp) throws IOException {
        Path storage = temp.resolve("workspace-identity-registry");
        LocalProjectRegistry registry = new LocalProjectRegistry(storage);
        RegisteredWorkspace workspace = registry.createWorkspace("Identity workspace");
        UUID otherId = UUID.randomUUID();

        Path file = storage.resolve("workspaces").resolve(workspace.id() + ".properties");
        Files.writeString(file, Files.readString(file).replace(
                "id=" + workspace.id(),
                "id=" + otherId));

        IOException lookupFailure = assertThrows(IOException.class, () -> registry.findWorkspace(workspace.id()));
        assertTrue(lookupFailure.getMessage().contains("identity mismatch"));
        assertThrows(IOException.class, registry::listWorkspaces);
    }

    @Test
    void rejectsUnknownWorkspaceAssignment(@TempDir Path temp) throws IOException {
        Path projectRoot = Files.createDirectories(temp.resolve("project"));
        LocalProjectRegistry registry = new LocalProjectRegistry(temp.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, "Demo");

        assertThrows(
                IllegalArgumentException.class,
                () -> registry.assignProjectToWorkspace(project.id(), java.util.UUID.randomUUID())
        );
    }
}
