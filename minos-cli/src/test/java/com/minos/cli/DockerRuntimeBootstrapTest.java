package com.minos.cli;

import com.minos.cli.command.FindSymbolCommand;
import com.minos.domain.registry.ProjectMount;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.storage.home.ProjectPathMappingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerRuntimeBootstrapTest {

    @Test
    void createsPortableMappingAndIsIdempotent(@TempDir Path home) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int first = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "N:/workspace-dev", "/workspace/projects"},
                output, error);
        int second = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "N:\\workspace-dev", "/workspace/projects/"},
                output, error);

        assertEquals(FindSymbolCommand.SUCCESS, first);
        assertEquals(FindSymbolCommand.SUCCESS, second);
        assertEquals("", error.toString());
        assertTrue(output.toString().contains("mapping created"));
        assertTrue(output.toString().contains("already matches"));
        assertEquals(ProjectPathMapping.singleRoot("N:/workspace-dev", "/workspace/projects"),
                new ProjectPathMappingStore(home).loadOptional().orElseThrow());
    }

    @Test
    void refusesImplicitMappingReplacement(@TempDir Path home) throws Exception {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(ProjectPathMapping.singleRoot("N:/workspace-dev", "/workspace/projects"));
        StringBuilder error = new StringBuilder();

        int exitCode = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "D:/other-projects", "/workspace/projects"},
                new StringBuilder(), error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exitCode);
        assertTrue(error.toString().contains("refusing to replace"));
        assertTrue(error.toString().contains("--replace"), error.toString());
        assertEquals("N:/workspace-dev", store.loadOptional().orElseThrow().hostRoot());
    }

    @Test
    void replacesTheMappingWhenAskedExplicitly(@TempDir Path home) throws Exception {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(ProjectPathMapping.singleRoot("N:/workspace-dev", "/workspace/projects"));
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "--replace", "D:/other-projects", "/workspace/projects"},
                output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals("", error.toString());
        assertTrue(output.toString().contains("mapping replaced"), output.toString());
        assertTrue(output.toString().contains("detached: 0"), output.toString());
        assertEquals("D:/other-projects", store.loadOptional().orElseThrow().hostRoot());
    }

    @Test
    void replaceOnAFreshHomeCreatesTheMapping(@TempDir Path home) throws Exception {
        StringBuilder output = new StringBuilder();

        int exitCode = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "--replace", "N:/workspace-dev", "/workspace/projects"},
                output, new StringBuilder());
        int again = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "--replace", "N:/workspace-dev", "/workspace/projects"},
                output, new StringBuilder());

        assertEquals(FindSymbolCommand.SUCCESS, exitCode);
        assertEquals(FindSymbolCommand.SUCCESS, again);
        assertTrue(output.toString().contains("mapping created"), output.toString());
        assertTrue(output.toString().contains("already matches"), output.toString());
    }

    @Test
    void rejectsAnUnknownOption(@TempDir Path home) throws Exception {
        StringBuilder error = new StringBuilder();

        int exitCode = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "--force", "D:/other-projects", "/workspace/projects"},
                new StringBuilder(), error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, exitCode);
        assertTrue(error.toString().contains("[--replace]"), error.toString());
    }

    @Test
    void rejectsInvalidInvocation(@TempDir Path home) throws Exception {
        StringBuilder error = new StringBuilder();

        int exitCode = DockerRuntimeBootstrap.run(home, new String[]{"configure-project-paths"},
                new StringBuilder(), error);

        assertEquals(FindSymbolCommand.USAGE_ERROR, exitCode);
        assertTrue(error.toString().contains("expected configure-project-paths"));
    }

    private static int bootstrap(Path home, StringBuilder output, StringBuilder error, String... arguments)
            throws Exception {
        return DockerRuntimeBootstrap.run(home, arguments, output, error);
    }

    @Test
    void initCreatesAnEmptyTableOnceAndListsItAsJson(@TempDir Path home) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, bootstrap(home, output, error, "init-project-mounts", "/workspace/projects"));
        assertEquals(FindSymbolCommand.SUCCESS, bootstrap(home, output, error, "init-project-mounts", "/workspace/projects"));
        StringBuilder listing = new StringBuilder();
        assertEquals(FindSymbolCommand.SUCCESS,
                bootstrap(home, listing, error, "list-project-mounts", "--format", "json"));

        assertEquals("", error.toString());
        assertTrue(output.toString().contains("table created"), output.toString());
        assertTrue(output.toString().contains("already present"), output.toString());
        assertEquals("{\"formatVersion\":2,\"containerRoot\":\"/workspace/projects\",\"mounts\":[]}\n",
                listing.toString());
    }

    @Test
    void listWithoutMappingSaysFormatZero(@TempDir Path home) throws Exception {
        StringBuilder listing = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS,
                bootstrap(home, listing, new StringBuilder(), "list-project-mounts", "--format", "json"));

        assertEquals("{\"formatVersion\":0,\"containerRoot\":null,\"mounts\":[]}\n", listing.toString());
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                bootstrap(home, new StringBuilder(), new StringBuilder(), "list-project-mounts"));
    }

    @Test
    void addAndRemoveAMountChangeTheTable(@TempDir Path home) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        bootstrap(home, output, error, "init-project-mounts", "/workspace/projects");

        assertEquals(FindSymbolCommand.SUCCESS,
                bootstrap(home, output, error, "add-project-mount", "app", "D:\\src\\it's #1:app"));
        StringBuilder listing = new StringBuilder();
        bootstrap(home, listing, error, "list-project-mounts", "--format", "json");
        assertEquals("{\"formatVersion\":2,\"containerRoot\":\"/workspace/projects\",\"mounts\":"
                + "[{\"name\":\"app\",\"path\":\"app\",\"hostRoot\":\"D:/src/it's #1:app\"}]}\n", listing.toString());

        assertEquals(FindSymbolCommand.SUCCESS, bootstrap(home, output, error, "remove-project-mount", "app"));
        assertEquals("", error.toString());
        assertTrue(output.toString().contains("mount added: app (1 of 64 mounts)"), output.toString());
        assertTrue(output.toString().contains("mount removed: app"), output.toString());
        assertTrue(new ProjectPathMappingStore(home).loadOptional().orElseThrow().mounts().isEmpty());
    }

    @Test
    void aRefusedAddLeavesTheTableAndNamesNoPath(@TempDir Path home) throws Exception {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(ProjectPathMapping.mounted("/workspace/projects", List.of(new ProjectMount("app", "D:/src/app"))));
        byte[] before = Files.readAllBytes(store.file());
        StringBuilder error = new StringBuilder();

        assertEquals(FindSymbolCommand.USAGE_ERROR,
                bootstrap(home, new StringBuilder(), error, "add-project-mount", "App", "D:/autre"));
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                bootstrap(home, new StringBuilder(), error, "add-project-mount", "sub", "D:/src/app/sub"));
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                bootstrap(home, new StringBuilder(), error, "add-project-mount", "rel", "relative/folder"));
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                bootstrap(home, new StringBuilder(), error, "remove-project-mount", "absent"));

        assertArrayEquals(before, Files.readAllBytes(store.file()));
        assertFalse(error.toString().contains("D:/"), error.toString());
        assertFalse(error.toString().contains("relative/folder"), error.toString());
    }

    @Test
    void mountCommandsAreRefusedOnASingleRootMapping(@TempDir Path home) throws Exception {
        new ProjectPathMappingStore(home).save(ProjectPathMapping.singleRoot("N:/workspace-dev", "/workspace/projects"));
        StringBuilder error = new StringBuilder();

        assertEquals(FindSymbolCommand.EXECUTION_ERROR,
                bootstrap(home, new StringBuilder(), error, "add-project-mount", "app", "D:/src/app"));
        assertEquals(FindSymbolCommand.EXECUTION_ERROR,
                bootstrap(home, new StringBuilder(), error, "init-project-mounts", "/workspace/projects"));

        assertTrue(error.toString().contains("migrate-project-mounts"), error.toString());
        assertFalse(error.toString().contains("N:/"), error.toString());
    }

    @Test
    void migrationReplacesTheSingleRootByOneMountPerProject(@TempDir Path temp) throws Exception {
        Path home = Files.createDirectories(temp.resolve("home"));
        Path root = Files.createDirectories(temp.resolve("root")).toRealPath();
        ProjectPathMapping single = ProjectPathMapping.singleRoot(root.toString(), "/workspace/projects");
        new ProjectPathMappingStore(home).save(single);
        com.minos.storage.local.registry.LocalProjectRegistry registry = new com.minos.storage.local.registry
                .LocalProjectRegistry(home.resolve("registry"), single, ProjectPathMapping.RuntimeLocation.NATIVE);
        registry.registerProject(Files.createDirectories(root.resolve("a")), "A");
        registry.registerProject(Files.createDirectories(root.resolve("team").resolve("b")), "Team B");
        registry.registerProject(root, "Whole root");

        StringBuilder plan = new StringBuilder();
        assertEquals(FindSymbolCommand.SUCCESS,
                bootstrap(home, plan, new StringBuilder(), "migrate-project-mounts", "--dry-run"));
        assertTrue(plan.toString().startsWith("{\"outcome\":\"PLANNED\""), plan.toString());
        assertEquals(1, new ProjectPathMappingStore(home).loadFormatVersion().orElseThrow());

        StringBuilder output = new StringBuilder();
        assertEquals(FindSymbolCommand.SUCCESS,
                bootstrap(home, output, new StringBuilder(), "migrate-project-mounts", "--skip", "team/b"));

        assertTrue(output.toString().startsWith("{\"outcome\":\"MIGRATED\",\"mounts\":[{\"name\":\"a\""),
                output.toString());
        assertTrue(output.toString().contains("{\"project\":\"Team B\",\"reason\":\"REFUSED\"}"), output.toString());
        assertTrue(output.toString().contains("{\"project\":\"Whole root\",\"reason\":\"ROOT\"}"), output.toString());
        ProjectPathMapping table = new ProjectPathMappingStore(home).loadOptional().orElseThrow();
        assertEquals(List.of(new ProjectMount("a", root.resolve("a").toString())), table.mounts());
        assertEquals(FindSymbolCommand.USAGE_ERROR,
                bootstrap(home, new StringBuilder(), new StringBuilder(), "migrate-project-mounts", "--skip"));
    }

    @Test
    void replaceOnATableIsRefusedAndNamesAddProject(@TempDir Path home) throws Exception {
        new ProjectPathMappingStore(home).save(ProjectPathMapping.mounted("/workspace/projects", List.of()));
        StringBuilder error = new StringBuilder();

        IOException refusal = assertThrows(IOException.class, () -> DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "--replace", "N:/workspace-dev", "/workspace/projects"},
                new StringBuilder(), new StringBuilder()));
        int configure = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "N:/workspace-dev", "/workspace/projects"},
                new StringBuilder(), error);

        assertTrue(refusal.getMessage().contains("AddProject"), refusal.getMessage());
        assertEquals(FindSymbolCommand.EXECUTION_ERROR, configure);
        assertTrue(error.toString().contains("AddProject"), error.toString());
        assertTrue(new ProjectPathMappingStore(home).loadOptional().orElseThrow().mountTable());
    }

    @Test
    void anUnknownCommandIsAUsageError(@TempDir Path home) throws Exception {
        StringBuilder error = new StringBuilder();

        assertEquals(FindSymbolCommand.USAGE_ERROR, bootstrap(home, new StringBuilder(), error, "mount-everything"));
        assertEquals(FindSymbolCommand.USAGE_ERROR, bootstrap(home, new StringBuilder(), error));
        assertTrue(error.toString().contains("add-project-mount"), error.toString());
    }
}
