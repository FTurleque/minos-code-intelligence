package com.minos.cli;

import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappingStore;
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
        assertEquals("N:/workspace-dev", store.loadOptional().orElseThrow().hostRoot());
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
