package com.minos.mcp;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C01 : une référence de projet inconnue reçoit une erreur actionnable, sans recopier ce qui ressemble à
 * un chemin ou à un secret.
 */
class MinosMcpProjectReferenceErrorTest {

    @TempDir
    Path temp;

    @Test
    void aReferenceShapedLikeAPathOrASecretGetsAnActionableErrorThatDoesNotEchoIt() throws Exception {
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            for (String tool : new String[]{"minos_index_status", "minos_project_structure"}) {
                for (String reference : new String[]{
                        "C:\\Users\\x\\proj", "/home/x/proj", "\\\\srv\\share\\p", "my-api-key-service"}) {
                    var result = McpToolCalls.call(tools, tool, Map.of("project", reference));
                    String text = McpToolCalls.text(result);
                    String context = tool + " " + reference + " -> " + text;

                    assertTrue(McpToolCalls.isError(result), context);
                    assertTrue(text.contains("unknown project"), context);
                    assertTrue(text.contains("project name or UUID"), context);
                    assertFalse(text.contains("C:\\"), context);
                    assertFalse(text.contains("/home"), context);
                    assertFalse(text.contains("\\\\srv"), context);
                    assertFalse(text.contains("api-key"), context);
                }
            }
        }
    }

    @Test
    void aPlainUnknownNameKeepsTheMessageItHadBeforeThisChange() throws Exception {
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            var result = McpToolCalls.call(new MinosMcpTools(new MinosApplicationMcpBackend(application)),
                    "minos_index_status", Map.of("project", "demo"));

            assertEquals("error: unknown project: demo", McpToolCalls.text(result));
        }
    }

    @Test
    void aNameNotFoundBesideAnUnreadableRegistryEntryCountsThemAndSaysExistenceCannotBeEstablished() throws Exception {
        Path home = temp.resolve("home");
        try (MinosApplication application = MinosApplication.open(home)) {
            var project = application.projectRegistry().registerProject(
                    Files.createDirectories(temp.resolve("alpha")), "alpha");
            Path entry = home.resolve("registry").resolve("projects").resolve(project.id() + ".properties");
            Files.writeString(entry, Files.readString(entry, StandardCharsets.UTF_8)
                    .replaceAll("createdAt=.*", "createdAt=not-an-instant"), StandardCharsets.UTF_8);

            var result = McpToolCalls.call(new MinosMcpTools(new MinosApplicationMcpBackend(application)),
                    "minos_index_status", Map.of("project", "ghost"));

            String text = McpToolCalls.text(result);
            assertTrue(McpToolCalls.isError(result), text);
            assertTrue(text.contains("registry entr"), text);
            assertTrue(text.contains("1 registry entry is unreadable"), text);
            assertFalse(text.contains(temp.toString()), text);
            assertFalse(text.contains("alpha"), text);
            assertFalse(text.contains("MINOS tool execution failed"), text);
        }
    }
}
