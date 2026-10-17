package com.minos.mcp;

import com.minos.application.MinosApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-SEC-10 : la fiche projet rendue par le MCP ne porte plus la racine absolue (nom du compte local, arborescence
 * du poste) ; {@code rootPath} reste présent avec le seul nom du répertoire racine. La CLI garde le chemin absolu
 * ({@code ProjectJsonTest}, {@code cli-json.golden}).
 */
class MinosMcpProjectRootExposureTest {

    @TempDir
    Path temp;

    @Test
    void projectStructureRendersTheRootDirectoryNameNotTheAbsolutePath() throws Exception {
        Path root = Files.createDirectories(temp.resolve("workspace").resolve("demo-root"));
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            application.projectRegistry().registerProject(root, "demo");

            String text = structure(application);

            assertTrue(text.contains("\"rootPath\":\"demo-root\""), text);
            assertTrue(text.contains("\"rootAvailable\":true"), text);
            assertNoAbsolutePath(text, temp);
        }
    }

    @Test
    void aMissingRootIsReportedUnavailableWithoutAnyAbsolutePath() throws Exception {
        Path root = Files.createDirectories(temp.resolve("workspace").resolve("gone-root"));
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            application.projectRegistry().registerProject(root, "demo");
            Files.delete(root);

            String text = structure(application);

            assertTrue(text.contains("\"rootAvailable\":false"), text);
            assertTrue(text.contains("\"rootPath\":\"gone-root\""), text);
            assertNoAbsolutePath(text, temp);
        }
    }

    @Test
    void theRootDirectoryNameIsTheLastSegmentOnEverySeparatorStyle() {
        assertEquals("proj", MinosApplicationMcpBackend.rootDirectoryName("/home/operator/proj"));
        assertEquals("proj", MinosApplicationMcpBackend.rootDirectoryName("/home/operator/proj/"));
        assertEquals("proj", MinosApplicationMcpBackend.rootDirectoryName("C:\\Users\\operator\\proj"));
        assertEquals("proj", MinosApplicationMcpBackend.rootDirectoryName("C:\\Users\\operator\\proj\\"));
        assertEquals("proj", MinosApplicationMcpBackend.rootDirectoryName("\\\\server\\share\\dir\\proj"));
        assertEquals("share", MinosApplicationMcpBackend.rootDirectoryName("\\\\server\\share"));
        assertEquals("proj", MinosApplicationMcpBackend.rootDirectoryName("proj"));
    }

    @Test
    void aFilesystemRootHasNoDirectoryNameAndRendersAPlaceholder() {
        for (String root : new String[]{"/", "D:\\", "D:/", "D:", ""}) {
            String shown = MinosApplicationMcpBackend.rootDirectoryName(root);
            assertEquals(MinosApplicationMcpBackend.FILESYSTEM_ROOT_NAME, shown, root);
            assertFalse(shown.contains("/") || shown.contains("\\") || shown.contains(":"), root);
        }
    }

    private static String structure(MinosApplication application) {
        var result = McpToolCalls.call(new MinosMcpTools(new MinosApplicationMcpBackend(application)),
                "minos_project_structure", Map.of("project", "demo"));
        assertFalse(McpToolCalls.isError(result), McpToolCalls.text(result));
        return McpToolCalls.text(result);
    }

    private static void assertNoAbsolutePath(String text, Path temp) {
        String raw = temp.toString();
        String escaped = raw.replace("\\", "\\\\");
        assertFalse(text.contains(raw), text);
        assertFalse(text.contains(escaped), text);
        assertFalse(text.contains(temp.getFileName().toString()), text);
    }
}
