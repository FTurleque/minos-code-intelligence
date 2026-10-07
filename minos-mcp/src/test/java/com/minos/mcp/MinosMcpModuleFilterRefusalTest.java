package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-F04 sur MCP : un filtre par module sur un snapshot dont aucun symbole ne porte de module est une erreur
 * actionnable sans chemin, pas une réponse vide ; la recherche sans module reste inchangée. Branchement réel.
 */
class MinosMcpModuleFilterRefusalTest {
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @TempDir
    Path home;

    @Test
    void aModuleFilterOnASnapshotWithoutModulesIsAnActionableErrorAndTheUnfilteredSearchStillAnswers() throws Exception {
        try (MinosApplication application = MinosApplication.open(home.resolve("app"))) {
            RegisteredProject project = application.projectRegistry().registerProject(
                    Files.createDirectories(home.resolve("project")), "f04");
            application.snapshotStore().publish(project.id(), "snapshot-f04",
                    List.of(symbol(project, "a"), symbol(project, "b")), List.of(), List.of());
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            var refused = McpToolCalls.call(tools, "minos_find_symbols",
                    Map.of("project", "f04", "query", "A", "module", "packages/api"));
            String message = McpToolCalls.text(refused);
            assertTrue(McpToolCalls.isError(refused), message);
            assertTrue(message.startsWith("error: module filter is unavailable"), message);
            assertFalse(message.contains(home.toString()), message);

            var unfiltered = McpToolCalls.call(tools, "minos_find_symbols", Map.of("project", "f04", "query", "A"));
            assertFalse(McpToolCalls.isError(unfiltered), McpToolCalls.text(unfiltered));
            assertTrue(McpToolCalls.text(unfiltered).startsWith("{\"count\":"), McpToolCalls.text(unfiltered));
        }
    }

    private static Symbol symbol(RegisteredProject project, String id) {
        return new Symbol(id, "key:" + id, SymbolIdentityQuality.STRUCTURAL_FALLBACK, project.id().toString(), null,
                "src/" + id + ".java", null, SymbolKind.CLASS, id.toUpperCase(), "com.acme." + id.toUpperCase(), null,
                "java", null, ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}
