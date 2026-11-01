package com.minos.cli;

import com.minos.cli.command.FindSymbolCommand;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.storage.home.ProjectPathMappingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(new ProjectPathMapping("N:/workspace-dev", "/workspace/projects"),
                new ProjectPathMappingStore(home).loadOptional().orElseThrow());
    }

    @Test
    void refusesImplicitMappingReplacement(@TempDir Path home) throws Exception {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(new ProjectPathMapping("N:/workspace-dev", "/workspace/projects"));
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
        store.save(new ProjectPathMapping("N:/workspace-dev", "/workspace/projects"));
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
}
