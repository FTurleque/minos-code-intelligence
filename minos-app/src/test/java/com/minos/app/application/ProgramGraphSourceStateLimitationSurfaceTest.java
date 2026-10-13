package com.minos.app.application;

import com.minos.api.AdvancedCodeIntelligenceApi;
import com.minos.api.LocalAdvancedCodeIntelligenceApi;
import com.minos.application.MinosApplication;
import com.minos.cli.MinosLauncher;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.mcp.MinosMcpTools;
import com.minos.program.analysis.ProgramGraphService;
import com.minos.registry.RegisteredProject;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AUD-PERF-07, D3 : sans empreinte exacte du snapshot, le graphe de programme, l'impact avancé et les chemins de
 * sécurité portent la même limitation, avec le même identifiant, sur le MCP, la CLI et l'API Java ; avec une
 * empreinte exacte, aucune de ces neuf réponses ne la porte.
 */
class ProgramGraphSourceStateLimitationSurfaceTest {

    private static final String LIMITATION = ProgramGraphService.SOURCE_STATE_REUSE_LIMITATION;
    private static final String PROJECT = "source-state-surface";
    private static final String SYMBOL = "source-state-surface-symbol";
    private static final String SNAPSHOT = "source-state-surface-snapshot";
    private static final String FILE_ID = "src/main/java/demo/Surface.java";

    @Test
    void everySurfaceRelaysTheSameIdentifierWithoutExactFingerprint(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        publishJavaSnapshot(home, temp, false);

        Map<String, String> outputs = outputs(home);

        assertEquals(9, outputs.size());
        outputs.forEach((surface, output) -> assertEquals(1, occurrences(output), surface));
    }

    @Test
    void noSurfaceCarriesItWithAnExactFingerprint(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        publishJavaSnapshot(home, temp, true);

        Map<String, String> outputs = outputs(home);

        assertEquals(9, outputs.size());
        outputs.forEach((surface, output) -> assertFalse(output.contains(LIMITATION), surface));
    }

    private static void publishJavaSnapshot(Path home, Path temp, boolean exactFingerprint) throws Exception {
        Path projectRoot = Files.createDirectories(temp.resolve("java-project"));
        Path source = projectRoot.resolve(FILE_ID);
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package demo;
                final class Surface {
                    int run(int value) {
                        int copy = value + 1;
                        return copy;
                    }
                }
                """, StandardCharsets.UTF_8);
        try (MinosApplication application = MinosApplication.open(home)) {
            RegisteredProject project = application.projectRegistry().registerProject(projectRoot, PROJECT);
            Symbol symbol = new Symbol(
                    SYMBOL, "demo/Surface#", SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(), null,
                    FILE_ID, null, SymbolKind.CLASS, "Surface", "demo.Surface", null, "java",
                    new SymbolLocation(FILE_ID, 1, 0, 1, 1, PositionEncoding.UTF16_CODE_UNITS),
                    ResolutionStatus.RESOLVED,
                    new Origin("source-state-surface", "FIXTURE", "1", SNAPSHOT, OriginType.OTHER),
                    false, false, Set.of());
            application.snapshotStore().publish(project.id(), SNAPSHOT, List.of(symbol), List.of(), List.of());
            if (exactFingerprint) {
                application.fingerprintStore().publish(
                        project.id(), SNAPSHOT, application.fingerprintService().capture(projectRoot));
                application.fingerprintStore().promote(project.id(), SNAPSHOT);
            }
        }
    }

    /** Les trois analyses par les trois surfaces, chacune dans le processus que la surface ouvre elle-même. */
    private static Map<String, String> outputs(Path home) throws Exception {
        Map<String, String> outputs = new LinkedHashMap<>();
        try (MinosMcpTools tools = new MinosMcpTools(home)) {
            List<SyncToolSpecification> specifications = tools.specifications();
            outputs.put("mcp minos_program_graph", mcp(specifications, "minos_program_graph", Map.of("project", PROJECT)));
            outputs.put("mcp minos_impact_v2",
                    mcp(specifications, "minos_impact_v2", Map.of("project", PROJECT, "symbolId", SYMBOL)));
            outputs.put("mcp minos_security_paths",
                    mcp(specifications, "minos_security_paths", Map.of("project", PROJECT)));
        }
        outputs.put("cli program-graph", cli(home, "ide", "program-graph", PROJECT, "--format", "json"));
        outputs.put("cli impact-v2", cli(home, "ide", "impact-v2", PROJECT, SYMBOL, "--format", "json"));
        outputs.put("cli security-paths", cli(home, "ide", "security-paths", PROJECT, "--format", "json"));
        try (MinosApplication application = MinosApplication.open(home)) {
            AdvancedCodeIntelligenceApi api = new LocalAdvancedCodeIntelligenceApi(application);
            outputs.put("api getProgramGraph", String.join("\n", api.getProgramGraph(
                    PROJECT, new AdvancedCodeIntelligenceApi.ProgramGraphQuery(1_000, 1_000)).limitations()));
            outputs.put("api analyzeImpactV2", String.join("\n", api.analyzeImpactV2(
                    PROJECT, new AdvancedCodeIntelligenceApi.AdvancedImpactQuery(SYMBOL, 4, 100)).limitations()));
            outputs.put("api analyzeSecurityPaths", String.join("\n", api.analyzeSecurityPaths(
                    PROJECT, new AdvancedCodeIntelligenceApi.SecurityQuery(null, 8, 100)).limitations()));
        }
        return outputs;
    }

    private static String mcp(List<SyncToolSpecification> specifications, String tool, Map<String, Object> arguments) {
        SyncToolSpecification specification = specifications.stream()
                .filter(candidate -> tool.equals(candidate.tool().name()))
                .findFirst()
                .orElseThrow();
        CallToolResult result = specification.callHandler()
                .apply(null, CallToolRequest.builder(tool).arguments(arguments).build());
        assertFalse(Boolean.TRUE.equals(result.isError()), tool);
        StringBuilder content = new StringBuilder();
        for (var item : result.content()) {
            content.append(item instanceof TextContent text ? text.text() : item.toString());
        }
        return content.toString();
    }

    private static String cli(Path home, String... arguments) throws Exception {
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();
        assertEquals(0, MinosLauncher.run(home, arguments, output, error), () -> String.join(" ", arguments));
        return output.toString();
    }

    private static int occurrences(String output) {
        int count = 0;
        for (int index = output.indexOf(LIMITATION); index >= 0; index = output.indexOf(LIMITATION, index + 1)) {
            count++;
        }
        return count;
    }
}
