package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.spi.ProjectDetector;
import com.minos.discovery.ProjectDiscoveryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C03 / D02 : l'état d'index d'un projet est calculé sans parcourir l'arbre du dépôt ; une découverte qui
 * échoue ne fait pas échouer le statut.
 */
class MinosMcpIndexStatusWithoutDiscoveryTest {

    @TempDir
    Path temp;

    @Test
    void indexStatusAnswersWithoutDiscoveringTheRepositoryEvenWhenDiscoveryWouldFail() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        AtomicInteger discoveries = new AtomicInteger();
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .discoveryService(failingDiscovery(discoveries)).build()) {
            application.projectRegistry().registerProject(project, "fixture");
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            var result = McpToolCalls.call(tools, "minos_index_status", Map.of("project", "fixture"));

            String text = McpToolCalls.text(result);
            assertFalse(McpToolCalls.isError(result), text);
            assertTrue(text.contains("NEVER_INDEXED"), text);
            assertEquals(0, discoveries.get(), "the status must not walk the repository");
        }
    }

    /** A discovery whose project detector fails like a file walk over an unreadable directory, counting its calls. */
    private static ProjectDiscoveryService failingDiscovery(AtomicInteger calls) {
        List<ProjectDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.projectDetectors());
        // First, so that it is reached even for a project whose root is claimed by a default detector.
        detectors.add(0, (projectRoot, directory, ignorePolicy) -> {
            calls.incrementAndGet();
            throw new UncheckedIOException(new AccessDeniedException("locked"));
        });
        return new ProjectDiscoveryService(detectors, DefaultDiscoveryPlugins.buildSystemDetectors(),
                DefaultDiscoveryPlugins.sourceRootDetectors(), DefaultDiscoveryPlugins.languageDetectors());
    }
}
