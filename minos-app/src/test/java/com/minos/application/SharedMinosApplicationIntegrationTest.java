package com.minos.application;

import com.minos.api.LocalMinosApi;
import com.minos.api.LocalMinosMultiRepositoryApi;
import com.minos.cli.MinosCliRunner;
import com.minos.mcp.MinosMcpApplicationTools;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        var mcpTools = MinosMcpApplicationTools.specifications(application);
        assertEquals(16, mcpTools.size());
        var projectStructure = mcpTools.stream()
                .filter(spec -> "minos_project_structure".equals(spec.tool().name()))
                .findFirst().orElseThrow();
        var mcpResult = projectStructure.callHandler().apply(null,
                CallToolRequest.builder("minos_project_structure")
                        .arguments(Map.of("project", "shared-project"))
                        .build());
        assertFalse(Boolean.TRUE.equals(mcpResult.isError()));
        String mcpJson = ((TextContent) mcpResult.content().getFirst()).text();
        assertTrue(mcpJson.contains("\"name\":\"shared-project\""));
        assertTrue(mcpJson.contains("\"indexState\":\"NEVER_INDEXED\""));
    }
}
