package com.minos.mcp;

import com.minos.cli.MinosLauncher;
import com.minos.domain.Symbol;
import com.minos.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.FileSymbolSnapshotStore;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosMcpServerIntegrationTest {

    @Test
    void standardStdioClientDiscoversAndCallsRealMinosTools(@TempDir Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        run(home, "project", "add", fixture.toString(), "--name", "m10-typescript", "--format", "json");
        run(home, "index", "m10-typescript", "--scip", scip.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", "json");

        RegisteredProject project = new LocalProjectRegistry(home.resolve("registry")).listProjects().getFirst();
        CodeKnowledgeSnapshot snapshot = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
                .loadActiveKnowledge(project.id()).orElseThrow();
        Symbol greetingPort = snapshot.symbols().stream()
                .filter(symbol -> "GreetingPort".equals(symbol.qualifiedName()))
                .findFirst().orElseThrow();

        String javaExecutable = Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        String testClasspath = System.getProperty(
                "surefire.test.class.path",
                System.getProperty("java.class.path")
        );
        ServerParameters parameters = ServerParameters.builder(javaExecutable)
                .args("-cp", testClasspath, MinosMcpServer.class.getName())
                .env(Map.of(MinosLauncher.HOME_ENVIRONMENT_VARIABLE, home.toString()))
                .build();
        StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build();

        try {
            client.initialize();
            var listed = client.listTools();
            assertEquals(MinosMcpTools.TOOL_COUNT, listed.tools().size());
            List<String> names = listed.tools().stream().map(tool -> tool.name()).toList();
            assertTrue(names.contains("minos_project_structure"));
            assertTrue(names.contains("minos_architecture"));
            assertTrue(names.contains("minos_architecture_graph"));
            assertTrue(names.contains("minos_impact"));

            var architecture = client.callTool(CallToolRequest.builder("minos_architecture")
                    .arguments(Map.of("project", "m10-typescript"))
                    .build());
            assertFalse(Boolean.TRUE.equals(architecture.isError()));
            String architectureJson = ((TextContent) architecture.content().getFirst()).text();
            assertTrue(architectureJson.contains("\"moduleCount\":3"), architectureJson);
            assertTrue(architectureJson.contains("\"moduleDependencies\":["), architectureJson);

            var graph = client.callTool(CallToolRequest.builder("minos_architecture_graph")
                    .arguments(Map.of("project", "m10-typescript", "format", "mermaid"))
                    .build());
            assertFalse(Boolean.TRUE.equals(graph.isError()));
            String graphMermaid = ((TextContent) graph.content().getFirst()).text();
            assertTrue(graphMermaid.startsWith("flowchart LR"), graphMermaid);
            assertTrue(graphMermaid.contains("packages/app"), graphMermaid);
            assertTrue(graphMermaid.contains("packages/api"), graphMermaid);

            var impact = client.callTool(CallToolRequest.builder("minos_impact")
                    .arguments(Map.of("project", "m10-typescript", "symbolId", greetingPort.id()))
                    .build());
            assertFalse(Boolean.TRUE.equals(impact.isError()));
            String impactJson = ((TextContent) impact.content().getFirst()).text();
            assertTrue(impactJson.contains("\"impactCount\":2"), impactJson);
            assertTrue(impactJson.contains("\"testCount\":1"), impactJson);

            var invalidImpact = client.callTool(CallToolRequest.builder("minos_impact")
                    .arguments(Map.of(
                            "project", "m10-typescript",
                            "symbolId", greetingPort.id(),
                            "depth", 99
                    ))
                    .build());
            assertTrue(Boolean.TRUE.equals(invalidImpact.isError()));

            System.out.printf(
                    "M10 MCP stdio: tools=%d, project=%s, snapshot=%s, architecture-modules=3, graph=mermaid, impact-root=GreetingPort%n",
                    listed.tools().size(), project.id(), snapshot.snapshotId());
        } finally {
            client.closeGracefully();
        }
    }

    private static void run(Path home, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        int exitCode = MinosLauncher.run(home, arguments, output, error);
        assertEquals(0, exitCode, error.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
