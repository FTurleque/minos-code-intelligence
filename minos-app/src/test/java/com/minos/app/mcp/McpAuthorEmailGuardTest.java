package com.minos.app.mcp;

import com.minos.application.MinosApplication;
import com.minos.mcp.MinosMcpApplicationTools;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * AUD-SEC-10 : aucun outil du catalogue MCP ne rend l'adresse e-mail d'un auteur Git. L'adresse est un fait de
 * l'historique (CLI et API Java, sur demande) ; le MCP n'a pas d'outil Git et ce garde le maintient.
 */
class McpAuthorEmailGuardTest {

    private static final String AUTHOR_EMAIL = "guard-author@example.invalid";

    @TempDir
    Path temp;

    @Test
    void noToolOfTheCatalogueRendersTheAuthorEmailOfAProject() throws Exception {
        Path root = Files.createDirectories(temp.resolve("guard-project"));
        Files.writeString(root.resolve("package.json"), "{\"name\":\"guard\",\"private\":true}\n");
        try (Git git = Git.init().setDirectory(root.toFile()).call()) {
            git.add().addFilepattern(".").call();
            PersonIdent author = new PersonIdent("Guard Author", AUTHOR_EMAIL);
            git.commit().setMessage("initial").setAuthor(author).setCommitter(author).setSign(false).call();
        }

        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            application.projectRegistry().registerProject(root, "guard");
            List<SyncToolSpecification> specifications = MinosMcpApplicationTools.specifications(application);
            assertEquals(com.minos.mcp.MinosMcpTools.TOOL_COUNT, specifications.size());

            for (SyncToolSpecification specification : specifications) {
                CallToolResult result = specification.callHandler().apply(null,
                        CallToolRequest.builder(specification.tool().name()).arguments(arguments(specification)).build());
                String text = ((TextContent) result.content().getFirst()).text();
                assertFalse(text.contains(AUTHOR_EMAIL), specification.tool().name() + " -> " + text);
                assertFalse(text.contains("example.invalid"), specification.tool().name());
            }
        }
    }

    /** Un argument pour chaque propriété requise du schéma publié, le projet désignant le dépôt de l'épreuve. */
    private static Map<String, Object> arguments(SyncToolSpecification specification) {
        Map<String, Object> arguments = new HashMap<>();
        if (specification.tool().inputSchema().get("required") instanceof List<?> required) {
            required.forEach(key -> arguments.put(String.valueOf(key), "project".equals(key) ? "guard" : "value"));
        }
        return arguments;
    }
}
