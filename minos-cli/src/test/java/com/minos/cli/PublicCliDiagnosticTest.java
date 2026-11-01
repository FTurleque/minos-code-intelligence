package com.minos.cli;

import com.minos.cli.command.FindSymbolCommand;
import com.minos.domain.registry.ProjectPathMapping;
import com.minos.storage.home.ProjectPathMappingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void aProjectRootReplacementRefusalReachesTheOperatorUnchanged(@TempDir Path home) throws Exception {
        ProjectPathMappingStore store = new ProjectPathMappingStore(home);
        store.save(new ProjectPathMapping("N:/workspace-dev", "/workspace/projects"));
        java.nio.file.Files.createDirectories(home.resolve("config"));
        java.nio.file.Files.writeString(home.resolve("config/minos.properties"), "minos.storage.backend=postgresql\n");

        IOException refusal = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> DockerRuntimeBootstrap.run(home,
                        new String[]{"configure-project-paths", "--replace", "D:/other-projects", "/workspace/projects"},
                        new StringBuilder(), new StringBuilder()));

        assertEquals(refusal.getMessage(), DockerRuntimeBootstrap.failureMessage(refusal));
        assertTrue(refusal.getMessage().contains("PostgreSQL"), refusal.getMessage());
        assertEquals("N:/workspace-dev", store.loadOptional().orElseThrow().hostRoot());
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
        assertTrue(error.toString().startsWith("error: refusing to replace an existing project path mapping implicitly; "
                + "run configure-project-paths --replace"), error.toString());
        assertFalse(error.toString().contains(store.file().toString()));
    }
}
