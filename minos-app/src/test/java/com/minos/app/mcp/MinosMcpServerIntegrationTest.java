package com.minos.app.mcp;

import com.minos.cli.MinosLauncher;
import com.minos.domain.Symbol;
import com.minos.mcp.MinosMcpServer;
import com.minos.mcp.MinosMcpTools;
import com.minos.storage.local.registry.LocalProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinosMcpServerIntegrationTest {

    @Test
    void standardStdioClientDiscoversAndCallsRealMinosTools(@TempDir Path home) throws Exception {
        Symbol greetingPort = indexTypescriptFixture(home);
        List<String> serverStderr = new CopyOnWriteArrayList<>();
        McpSyncClient client = startClient(home, serverStderr);

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
                    "M10 MCP stdio: tools=%d, architecture-modules=3, graph=mermaid, impact-root=GreetingPort%n",
                    listed.tools().size());
        } finally {
            client.closeGracefully();
        }
    }

    /**
     * Preuve de bout en bout du schéma publié {@code additionalProperties:false} (AUD-SEC-09) : un argument absent
     * du schéma est refusé par le transport STDIO réel, sans exécuter l'outil ni reproduire de valeur d'appelant.
     */
    @Test
    void unknownArgumentIsRefusedOverStdioWithoutEchoingCallerValues(@TempDir Path home) throws Exception {
        Symbol greetingPort = indexTypescriptFixture(home);
        List<String> serverStderr = new CopyOnWriteArrayList<>();
        McpSyncClient client = startClient(home, serverStderr);
        String leakedPath = "C:/Users/fturl/secret-workspace/token.txt";
        try {
            client.initialize();
            Map<String, Object> valid = Map.of("project", "m10-typescript", "symbolId", greetingPort.id());

            var served = client.callTool(CallToolRequest.builder("minos_impact").arguments(valid).build());
            assertFalse(Boolean.TRUE.equals(served.isError()), "les arguments du schéma restent servis");

            Map<String, Object> misspelled = new LinkedHashMap<>(valid);
            misspelled.put("limt", 1);
            String misspelledText = refused(client, misspelled);
            assertTrue(misspelledText.contains("limt"), misspelledText);
            assertFalse(misspelledText.contains("impactCount"), "l'outil ne doit pas être exécuté");

            Map<String, Object> withPathValue = new LinkedHashMap<>(valid);
            withPathValue.put("unknownOption", leakedPath);
            String pathValueText = refused(client, withPathValue);
            assertFalse(pathValueText.contains(leakedPath), pathValueText);

            String hugeKey = "k".repeat(10_000);
            Map<String, Object> withHugeKey = new LinkedHashMap<>(valid);
            withHugeKey.put(hugeKey, 1);
            String hugeKeyText = refused(client, withHugeKey);
            assertTrue(hugeKeyText.length() < 500, "réponse bornée, longueur " + hugeKeyText.length());
            assertFalse(hugeKeyText.contains(hugeKey.substring(0, 200)), "la clé démesurée n'est pas reproduite");

            Map<String, Object> withPathKey = new LinkedHashMap<>(valid);
            withPathKey.put(leakedPath, 1);
            String pathKeyText = refused(client, withPathKey);
            assertEquals("error: MINOS tool execution failed", pathKeyText);

            String journal = String.join("\n", serverStderr);
            assertFalse(journal.contains(leakedPath), "ni la valeur ni une clé à forme de chemin dans le journal");
            assertFalse(journal.contains(hugeKey.substring(0, 200)), "la clé démesurée n'est pas journalisée");
        } finally {
            client.closeGracefully();
        }
    }

    /** Appelle {@code minos_impact} et exige un résultat d'outil en erreur ; rend son texte. */
    private static String refused(McpSyncClient client, Map<String, Object> arguments) {
        var result = client.callTool(CallToolRequest.builder("minos_impact").arguments(arguments).build());
        assertTrue(Boolean.TRUE.equals(result.isError()), "l'appel doit être refusé");
        return ((TextContent) result.content().getFirst()).text();
    }

    private static Symbol indexTypescriptFixture(Path home) throws Exception {
        Path fixture = Path.of("fixtures", "typescript", "typescript-modules");
        Path scip = fixture.resolve(Path.of(".minos-m0", "scip-typescript", "index.scip"));
        run(home, "project", "add", fixture.toString(), "--name", "m10-typescript", "--format", "json");
        run(home, "index", "m10-typescript", "--scip", scip.toString(),
                "--provider", "scip-typescript", "--provider-version", "0.4.0", "--format", "json");
        RegisteredProject project = new LocalProjectRegistry(home.resolve("registry")).listProjects().getFirst();
        CodeKnowledgeSnapshot snapshot = new FileSymbolSnapshotStore(home.resolve("symbol-snapshots"))
                .loadActiveKnowledge(project.id()).orElseThrow();
        return snapshot.symbols().stream()
                .filter(symbol -> "GreetingPort".equals(symbol.qualifiedName()))
                .findFirst().orElseThrow();
    }

    private static McpSyncClient startClient(Path home, List<String> serverStderr) {
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
        transport.setStdErrorHandler(serverStderr::add);
        return McpClient.sync(transport)
                .requestTimeout(Duration.ofSeconds(20))
                .build();
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
