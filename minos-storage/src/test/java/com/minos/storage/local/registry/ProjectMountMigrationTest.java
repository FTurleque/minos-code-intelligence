package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings.RegisteredRoot;
import com.minos.registry.RegisteredProject;
import com.minos.storage.home.ProjectPathMappingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The local side of the migration from a single project root to a mount table: the registry is listed by relative
 * root without any write, and the mapping file is replaced only after a backup of the single root file.
 */
class ProjectMountMigrationTest {

    private static final String CONTAINER = "/workspace/projects";

    @TempDir
    Path temp;

    private Path home;
    private Path root;
    private ProjectPathMapping single;

    @BeforeEach
    void layout() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        root = Files.createDirectories(temp.resolve("root")).toRealPath();
        single = ProjectPathMapping.singleRoot(root.toString(), CONTAINER);
        new ProjectPathMappingStore(home).save(single);
    }

    @Test
    void theRegistryIsListedByRelativeRootWithoutWriting() throws Exception {
        register(Files.createDirectories(root.resolve("a")), "A");
        register(Files.createDirectories(root.resolve("team").resolve("b")), "Team B");
        register(root, "Whole root");
        List<byte[]> before = entries();

        List<RegisteredRoot> roots = new LocalProjectPathMappings(home).registeredRoots();

        assertEquals(3, roots.size());
        assertTrue(roots.contains(new RegisteredRoot("A", Optional.of("a"))));
        assertTrue(roots.contains(new RegisteredRoot("Team B", Optional.of("team/b"))));
        assertTrue(roots.contains(new RegisteredRoot("Whole root", Optional.of("."))));
        assertBytesEqual(before, entries());
    }

    @Test
    void aDetachedEntryIsListedWithoutRoot() throws Exception {
        Path projects = Files.createDirectories(home.resolve("registry").resolve("projects"));
        UUID id = UUID.randomUUID();
        Files.writeString(projects.resolve(id + ".properties"),
                "id=" + id + "\ndisplayName=Isolated\nworkspaceId=\ncreatedAt=2026-01-01T00:00:00Z\n"
                        + "updatedAt=2026-01-01T00:00:00Z\ndetachedHostRoot=D\\:/old/isolated\n",
                StandardCharsets.UTF_8);

        assertEquals(List.of(new RegisteredRoot("Isolated", Optional.empty())),
                new LocalProjectPathMappings(home).registeredRoots());
    }

    @Test
    void anUnreadableEntryRefusesTheListingWithoutAPath() throws Exception {
        register(Files.createDirectories(root.resolve("a")), "A");
        Path broken = home.resolve("registry/projects/" + UUID.randomUUID() + ".properties");
        Files.writeString(broken, "id=not-a-uuid\n", StandardCharsets.UTF_8);

        IOException refusal = assertThrows(IOException.class, () -> new LocalProjectPathMappings(home).registeredRoots());

        assertTrue(refusal.getMessage().contains("1 registry entry"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(root.toString()), refusal.getMessage());
    }

    @Test
    void theMigrationSavesTheSingleRootFileThenWritesTheTable() throws Exception {
        RegisteredProject app = register(Files.createDirectories(root.resolve("a")), "A");
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        byte[] format1 = Files.readAllBytes(store.file());
        List<byte[]> before = entries();
        ProjectPathMapping table = ProjectPathMapping.mounted(CONTAINER,
                List.of(new ProjectMount("a", root.resolve("a").toString())));

        new LocalProjectPathMappings(home).migrateToTable(table);

        assertEquals(table, store.loadOptional().orElseThrow());
        List<Path> backups = backups();
        assertEquals(1, backups.size());
        assertArrayEquals(format1, Files.readAllBytes(backups.getFirst()));
        assertBytesEqual(before, entries());
        RegisteredProject reread = new LocalProjectRegistry(home.resolve("registry"), table,
                ProjectPathMapping.RuntimeLocation.DOCKER).listProjects().getFirst();
        assertEquals(app.id(), reread.id());
        assertEquals(CONTAINER + "/a", reread.rootPath().toString().replace('\\', '/').replaceFirst("^[A-Za-z]:", ""));
    }

    @Test
    void theMigrationIsRefusedOnATableOrWithoutMappingAndForAnotherContainerRoot() throws Exception {
        ProjectPathMapping table = ProjectPathMapping.mounted(CONTAINER, List.of());
        ProjectPathMapping elsewhere = ProjectPathMapping.mounted("/elsewhere", List.of());
        LocalProjectPathMappings mappings = new LocalProjectPathMappings(home);

        assertThrows(IOException.class, () -> mappings.migrateToTable(elsewhere));
        assertThrows(IllegalArgumentException.class, () -> mappings.migrateToTable(single));
        mappings.migrateToTable(table);
        assertThrows(IOException.class, () -> mappings.migrateToTable(table));
        assertThrows(IOException.class, mappings::registeredRoots);

        Path otherHome = Files.createDirectories(temp.resolve("other-home"));
        assertThrows(IOException.class, () -> new LocalProjectPathMappings(otherHome).migrateToTable(table));
    }

    @Test
    void aReplacementIsRefusedOnceTheTableIsSaved() throws Exception {
        LocalProjectPathMappings mappings = new LocalProjectPathMappings(home);
        mappings.migrateToTable(ProjectPathMapping.mounted(CONTAINER, List.of()));

        IOException refusal = assertThrows(IOException.class, () -> mappings.replace(single));

        assertTrue(refusal.getMessage().contains("AddProject"), refusal.getMessage());
    }

    private RegisteredProject register(Path project, String name) throws IOException {
        return new LocalProjectRegistry(home.resolve("registry"), single, ProjectPathMapping.RuntimeLocation.NATIVE)
                .registerProject(project, name);
    }

    private List<byte[]> entries() throws IOException {
        List<byte[]> contents = new ArrayList<>();
        try (Stream<Path> files = Files.list(home.resolve("registry/projects"))) {
            for (Path file : files.sorted().toList()) contents.add(Files.readAllBytes(file));
        }
        return contents;
    }

    private List<Path> backups() throws IOException {
        try (Stream<Path> files = Files.list(home.resolve("runtime"))) {
            return files.filter(path -> path.getFileName().toString().endsWith(
                    LocalProjectPathMappings.FORMAT1_BACKUP_SUFFIX)).toList();
        }
    }

    private static void assertBytesEqual(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) assertArrayEquals(expected.get(index), actual.get(index));
    }
}
