package com.minos.storage.local.registry;

import com.minos.domain.registry.ProjectPathMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A registry refusal names a project root by its leaf, or by its role, never by its absolute path: the message reaches
 * the CLI, MCP and the Java API through the callers, and its cause travels with it.
 */
class LocalProjectRegistryPublicMessageTest {

    private static final String CONTAINER = "/workspace/projects";

    @TempDir
    Path temp;

    private Path mappedRoot;
    private Path outsideProject;
    private LocalProjectRegistry registry;

    @BeforeEach
    void layout() throws IOException {
        mappedRoot = Files.createDirectories(temp.resolve("mapped")).toRealPath();
        outsideProject = Files.createDirectories(temp.resolve("elsewhere").resolve("outside-project")).toRealPath();
        registry = new LocalProjectRegistry(
                Files.createDirectories(temp.resolve("registry")),
                ProjectPathMapping.singleRoot(mappedRoot.toString(), CONTAINER),
                ProjectPathMapping.RuntimeLocation.NATIVE);
    }

    @Test
    void aProjectOutsideTheConfiguredMappingIsRefusedByItsLeafNameOnly() {
        IOException refusal = assertThrows(IOException.class, () -> registry.registerProject(outsideProject, "Outside"));

        assertTrue(refusal.getMessage().startsWith("project path cannot be represented by configured runtime mapping"),
                refusal.getMessage());
        assertTrue(refusal.getMessage().contains("outside-project"), refusal.getMessage());
        assertCarriesNoPath(refusal, outsideProject);
    }

    @Test
    void aLegacyRootOutsideTheConfiguredMappingIsRefusedWithoutItsPath() throws Exception {
        UUID id = UUID.randomUUID();
        Path projects = Files.createDirectories(temp.resolve("registry").resolve("projects"));
        Files.writeString(projects.resolve(id + ".properties"),
                "id=" + id + "\ndisplayName=Legacy\nworkspaceId=\ncreatedAt=2026-01-01T00:00:00Z\n"
                        + "updatedAt=2026-01-01T00:00:00Z\nrootPath="
                        + outsideProject.toString().replace("\\", "\\\\").replace(":", "\\:") + "\n",
                StandardCharsets.UTF_8);

        IOException refusal = assertThrows(IOException.class, () -> registry.findProject(id));

        assertTrue(refusal.getMessage().startsWith("legacy project root cannot be migrated with configured mapping"),
                refusal.getMessage());
        assertCarriesNoPath(refusal, outsideProject);
    }

    private static void assertCarriesNoPath(Throwable refusal, Path root) {
        for (Throwable link = refusal; link != null; link = link.getCause()) {
            assertNotNull(link.getMessage(), link.toString());
            assertAbsent(link.getMessage(), root);
            for (Throwable suppressed : link.getSuppressed()) assertAbsent(String.valueOf(suppressed.getMessage()), root);
        }
    }

    private static void assertAbsent(String message, Path root) {
        assertFalse(message.contains(root.toString()), message);
        assertFalse(message.contains(root.getParent().toString()), message);
    }
}
