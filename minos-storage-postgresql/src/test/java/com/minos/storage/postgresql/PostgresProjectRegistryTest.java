package com.minos.storage.postgresql;

import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PostgresProjectRegistryTest extends PostgresTestSupport {

    @Test
    void duplicateCanonicalRootReturnsExistingProjectUnchanged(@TempDir Path temp) throws Exception {
        PostgresProjectRegistry registry = registry(temp);
        Path root = Files.createDirectories(temp.resolve("project"));

        RegisteredProject first = registry.registerProject(root, "Original name");
        RegisteredProject duplicate = registry.registerProject(root, "Different name");

        assertEquals(first.id(), duplicate.id());
        assertEquals("Original name", duplicate.displayName());
        assertEquals(first.createdAt(), duplicate.createdAt());
        assertEquals(1, registry.listProjects().size());
    }

    @Test
    void workspacesAreLoadedWithMembershipInOneJoinedResult(@TempDir Path temp) throws Exception {
        PostgresProjectRegistry registry = registry(temp);
        RegisteredProject first = registry.registerProject(Files.createDirectories(temp.resolve("p1")), "P1");
        RegisteredProject second = registry.registerProject(Files.createDirectories(temp.resolve("p2")), "P2");
        RegisteredProject third = registry.registerProject(Files.createDirectories(temp.resolve("p3")), "P3");
        RegisteredWorkspace alpha = registry.createWorkspace("Alpha");
        RegisteredWorkspace beta = registry.createWorkspace("Beta");
        RegisteredWorkspace empty = registry.createWorkspace("Empty");

        registry.assignProjectToWorkspace(first.id(), alpha.id());
        registry.assignProjectToWorkspace(second.id(), alpha.id());
        registry.assignProjectToWorkspace(third.id(), beta.id());

        RegisteredWorkspace loadedAlpha = registry.findWorkspace(alpha.id()).orElseThrow();
        assertEquals(List.of(first.id(), second.id()).stream().sorted().toList(), loadedAlpha.projectIds());

        List<RegisteredWorkspace> workspaces = registry.listWorkspaces();
        assertEquals(3, workspaces.size());
        assertEquals(List.of(first.id(), second.id()).stream().sorted().toList(),
                workspace(workspaces, alpha).projectIds());
        assertEquals(List.of(third.id()), workspace(workspaces, beta).projectIds());
        assertEquals(List.of(), workspace(workspaces, empty).projectIds());
    }

    private PostgresProjectRegistry registry(Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        return new PostgresProjectRegistry(connections, home);
    }

    private static RegisteredWorkspace workspace(List<RegisteredWorkspace> values, RegisteredWorkspace expected) {
        return values.stream().filter(value -> value.id().equals(expected.id())).findFirst().orElseThrow();
    }
}
