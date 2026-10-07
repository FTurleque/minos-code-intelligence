package com.minos.mcp;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C05 : une combinaison de bornes que le schéma publié accepte n'est pas refusée par les valeurs par défaut.
 * Sans plafond par document fourni par le client, le plafond par défaut (800) est borné par {@code maxTokens} ; une
 * combinaison explicitement incohérente reste refusée avec un message actionnable.
 */
class MinosMcpHybridContextBoundsTest {

    @TempDir
    Path home;

    @Test
    void theDefaultPerDocumentCapIsBoundedByMaxTokens() {
        assertEquals(500, defaults(500, null).maxTokensPerDocument(), "a low total budget lowers the default cap");
        assertEquals(128, defaults(128, null).maxTokensPerDocument(), "the schema minimum of maxTokens is accepted");
        assertEquals(800, defaults(800, null).maxTokensPerDocument());
        assertEquals(800, defaults(4000, null).maxTokensPerDocument(), "above 800 the default is unchanged");
        assertEquals(800, defaults(65536, null).maxTokensPerDocument());
        assertEquals(800, defaults(null, null).maxTokensPerDocument(), "no maxTokens: the 4 000 default keeps 800");
        assertEquals(4000, defaults(null, null).maxTokens());
    }

    @Test
    void anExplicitPerDocumentCapIsNeverOverridden() {
        assertEquals(256, defaults(500, 256).maxTokensPerDocument());
        assertEquals(900, defaults(500, 900).maxTokensPerDocument(), "an inconsistent pair is left for the validation to refuse");
    }

    @Test
    void aLowMaxTokensAloneIsAcceptedAndReachesTheProjectResolution() throws Exception {
        try (MinosApplication application = MinosApplication.open(home)) {
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            for (int maxTokens : new int[]{128, 500, 799}) {
                var result = McpToolCalls.call(tools, "minos_hybrid_context",
                        Map.of("project", "ghost", "query", "auth", "maxTokens", maxTokens));

                String text = McpToolCalls.text(result);
                assertEquals("error: unknown project: ghost", text, "maxTokens=" + maxTokens);
            }
        }
    }

    @Test
    void anExplicitlyInconsistentPairIsStillRefusedWithAnActionableMessage() throws Exception {
        try (MinosApplication application = MinosApplication.open(home)) {
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            var result = McpToolCalls.call(tools, "minos_hybrid_context",
                    Map.of("project", "ghost", "query", "auth", "maxTokens", 500, "maxTokensPerDocument", 900));

            String text = McpToolCalls.text(result);
            assertTrue(McpToolCalls.isError(result), text);
            assertTrue(text.contains("maxTokensPerDocument must be between 32 and maxTokens"), text);
            assertFalse(text.contains("MINOS tool execution failed"), text);
        }
    }

    private static MinosMcpBackend.HybridContextRequest defaults(Integer maxTokens, Integer perDocument) {
        return MinosApplicationMcpBackend.hybridContextDefaults("demo", "auth", null, maxTokens, perDocument);
    }
}
