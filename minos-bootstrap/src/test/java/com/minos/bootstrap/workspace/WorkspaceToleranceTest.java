package com.minos.bootstrap.workspace;

import com.minos.registry.DegradedEntry;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.registry.UnreadableRegistryException;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.workspace.WorkspaceIntelligenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q26 : le service porte les {@link DegradedEntry} du registre telles quelles (aucune seconde notion d'entrée dégradée,
 * aucun recomptage), et ne tire une conclusion d'absence que de ce qu'il a lu en entier.
 */
class WorkspaceToleranceTest {

    @TempDir
    Path home;

    @Test
    void theListingCarriesTheRegistrysOwnDegradedEntries() throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredProject alpha = registry.registerProject(Files.createDirectories(home.resolve("alpha")), "alpha");
        RegisteredProject beta = registry.registerProject(Files.createDirectories(home.resolve("beta")), "beta");
        RegisteredWorkspace platform = registry.createWorkspace("platform");
        registry.assignProjectToWorkspace(alpha.id(), platform.id());
        registry.assignProjectToWorkspace(beta.id(), platform.id());
        Path betaEntry = home.resolve("registry").resolve("projects").resolve(beta.id() + ".properties");
        Files.writeString(betaEntry, Files.readString(betaEntry, StandardCharsets.UTF_8)
                .replaceAll("createdAt=.*", "createdAt=nope"), StandardCharsets.UTF_8);
        WorkspaceIntelligenceService service = new WorkspaceIntelligenceService(
                registry, new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")));

        WorkspaceIntelligenceService.WorkspaceListing listing = service.listWorkspacesTolerantly();
        ProjectRegistry.WorkspaceInventory fromRegistry = registry.workspaceInventory();

        assertEquals(fromRegistry.unreadableProjects(), listing.unreadableProjects());
        assertEquals(List.of(), listing.unreadableWorkspaces());
        assertEquals(1, listing.unreadableProjects().size());
        DegradedEntry entry = listing.unreadableProjects().getFirst();
        assertEquals(beta.id().toString(), entry.entry());
        assertTrue(entry.reason().startsWith("registry entry is unreadable"), entry.reason());
    }

    @Test
    void aNameAbsentBesideADamagedWorkspaceEntryFailsWithTheRegistrysSignalNotWithAnAbsence() throws Exception {
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        RegisteredWorkspace platform = registry.createWorkspace("platform");
        RegisteredWorkspace tooling = registry.createWorkspace("tooling");
        Path toolingEntry = home.resolve("registry").resolve("workspaces").resolve(tooling.id() + ".properties");
        Files.writeString(toolingEntry, "id=" + tooling.id() + "\nname=\n", StandardCharsets.UTF_8);
        WorkspaceIntelligenceService service = new WorkspaceIntelligenceService(
                registry, new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")));

        UnreadableRegistryException failure = assertThrows(UnreadableRegistryException.class,
                () -> service.getWorkspaceTolerantly("ghost"));

        assertEquals(1, failure.unreadable().size());
        assertEquals(platform.id().toString(), service.getWorkspaceTolerantly("platform").workspace().id());
    }
}
