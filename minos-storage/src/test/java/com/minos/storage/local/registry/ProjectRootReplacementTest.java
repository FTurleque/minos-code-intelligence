package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappings;
import com.minos.registry.RegisteredProject;
import com.minos.registry.RegisteredWorkspace;
import com.minos.storage.home.ProjectPathMappingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Replacing the project root of a MINOS home: every registered project is rebased under the new root, or detached
 * (kept with its identity, never resolved to a folder of the new root), and a detached project comes back when a later
 * root contains it again.
 */
class ProjectRootReplacementTest {

    @TempDir
    Path temp;

    private Path home;
    private Path oldRoot;
    private Path newRoot;
    private ProjectPathMapping oldMapping;
    private ProjectPathMapping newMapping;

    @BeforeEach
    void layout() throws IOException {
        home = Files.createDirectories(temp.resolve("home"));
        oldRoot = Files.createDirectories(temp.resolve("old")).toRealPath();
        newRoot = Files.createDirectories(temp.resolve("new")).toRealPath();
        oldMapping = ProjectPathMapping.singleRoot(oldRoot.toString(), "/workspace/projects");
        newMapping = ProjectPathMapping.singleRoot(newRoot.toString(), "/workspace/projects");
        new ProjectPathMappingStore(home).save(oldMapping);
    }

    @Test
    void aProjectOutsideTheNewRootIsDetachedNotRepointed() throws Exception {
        RegisteredProject app = register(Files.createDirectories(oldRoot.resolve("app")), "App");
        // A different repository with the same relative path exists under the new root: it must not inherit the index.
        Path homonym = Files.createDirectories(newRoot.resolve("app"));

        ProjectPathMappings.Replacement replacement = new LocalProjectPathMappings(home).replace(newMapping);

        assertEquals(new ProjectPathMappings.Replacement(0, 0, 1), replacement);
        assertEquals(newMapping, new ProjectPathMappingStore(home).loadOptional().orElseThrow());
        RegisteredProject detached = reopen().findProject(app.id()).orElseThrow();
        assertEquals("App", detached.displayName());
        assertNotEquals(homonym, detached.rootPath());
        assertFalse(detached.rootPath().startsWith(newRoot));
        assertFalse(Files.exists(detached.rootPath()));
        assertEquals(1, backupsOf(app.id()).size(), "the entry is saved before it is rewritten");
    }

    @Test
    void aProjectUnderTheNewRootIsRebased() throws Exception {
        Path team = Files.createDirectories(oldRoot.resolve("team"));
        RegisteredProject app = register(Files.createDirectories(team.resolve("app")), "App");
        ProjectPathMapping teamMapping = ProjectPathMapping.singleRoot(team.toString(), "/workspace/projects");

        ProjectPathMappings.Replacement replacement = new LocalProjectPathMappings(home).replace(teamMapping);

        assertEquals(new ProjectPathMappings.Replacement(1, 0, 0), replacement);
        RegisteredProject rebased = reopen().findProject(app.id()).orElseThrow();
        assertEquals(team.resolve("app").toAbsolutePath().normalize(), rebased.rootPath());
        String entry = Files.readString(entryOf(app.id()), StandardCharsets.UTF_8);
        assertTrue(entry.contains("rootRelativePath=app"), entry);
    }

    @Test
    void returningToTheOldRootReattachesADetachedProject() throws Exception {
        Path appRoot = Files.createDirectories(oldRoot.resolve("app"));
        RegisteredProject app = register(appRoot, "App");
        LocalProjectPathMappings mappings = new LocalProjectPathMappings(home);
        mappings.replace(newMapping);

        ProjectPathMappings.Replacement back = mappings.replace(oldMapping);

        assertEquals(new ProjectPathMappings.Replacement(0, 1, 0), back);
        assertEquals(appRoot.toAbsolutePath().normalize(), reopen().findProject(app.id()).orElseThrow().rootPath());
    }

    @Test
    void replacingWithTheSameRootReattachesWhatAnInterruptedReplacementLeftDetached() throws Exception {
        Path appRoot = Files.createDirectories(newRoot.resolve("app"));
        RegisteredProject app;
        {
            ProjectPathMappingStore store = new ProjectPathMappingStore(home);
            store.save(newMapping);
            app = register(appRoot, "App");
            // State left by an interruption after the entry was detached and the mapping saved.
            Path entry = entryOf(app.id());
            String detached = Files.readString(entry, StandardCharsets.UTF_8)
                    .replace("rootRelativePath=app", "detachedHostRoot=" + escaped(appRoot.toString()));
            Files.writeString(entry, detached, StandardCharsets.UTF_8);
        }
        assertFalse(Files.exists(reopen().findProject(app.id()).orElseThrow().rootPath()));

        ProjectPathMappings.Replacement resumed = new LocalProjectPathMappings(home).replace(newMapping);

        assertEquals(new ProjectPathMappings.Replacement(0, 1, 0), resumed);
        assertEquals(appRoot.toAbsolutePath().normalize(), reopen().findProject(app.id()).orElseThrow().rootPath());
    }

