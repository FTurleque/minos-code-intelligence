package com.minos.bootstrap.application;

import com.minos.application.ProjectInspectionService;
import com.minos.diagnostics.PublicErrorMessages;
import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.discovery.spi.ProjectDetector;
import com.minos.orchestration.IndexStateStore;
import com.minos.registry.DegradedEntry;
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.orchestration.FileIndexStateStore;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.ClosedByInterruptException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Q8 : un projet dont la vue ne peut être assemblée (entrée de registre abîmée, historique abîmé, répertoire
 * illisible sous sa racine) apparaît avec l'état {@code UNREADABLE} et est compté ; les autres projets sont
 * rendus comme avant. Registre, magasin d'état et découverte sont les vrais : seule la défaillance est injectée.
 */
class ProjectInventoryToleranceTest {

    private static final String REGISTRY = "registry";
    private static final String NEVER_INDEXED = "NEVER_INDEXED";
    private static final String UNREADABLE = "UNREADABLE";
    private static final String PROPERTIES = ".properties";
    private static final String SNAPSHOT = "snapshot-1";

    @TempDir
    Path temp;

    private Path home;
    private LocalProjectRegistry registry;
    private final List<RegisteredProject> projects = new ArrayList<>();

    private void registerProjects(int count) throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        registry = new LocalProjectRegistry(home.resolve(REGISTRY));
        for (int index = 0; index < count; index++) {
            Path root = Files.createDirectories(temp.resolve("project-" + index));
            projects.add(registry.registerProject(root, "Project " + index));
        }
    }

    private ProjectInspectionService inspection(ProjectDiscoveryService discovery) throws IOException {
        IndexStateStore stateStore = new FileIndexStateStore(home.resolve("index-state"));
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"));
        return new ProjectInspectionService(home, registry, snapshots, stateStore, discovery, List.of());
    }

    private void corruptRegistryEntry(RegisteredProject project) throws IOException {
        Path file = home.resolve(REGISTRY).resolve("projects").resolve(project.id() + PROPERTIES);
        Files.writeString(file,
                Files.readString(file, StandardCharsets.UTF_8).replaceAll("createdAt=.*", "createdAt=not-an-instant"),
                StandardCharsets.UTF_8);
    }

    private void writeHistory(RegisteredProject project, UnaryOperator<String> completedAt) throws IOException {
        Path directory = Files.createDirectories(home.resolve("cli-index-history"));
        Files.writeString(directory.resolve(project.id() + PROPERTIES),
                "snapshotId=" + SNAPSHOT + "\nproviderId=scip-java\nproviderVersion=1\ncompletedAt="
                        + completedAt.apply("2026-09-30T08:00:00Z") + "\n", StandardCharsets.UTF_8);
    }

    private static Map<String, ProjectInspectionService.ProjectView> byId(ProjectInspectionService.Inventory inventory) {
        return inventory.projects().stream().collect(Collectors.toMap(
                ProjectInspectionService.ProjectView::id, view -> view));
    }

    private void assertPublic(DegradedEntry entry) {
        String text = entry.entry() + " " + entry.reason();
        assertFalse(text.contains(temp.toString()), text);
        assertFalse(PublicErrorMessages.looksSensitive(text), text);
    }

    /** Un détecteur qui échoue comme le parcours de fichiers quand un répertoire est illisible ({@code visitFileFailed}). */
    private static ProjectDiscoveryService discoveryFailingUnder(Path unreadableRoot) {
        List<ProjectDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.projectDetectors());
        detectors.add((projectRoot, directory, ignorePolicy) -> {
            if (projectRoot.equals(unreadableRoot.toAbsolutePath().normalize())) {
                throw new UncheckedIOException(new AccessDeniedException(directory.getFileName().toString()));
            }
            return false;
        });
        return new ProjectDiscoveryService(detectors, DefaultDiscoveryPlugins.buildSystemDetectors(),
                DefaultDiscoveryPlugins.sourceRootDetectors(), DefaultDiscoveryPlugins.languageDetectors());
    }

    @Test
    void aProjectWhoseRegistryEntryIsDamagedAppearsUnreadableAndIsCounted() throws IOException {
        registerProjects(3);
        corruptRegistryEntry(projects.getFirst());

        ProjectInspectionService.Inventory inventory = inspection(new ProjectDiscoveryService()).inventory();

        assertEquals(3, inventory.projects().size(), "the damaged project is a row, it does not vanish");
        ProjectInspectionService.ProjectView damaged = byId(inventory).get(projects.getFirst().id().toString());
        assertEquals(UNREADABLE, damaged.indexState());
        assertFalse(damaged.rootAvailable());
        assertEquals(List.of(projects.getFirst().id().toString()),
                inventory.degraded().stream().map(DegradedEntry::entry).toList());
        inventory.degraded().forEach(this::assertPublic);
        assertEquals(NEVER_INDEXED, byId(inventory).get(projects.get(1).id().toString()).indexState());
        assertEquals(NEVER_INDEXED, byId(inventory).get(projects.get(2).id().toString()).indexState());
    }

    @Test
    void anUnreadableDirectoryUnderOneProjectRootDegradesThatProjectOnly() throws IOException {
        registerProjects(3);
        RegisteredProject failing = projects.get(1);

        ProjectInspectionService.Inventory inventory =
                inspection(discoveryFailingUnder(failing.rootPath())).inventory();

        assertEquals(3, inventory.projects().size());
        ProjectInspectionService.ProjectView row = byId(inventory).get(failing.id().toString());
        assertEquals(UNREADABLE, row.indexState());
        assertEquals(failing.displayName(), row.name(), "the registry still knows the name of the project");
        assertEquals(failing.rootPath().toString(), row.rootPath());
        assertEquals(1, inventory.degraded().size());
        assertEquals(failing.id().toString(), inventory.degraded().getFirst().entry());
        assertPublic(inventory.degraded().getFirst());
        assertEquals(NEVER_INDEXED, byId(inventory).get(projects.getFirst().id().toString()).indexState());
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void aReallyUnreadableSubdirectoryIsVisitFileFailedAndDegradesThatProjectOnly() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")), "root reads every directory");
        registerProjects(2);
        Path locked = Files.createDirectory(projects.getFirst().rootPath().resolve("locked"));
        Files.createFile(locked.resolve("inside.txt"));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            ProjectInspectionService.Inventory inventory = inspection(new ProjectDiscoveryService()).inventory();

            assertEquals(2, inventory.projects().size());
            assertEquals(UNREADABLE, byId(inventory).get(projects.getFirst().id().toString()).indexState());
            assertEquals(1, inventory.degraded().size());
            assertPublic(inventory.degraded().getFirst());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void anHistoryWithAnInvalidInstantDegradesThatProjectOnly() throws IOException {
        registerProjects(2);
        writeHistory(projects.getFirst(), valid -> "not-an-instant");
        writeHistory(projects.get(1), UnaryOperator.identity());

        ProjectInspectionService.Inventory inventory = inspection(new ProjectDiscoveryService()).inventory();

        assertEquals(2, inventory.projects().size());
        assertEquals(UNREADABLE, byId(inventory).get(projects.getFirst().id().toString()).indexState());
        assertEquals(NEVER_INDEXED, byId(inventory).get(projects.get(1).id().toString()).indexState());
        assertEquals(1, inventory.degraded().size());
        assertPublic(inventory.degraded().getFirst());
    }

    @Test
    void severalDamagesOfDifferentKindsAreAllCounted() throws IOException {
        registerProjects(4);
        corruptRegistryEntry(projects.get(0));
        writeHistory(projects.get(1), valid -> "not-an-instant");
        RegisteredProject failing = projects.get(2);

        ProjectInspectionService.Inventory inventory =
                inspection(discoveryFailingUnder(failing.rootPath())).inventory();

        assertEquals(4, inventory.projects().size());
        assertEquals(3, inventory.degraded().size(), "one degraded entry per damaged project");
        assertEquals(3, inventory.projects().stream().filter(view -> UNREADABLE.equals(view.indexState())).count());
        assertEquals(NEVER_INDEXED, byId(inventory).get(projects.get(3).id().toString()).indexState());
        inventory.degraded().forEach(this::assertPublic);
    }

    @Test
    void aHealthyInventoryReportsNothingDegradedAndTheSameRowsAsBefore() throws IOException {
        registerProjects(3);
        ProjectInspectionService service = inspection(new ProjectDiscoveryService());

        ProjectInspectionService.Inventory inventory = service.inventory();

        assertTrue(inventory.degraded().isEmpty(), "no degraded entry, no stray counter");
        assertEquals(3, inventory.projects().size());
        for (RegisteredProject project : projects) {
            assertEquals(service.view(project), byId(inventory).get(project.id().toString()));
        }
        assertEquals(inventory.projects(), service.listProjects());
    }

    @Test
    void theRowsStayInTheOrderOfTheirIdentifiers() throws IOException {
        registerProjects(4);
        corruptRegistryEntry(projects.get(2));

        List<String> ids = inspection(new ProjectDiscoveryService()).inventory().projects().stream()
                .map(ProjectInspectionService.ProjectView::id).toList();

        assertEquals(ids.stream().sorted().toList(), ids);
    }

    @Test
    void aRegistryThatCannotBeListedStillFailsAsAWhole() throws IOException {
        registerProjects(2);
        Path directory = home.resolve(REGISTRY).resolve("projects");
        for (RegisteredProject project : projects) Files.delete(directory.resolve(project.id() + PROPERTIES));
        Files.delete(directory);
        Files.writeString(directory, "not a directory");
        ProjectInspectionService service = inspection(new ProjectDiscoveryService());

        assertThrows(IOException.class, service::inventory);
    }

    @Test
    void anInterruptionWhileAssemblingAViewIsRethrownInsteadOfDegradingTheProject() throws IOException {
        registerProjects(2);
        List<ProjectDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.projectDetectors());
        detectors.add((projectRoot, directory, ignorePolicy) -> {
            Thread.currentThread().interrupt();
            throw new UncheckedIOException(new ClosedByInterruptException());
        });
        ProjectInspectionService service = inspection(new ProjectDiscoveryService(detectors,
                DefaultDiscoveryPlugins.buildSystemDetectors(), DefaultDiscoveryPlugins.sourceRootDetectors(),
                DefaultDiscoveryPlugins.languageDetectors()));
        try {
            assertThrows(ClosedByInterruptException.class, service::inventory);
            assertTrue(Thread.currentThread().isInterrupted(), "the interruption is still pending for the caller");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void inspectingOneDamagedProjectStillFailsSoOutOfScopeCommandsKeepTheirContract() throws IOException {
        registerProjects(2);
        writeHistory(projects.getFirst(), valid -> "not-an-instant");
        ProjectInspectionService service = inspection(new ProjectDiscoveryService());
        String identifier = projects.getFirst().id().toString();

        assertThrows(RuntimeException.class, () -> service.inspectProject(identifier));
    }
}
