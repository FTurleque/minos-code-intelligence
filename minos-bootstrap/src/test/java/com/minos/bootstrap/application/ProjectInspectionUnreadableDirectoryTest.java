package com.minos.bootstrap.application;

import com.minos.application.ProjectInspectionService;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.testsupport.UnreadableDirectory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D01 : l'inspection (et donc le statut {@code minos_index_status}) hérite de la correction de la
 * découverte. Un projet dont une dépendance en volume (un {@code pgdata/} en 0700, appartenant à root) est illisible
 * mais ignorée n'apparaît plus {@code UNREADABLE} : il est inspecté normalement. Un répertoire illisible non ignoré
 * continue de dégrader ce projet seul, ce que {@code ProjectInventoryToleranceTest} fixe pour POSIX. Ne couvre pas
 * D02 ni D03 (changement {@code diagnostiquer-statut-mcp-et-erreurs}). Ignoré pour un compte root ou administrateur.
 */
class ProjectInspectionUnreadableDirectoryTest {

    @TempDir
    Path temp;

    @Test
    void aProjectWithAnIgnoredUnreadableDirectoryIsInspectedNormally() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        Path root = Files.createDirectories(temp.resolve("project"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/A.java"), "class A {}");
        Files.writeString(root.resolve(".gitignore"), "pgdata/\n");
        Path pgdata = Files.createDirectories(root.resolve("pgdata"));
        Files.writeString(pgdata.resolve("PG_VERSION"), "16");
        RegisteredProject project = registry.registerProject(root, "with-volume");
        ProjectInspectionService inspection = inspection(home, registry);

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(pgdata)) {
            ProjectInspectionService.Inventory inventory = inspection.inventory();

            assertEquals(1, inventory.projects().size());
            ProjectInspectionService.ProjectView view = inventory.projects().getFirst();
            assertEquals(project.id().toString(), view.id());
            assertTrue(inventory.degraded().isEmpty(), "an ignored unreadable directory degrades nothing: " + inventory.degraded());
            assertFalse("UNREADABLE".equals(view.indexState()), "the project must be inspected, not unreadable");
            assertEquals("NEVER_INDEXED", view.indexState());
        }
    }

    @Test
    void aProjectWithAnUnreadableDirectoryThatIsNotIgnoredStaysUnreadableAlone() throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
        Path healthyRoot = Files.createDirectories(temp.resolve("healthy"));
        Files.writeString(healthyRoot.resolve("pom.xml"), "<project/>");
        Path brokenRoot = Files.createDirectories(temp.resolve("broken"));
        Files.writeString(brokenRoot.resolve("pom.xml"), "<project/>");
        Path database = Files.createDirectories(brokenRoot.resolve("data/db"));
        RegisteredProject healthy = registry.registerProject(healthyRoot, "healthy");
        RegisteredProject broken = registry.registerProject(brokenRoot, "broken");
        ProjectInspectionService inspection = inspection(home, registry);

        try (UnreadableDirectory ignored = UnreadableDirectory.denyOrSkip(database)) {
            ProjectInspectionService.Inventory inventory = inspection.inventory();

            assertEquals(2, inventory.projects().size());
            for (ProjectInspectionService.ProjectView view : inventory.projects()) {
                if (view.id().equals(broken.id().toString())) assertEquals("UNREADABLE", view.indexState());
                if (view.id().equals(healthy.id().toString())) assertEquals("NEVER_INDEXED", view.indexState());
            }
            assertEquals(1, inventory.degraded().size());
            String text = inventory.degraded().getFirst().entry() + " " + inventory.degraded().getFirst().reason();
            assertFalse(text.contains(temp.toString()), "no absolute path in the public reason: " + text);
        }
    }

    private static ProjectInspectionService inspection(Path home, LocalProjectRegistry registry) throws IOException {
        IndexStateStore stateStore = new FileIndexStateStore(home.resolve("index-state"));
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        return new ProjectInspectionService(home, registry, snapshots, stateStore, new ProjectDiscoveryService(), List.of());
    }
}
