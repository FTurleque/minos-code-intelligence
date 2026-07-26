package com.minos.application;

import com.minos.api.LocalMinosApi;
import com.minos.api.LocalMinosMultiRepositoryApi;
import com.minos.cli.MinosCliRunner;
import com.minos.mcp.MinosMcpApplicationTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedMinosApplicationIntegrationTest {

    @Test
    void cliApiMultiRepoAndMcpAreBuiltFromOneApplication(@TempDir Path root) throws Exception {
        MinosApplication application = MinosApplication.open(root.resolve("minos-home"));
        Path projectRoot = Files.createDirectories(root.resolve("project"));

        LocalMinosApi api = new LocalMinosApi(application);
        api.addProject(projectRoot, "shared-project");
        assertEquals("shared-project", api.listProjects().getFirst().name());

        StringBuilder cliOutput = new StringBuilder();
        StringBuilder cliError = new StringBuilder();
        int exitCode = MinosCliRunner.run(
                application,
                new String[]{"project", "list", "--format", "json"},
                cliOutput,
                cliError
        );
        assertEquals(0, exitCode, cliError::toString);
        assertTrue(cliOutput.toString().contains("shared-project"));

        LocalMinosMultiRepositoryApi multiRepositoryApi = new LocalMinosMultiRepositoryApi(application);
        assertEquals("shared-project", multiRepositoryApi.listProjects().getFirst().name());

        assertEquals(16, MinosMcpApplicationTools.specifications(application).size());
    }
}
