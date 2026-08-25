package com.minos.cli;

import com.minos.registry.ProjectPathMapping;
import com.minos.registry.ProjectPathMappingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PublicCliDiagnosticTest {

    @Test
    void launcherAndDockerBootstrapUseTheSharedPublicRedactionPolicy() {
        List<String> sensitive = List.of(
                "cannot open /home/private-user/.minos/token",
                "cannot open C:\\Users\\private-user\\.minos\\token",
                "jdbc:postgresql://user:password@db.example/minos",
                "token=super-secret",
                "Authorization: Bearer super-secret");
        for (String detail : sensitive) {
            assertEquals("IOException", MinosLauncher.failureMessage(new IOException(detail)), detail);
            assertEquals("IOException", DockerRuntimeBootstrap.failureMessage(new IOException(detail)), detail);
        }
    }

    @Test
    void dockerMappingConflictDoesNotPublishItsPrivateControlPlanePath(@TempDir Path home) throws Exception {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(new ProjectPathMapping("N:/workspace-dev", "/workspace/projects"));
        StringBuilder error = new StringBuilder();

        int exitCode = DockerRuntimeBootstrap.run(home,
                new String[]{"configure-project-paths", "D:/other-projects", "/workspace/projects"},
                new StringBuilder(), error);

        assertEquals(FindSymbolCommand.EXECUTION_ERROR, exitCode);
        assertEquals("error: refusing to replace an existing project path mapping implicitly\n", error.toString());
        assertFalse(error.toString().contains(store.file().toString()));
    }
}