    @Test
    void theSameMappingRewritesNoAttachedEntry() throws Exception {
        RegisteredProject app = register(Files.createDirectories(oldRoot.resolve("app")), "App");
        byte[] before = Files.readAllBytes(entryOf(app.id()));

        ProjectPathMappings.Replacement replacement = new LocalProjectPathMappings(home).replace(oldMapping);

        assertEquals(new ProjectPathMappings.Replacement(0, 0, 0), replacement);
        assertArrayEquals(before, Files.readAllBytes(entryOf(app.id())));
        assertTrue(backupsOf(app.id()).isEmpty());
    }

    @Test
    void aDetachedProjectStaysDetachedWhenItsEntryIsRewritten() throws Exception {
        RegisteredProject app = register(Files.createDirectories(oldRoot.resolve("app")), "App");
        new LocalProjectPathMappings(home).replace(newMapping);
        LocalProjectRegistry registry = reopen();
        RegisteredWorkspace workspace = registry.createWorkspace("team");

        registry.assignProjectToWorkspace(app.id(), workspace.id());

        String entry = Files.readString(entryOf(app.id()), StandardCharsets.UTF_8);
        assertTrue(entry.contains("detachedHostRoot="), entry);
        assertFalse(entry.contains("rootRelativePath="), entry);
        assertEquals(new ProjectPathMappings.Replacement(0, 1, 0), new LocalProjectPathMappings(home).replace(oldMapping));
    }

    @Test
    void anUnreadableEntryRefusesTheReplacementAndWritesNothing() throws Exception {
        RegisteredProject app = register(Files.createDirectories(oldRoot.resolve("app")), "App");
        Path broken = home.resolve("registry/projects/" + UUID.randomUUID() + ".properties");
        Files.writeString(broken, "id=not-a-uuid\n", StandardCharsets.UTF_8);
        String before = Files.readString(entryOf(app.id()), StandardCharsets.UTF_8);

        IOException refusal = assertThrows(IOException.class, () -> new LocalProjectPathMappings(home).replace(newMapping));

        assertTrue(refusal.getMessage().contains("1 registry entry"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(oldRoot.toString()), refusal.getMessage());
        assertEquals(oldMapping, new ProjectPathMappingStore(home).loadOptional().orElseThrow());
        assertEquals(before, Files.readString(entryOf(app.id()), StandardCharsets.UTF_8));
        assertTrue(backupsOf(app.id()).isEmpty());
    }

    @Test
    void postgresqlStorageRefusesTheReplacement() throws Exception {
        Files.createDirectories(home.resolve("config"));
        Files.writeString(home.resolve("config/minos.properties"), "minos.storage.backend=postgresql\n",
                StandardCharsets.UTF_8);

        IOException refusal = assertThrows(IOException.class, () -> new LocalProjectPathMappings(home).replace(newMapping));

        assertTrue(refusal.getMessage().contains("PostgreSQL"), refusal.getMessage());
        assertEquals(oldMapping, new ProjectPathMappingStore(home).loadOptional().orElseThrow());
    }

    @Test
    void withoutASavedMappingTheMappingIsCreated() throws Exception {
        Path otherHome = Files.createDirectories(temp.resolve("other-home"));

        assertEquals(new ProjectPathMappings.Replacement(0, 0, 0), new LocalProjectPathMappings(otherHome).replace(newMapping));
        assertEquals(newMapping, new ProjectPathMappingStore(otherHome).loadOptional().orElseThrow());
    }

    @Test
    void loadAndSaveAreThoseOfTheFileStore() throws Exception {
        LocalProjectPathMappings mappings = new LocalProjectPathMappings(home);

        mappings.save(newMapping);

        assertEquals(newMapping, mappings.loadOptional().orElseThrow());
        assertEquals(newMapping, new ProjectPathMappingStore(home).loadOptional().orElseThrow());
    }

    private RegisteredProject register(Path root, String name) throws IOException {
        return new LocalProjectRegistry(home.resolve("registry"), oldMappingOrCurrent(),
                ProjectPathMapping.RuntimeLocation.NATIVE).registerProject(root, name);
    }

    private ProjectPathMapping oldMappingOrCurrent() throws IOException {
        return new ProjectPathMappingStore(home).loadOptional().orElseThrow();
    }

    private LocalProjectRegistry reopen() throws IOException {
        return new LocalProjectRegistry(home.resolve("registry"), oldMappingOrCurrent(),
                ProjectPathMapping.RuntimeLocation.NATIVE);
    }

    private Path entryOf(UUID id) {
        return home.resolve("registry/projects/" + id + ".properties");
    }

    private List<Path> backupsOf(UUID id) throws IOException {
        try (Stream<Path> files = Files.list(home.resolve("registry/projects"))) {
            return files.filter(path -> path.getFileName().toString().startsWith(id + ".properties.")
                    && path.getFileName().toString().endsWith(".root-replacement.bak")).toList();
        }
    }

    /** Properties escaping of a path value (a Windows path holds backslashes and a colon). */
    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace(":", "\\:");
    }
}
