package com.minos.application;

import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import com.minos.registry.ProjectPathMappings.RegisteredRoot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectPathMappingServiceTest {

    private static final ProjectPathMapping MAPPING = ProjectPathMapping.singleRoot("N:/workspace-dev", "/workspace/projects");
    private static final ProjectPathMapping EMPTY = ProjectPathMapping.mounted("/workspace/projects", List.of());

    private static ProjectPathMappingService service(RecordingMappings store) {
        return new ProjectPathMappingService(store, store::registeredRoots);
    }

    @Test
    void createsTheMappingWhenNoneExists() throws Exception {
        RecordingMappings store = new RecordingMappings(null);

        assertEquals(ProjectPathMappingService.Outcome.CREATED, service(store).configure(MAPPING));
        assertEquals(List.of(MAPPING), store.saved);
    }

    @Test
    void acceptsTheSameMappingWithoutWriting() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.UNCHANGED, service(store)
                .configure(ProjectPathMapping.singleRoot("N:\\workspace-dev", "/workspace/projects/")));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void refusesToReplaceAnotherMappingAndWritesNothing() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.CONFLICT, service(store)
                .configure(ProjectPathMapping.singleRoot("D:/other-projects", "/workspace/projects")));
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void replaceCreatesTheMappingWhenNoneExists() throws Exception {
        RecordingMappings store = new RecordingMappings(null);

        ProjectPathMappingService.Result result = service(store).replace(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.CREATED, result.outcome());
        assertEquals(new ProjectPathMappings.Replacement(0, 0, 0), result.replacement());
        assertEquals(List.of(MAPPING), store.saved);
        assertTrue(store.replaced.isEmpty());
    }

    @Test
    void replaceReplacesAnotherMappingAndReportsTheProjects() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);
        ProjectPathMapping other = ProjectPathMapping.singleRoot("D:/other-projects", "/workspace/projects");

        ProjectPathMappingService.Result result = service(store).replace(other);

        assertEquals(ProjectPathMappingService.Outcome.REPLACED, result.outcome());
        assertEquals(RecordingMappings.REPORT, result.replacement());
        assertEquals(List.of(other), store.replaced);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void replaceWithTheSameMappingIsUnchangedAndStillReattaches() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);

        ProjectPathMappingService.Result result = service(store).replace(MAPPING);

        assertEquals(ProjectPathMappingService.Outcome.UNCHANGED, result.outcome());
        assertEquals(List.of(MAPPING), store.replaced);
    }

    @Test
    void replaceIsRefusedOnATableAndNamesAddProject() throws Exception {
        RecordingMappings store = new RecordingMappings(EMPTY);

        IOException refusal = assertThrows(IOException.class, () -> service(store).replace(MAPPING));

        assertTrue(refusal.getMessage().contains("AddProject"), refusal.getMessage());
        assertTrue(store.replaced.isEmpty());
        assertEquals(ProjectPathMappingService.Outcome.CONFLICT, service(store).configure(MAPPING));
    }

    @Test
    void initializeCreatesAnEmptyTableOnceAndRefusesASingleRoot() throws Exception {
        RecordingMappings none = new RecordingMappings(null);
        assertEquals(ProjectPathMappingService.Outcome.CREATED, service(none).initialize("/workspace/projects"));
        assertEquals(List.of(EMPTY), none.saved);

        RecordingMappings table = new RecordingMappings(EMPTY);
        assertEquals(ProjectPathMappingService.Outcome.UNCHANGED, service(table).initialize("/workspace/projects/"));
        assertEquals(ProjectPathMappingService.Outcome.CONFLICT, service(table).initialize("/elsewhere"));
        assertTrue(table.saved.isEmpty());

        ProjectPathMappingService singleRoot = service(new RecordingMappings(MAPPING));
        IOException refusal = assertThrows(IOException.class, () -> singleRoot.initialize("/workspace/projects"));
        assertTrue(refusal.getMessage().contains("migrate-project-mounts"), refusal.getMessage());
    }

    @Test
    void addAndRemoveAMountSaveTheTable() throws Exception {
        RecordingMappings store = new RecordingMappings(EMPTY);

        ProjectPathMapping added = service(store).addMount("app", "D:/src/app");

        assertEquals(List.of(new ProjectMount("app", "D:/src/app")), added.mounts());
        assertEquals(List.of(added), store.saved);
        assertEquals(EMPTY, service(new RecordingMappings(added)).removeMount("app"));
    }

    @Test
    void anInvalidAddOrAnUnknownRemovalWritesNothing() {
        RecordingMappings store = new RecordingMappings(
                ProjectPathMapping.mounted("/workspace/projects", List.of(new ProjectMount("app", "D:/src/app"))));
        ProjectPathMappingService service = service(store);

        assertThrows(IllegalArgumentException.class, () -> service.addMount("App", "D:/other"));
        assertThrows(IllegalArgumentException.class, () -> service.addMount("sub", "D:/src/app/sub"));
        assertThrows(IllegalArgumentException.class, () -> service.removeMount("absent"));
        assertTrue(store.saved.isEmpty());
        ProjectPathMappingService noTable = service(new RecordingMappings(null));
        assertThrows(IOException.class, () -> noTable.addMount("app", "D:/a"));
        ProjectPathMappingService singleRoot = service(new RecordingMappings(MAPPING));
        assertThrows(IOException.class, () -> singleRoot.removeMount("app"));
    }

    @Test
    void migrationMountsEachProjectOnItsContainerPathAndSkipsTheRootAndDetachedOnes() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);
        store.roots.addAll(List.of(
                new RegisteredRoot("Team B", Optional.of("team/b")),
                new RegisteredRoot("A", Optional.of("a")),
                new RegisteredRoot("A core", Optional.of("a/core")),
                new RegisteredRoot("Whole root", Optional.of(".")),
                new RegisteredRoot("Isolated", Optional.empty())));

        ProjectPathMappingService.Migration migration = service(store).migrate(Set.of(), false);

        assertEquals(ProjectPathMappingService.Outcome.MIGRATED, migration.outcome());
        assertEquals(List.of(
                new ProjectMount("a", "N:/workspace-dev/a"),
                new ProjectMount("team/b", "N:/workspace-dev/team/b")), migration.table().mounts());
        assertEquals(List.of(
                new ProjectPathMappingService.Skipped("Isolated", ProjectPathMappingService.SkipReason.DETACHED),
                new ProjectPathMappingService.Skipped("Whole root", ProjectPathMappingService.SkipReason.ROOT)),
                migration.skipped());
        assertEquals(List.of(migration.table()), store.migrated);
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void aDryRunWritesNothingAndARefusedFolderIsNotMounted() throws Exception {
        RecordingMappings store = new RecordingMappings(MAPPING);
        store.roots.addAll(List.of(
                new RegisteredRoot("A", Optional.of("a")),
                new RegisteredRoot("Cache", Optional.of("AppData/cache"))));

        ProjectPathMappingService.Migration plan = service(store).migrate(Set.of("appdata/cache"), true);

        assertEquals(ProjectPathMappingService.Outcome.PLANNED, plan.outcome());
        assertEquals(List.of(new ProjectMount("a", "N:/workspace-dev/a")), plan.table().mounts());
        assertEquals(List.of(new ProjectPathMappingService.Skipped(
                "Cache", ProjectPathMappingService.SkipReason.REFUSED)), plan.skipped());
        assertTrue(store.migrated.isEmpty());
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void anUnreadableRegistryRefusesTheMigrationBeforeAnyWrite() {
        RecordingMappings store = new RecordingMappings(MAPPING);
        store.unreadable = true;
        ProjectPathMappingService service = service(store);

        assertThrows(IOException.class, () -> service.migrate(Set.of(), false));
        assertTrue(store.migrated.isEmpty());
        assertTrue(store.saved.isEmpty());
    }

    @Test
    void migrationLeavesATableAsItIsAndCreatesAnEmptyTableWithoutMapping() throws Exception {
        RecordingMappings table = new RecordingMappings(EMPTY);
        assertEquals(ProjectPathMappingService.Outcome.UNCHANGED, service(table).migrate(Set.of(), false).outcome());
        assertTrue(table.saved.isEmpty());

        RecordingMappings none = new RecordingMappings(null);
        assertEquals(ProjectPathMappingService.Outcome.CREATED, service(none).migrate(Set.of(), false).outcome());
        assertEquals(List.of(EMPTY), none.saved);

        RecordingMappings dry = new RecordingMappings(null);
        assertEquals(ProjectPathMappingService.Outcome.PLANNED, service(dry).migrate(Set.of(), true).outcome());
        assertTrue(dry.saved.isEmpty());
    }

    @Test
    void moreProjectsThanTheMountCapRefuseTheMigration() {
        RecordingMappings store = new RecordingMappings(MAPPING);
        for (int index = 0; index <= ProjectPathMapping.MAX_MOUNTS; index++) {
            store.roots.add(new RegisteredRoot("P" + index, Optional.of("p" + index)));
        }
        ProjectPathMappingService service = service(store);

        assertThrows(IllegalArgumentException.class, () -> service.migrate(Set.of(), false));
        assertTrue(store.migrated.isEmpty());
    }

    @Test
    void projectsUnderAMountAreNamed() throws Exception {
        RecordingMappings store = new RecordingMappings(EMPTY);
        store.roots.addAll(List.of(
                new RegisteredRoot("App", Optional.of("app")),
                new RegisteredRoot("App core", Optional.of("app/core")),
                new RegisteredRoot("Apple", Optional.of("apple")),
                new RegisteredRoot("Isolated", Optional.empty())));

        assertEquals(List.of("App", "App core"), service(store).projectsUnder("app"));
        assertEquals(List.of(), service(store).projectsUnder("lib"));
    }

    @Test
    void refusesMissingArguments() {
        assertThrows(NullPointerException.class, () -> new ProjectPathMappingService(null, List::of));
        RecordingMappings store = new RecordingMappings(null);
        assertThrows(NullPointerException.class, () -> new ProjectPathMappingService(store, null));
        ProjectPathMappingService service = service(store);
        assertThrows(NullPointerException.class, () -> service.configure(null));
        assertThrows(NullPointerException.class, () -> service.replace(null));
        assertThrows(NullPointerException.class, () -> service.migrate(null, false));
        assertThrows(NullPointerException.class, () -> ProjectPathMappingService.forHome(null));
    }

    @Test
    void forHomeFailsExplicitlyWithoutTheCompositionRoot() {
        // minos-bootstrap is not on this module's test classpath: the composer lookup fails closed.
        Path home = Path.of("home");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ProjectPathMappingService.forHome(home));
        assertEquals(MinosApplicationComposers.MISSING, failure.getMessage());
    }

    private static final class RecordingMappings implements ProjectPathMappings {
        private static final Replacement REPORT = new Replacement(1, 2, 3);
        private final ProjectPathMapping existing;
        private final List<ProjectPathMapping> saved = new ArrayList<>();
        private final List<ProjectPathMapping> replaced = new ArrayList<>();
        private final List<ProjectPathMapping> migrated = new ArrayList<>();
        private final List<RegisteredRoot> roots = new ArrayList<>();
        private boolean unreadable;

        private RecordingMappings(ProjectPathMapping existing) {
            this.existing = existing;
        }

        @Override
        public Optional<ProjectPathMapping> loadOptional() {
            return Optional.ofNullable(existing);
        }

        @Override
        public void save(ProjectPathMapping mapping) {
            saved.add(mapping);
        }

        @Override
        public Replacement replace(ProjectPathMapping desired) {
            replaced.add(desired);
            return REPORT;
        }

        @Override
        public List<RegisteredRoot> registeredRoots() throws IOException {
            if (unreadable) throw new IOException("1 registry entry is unreadable");
            return List.copyOf(roots);
        }

        @Override
        public void migrateToTable(ProjectPathMapping table) {
            migrated.add(table);
        }
    }
}
