package com.minos.storage.local.registry;

import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.testsupport.WriteProtectedTree;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Le plan de requête lit le registre sur un support qu'il ne peut pas écrire (répertoire de données monté en lecture
 * seule pour le service {@code minos-mcp}) : une lecture du registre n'ouvre rien en écriture et ne change rien.
 */
class InterProcessLocalProjectRegistryWriteProtectedStorageTest {

    @TempDir
    Path temporary;

    @Test
    void everyReadAnswersOnAWriteProtectedRegistryAndLeavesItUntouched() throws Exception {
        Path storage = temporary.resolve("registry");
        Path projectRoot = Files.createDirectory(temporary.resolve("project"));
        InterProcessLocalProjectRegistry registry = new InterProcessLocalProjectRegistry(storage);
        RegisteredWorkspace workspace = registry.createWorkspace("team");
        RegisteredProject project = registry.assignProjectToWorkspace(
                registry.registerProject(projectRoot, "fixture").id(), workspace.id());

        try (WriteProtectedTree protectedStorage = WriteProtectedTree.protect(storage)) {
            Map<String, String> before = protectedStorage.state();

            assertEquals(project, registry.findProject(project.id()).orElseThrow());
            assertEquals(List.of(project.id()),
                    registry.findWorkspace(workspace.id()).orElseThrow().projectIds());
            assertEquals(1, registry.listProjects().size());
            assertEquals(1, registry.inventory().projects().size());
            assertEquals(1, registry.listWorkspaces().size());
            assertEquals(1, registry.workspaceInventory().workspaces().size());

            assertEquals(before, protectedStorage.state(), "a read must not open, harden or create anything");
        }
    }

    @Test
    void aRegistryOpenedOnAWriteProtectedStorageStillAnswersReads() throws Exception {
        Path storage = temporary.resolve("registry");
        Path projectRoot = Files.createDirectory(temporary.resolve("project"));
        RegisteredProject project = new InterProcessLocalProjectRegistry(storage).registerProject(projectRoot, "fixture");

        try (WriteProtectedTree protectedStorage = WriteProtectedTree.protect(storage)) {
            Map<String, String> before = protectedStorage.state();

            InterProcessLocalProjectRegistry reopened = new InterProcessLocalProjectRegistry(storage);

            assertEquals(project, reopened.findProject(project.id()).orElseThrow());
            assertEquals(before, protectedStorage.state(), "opening and reading must not open, harden or create anything");
        }
    }
}
