package com.minos.bootstrap.application;

import com.minos.application.ProjectInspectionService;
import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDetector;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.registry.RegisteredProject;
import com.minos.source.SourceBudgetPolicy;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * MINOS-AUD-C03 / D02 : l'état d'index d'un projet est calculé sans parcourir l'arbre du dépôt, donc une découverte
 * qui échoue (répertoire illisible, budget de traversée dépassé) ne fait pas échouer le statut ; les vues qui
 * rapportent la structure du dépôt découvrent toujours.
 */
class ProjectStatusWithoutDiscoveryTest {

    @TempDir
    Path root;

    @Test
    void theStatusNeverCallsTheDiscoveryWhereTheStructureViewDoes() throws Exception {
        AtomicInteger discoveries = new AtomicInteger();
        Fixture fixture = new Fixture(root, countingFailingDiscovery(discoveries));

        ProjectInspectionService.ProjectView status = fixture.service.inspectStatus(fixture.project.id().toString());

        assertEquals(0, discoveries.get(), "the status must not walk the repository");
        assertEquals("NEVER_INDEXED", status.indexState());
        assertTrue(status.rootAvailable());
        assertEquals(List.of(), status.languages(), "a status does not report the structure of the repository");
        assertEquals(List.of(), status.buildSystems());
        assertEquals(0, status.moduleCount());

        assertThrows(IOException.class, () -> fixture.service.inspectProject(fixture.project.id().toString()),
                "the structure view still discovers, and here discovery fails");
        assertTrue(discoveries.get() > 0);
    }

    @Test
    void aTraversalBudgetTooSmallForTheRepositoryDoesNotFailTheStatus() throws Exception {
        Fixture fixture = new Fixture(root, new ProjectDiscoveryService(
                DefaultDiscoveryPlugins.projectDetectors(), DefaultDiscoveryPlugins.buildSystemDetectors(),
                DefaultDiscoveryPlugins.sourceRootDetectors(), DefaultDiscoveryPlugins.languageDetectors(),
                new SourceBudgetPolicy(1, 1)));
        Files.createDirectories(fixture.projectRoot.resolve("src/main/java"));
        for (String name : new String[]{"A.java", "B.java", "C.java"}) {
            Files.writeString(fixture.projectRoot.resolve("src/main/java").resolve(name), "class X {}");
        }

        assertThrows(IOException.class, () -> fixture.service.inspectProject(fixture.project.id().toString()),
                "the premise: the structure view fails on this budget");
        ProjectInspectionService.ProjectView status = fixture.service.inspectStatus(fixture.project.id().toString());

        assertEquals("NEVER_INDEXED", status.indexState());
    }

    @Test
    void aMissingProjectRootIsReportedAsUnavailableWithTheIndexState() throws Exception {
        Fixture fixture = new Fixture(root, new ProjectDiscoveryService());
        Files.delete(fixture.projectRoot.resolve("pom.xml"));
        Files.delete(fixture.projectRoot);

        ProjectInspectionService.ProjectView status = fixture.service.inspectStatus(fixture.project.id().toString());

        assertFalse(status.rootAvailable());
        assertEquals("NEVER_INDEXED", status.indexState());
    }

    @Test
    void theTolerantStatusAnswersWithoutDiscoveryAndStillListsTheUnreadableEntries() throws Exception {
        AtomicInteger discoveries = new AtomicInteger();
        Fixture fixture = new Fixture(root, countingFailingDiscovery(discoveries));

        ProjectInspectionService.Inspection inspection = fixture.service.statusInspection("status-project");

        assertEquals(0, discoveries.get());
        assertEquals("NEVER_INDEXED", inspection.project().indexState());
        assertTrue(inspection.unreadable().isEmpty());
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void aReallyUnreadableSubdirectoryDoesNotFailTheStatus() throws Exception {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads every directory");
        Fixture fixture = new Fixture(root, new ProjectDiscoveryService());
        Path locked = Files.createDirectory(fixture.projectRoot.resolve("locked"));
        Files.createFile(locked.resolve("inside.txt"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "this account can read an unreadable directory");
            assertThrows(IOException.class, () -> fixture.service.inspectProject(fixture.project.id().toString()),
                    "the premise: the structure view fails on an unreadable directory");

            ProjectInspectionService.ProjectView status = fixture.service.inspectStatus(fixture.project.id().toString());

            assertEquals("NEVER_INDEXED", status.indexState());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    /** A discovery whose first project detector fails like a walk over an unreadable directory, counting its calls. */
    private static ProjectDiscoveryService countingFailingDiscovery(AtomicInteger calls) {
        List<ProjectDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.projectDetectors());
        detectors.add(0, (projectRoot, directory, ignorePolicy) -> {
            calls.incrementAndGet();
            throw new UncheckedIOException(new AccessDeniedException("locked"));
        });
        return new ProjectDiscoveryService(detectors, DefaultDiscoveryPlugins.buildSystemDetectors(),
                DefaultDiscoveryPlugins.sourceRootDetectors(), DefaultDiscoveryPlugins.languageDetectors());
    }

    private static final class Fixture {
        private final Path projectRoot;
        private final RegisteredProject project;
        private final ProjectInspectionService service;

        private Fixture(Path root, ProjectDiscoveryService discovery) throws Exception {
            Path home = Files.createDirectories(root.resolve("home"));
            this.projectRoot = Files.createDirectories(root.resolve("project"));
            Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
            LocalProjectRegistry registry = new LocalProjectRegistry(home.resolve("registry"));
            this.project = registry.registerProject(projectRoot, "status-project");
            this.service = new ProjectInspectionService(
                    home, registry, new FileSymbolSnapshotStore(home.resolve("symbol-snapshots")),
                    new FileIndexStateStore(home.resolve("index-state")), discovery, List.of());
        }
    }
}
