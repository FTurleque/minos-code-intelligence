package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-C02 : les outils MCP de statut d'index et de structure de projet ne touchent ni à MINOS_HOME ni aux
 * runtimes providers (ADR 0017 : le MCP est en lecture seule).
 */
class MinosMcpReadOnlyHomeTest {

    @TempDir
    Path temp;

    @Test
    void statusAndStructureNeverInspectTheProviderRuntimesNorChangeTheHome() throws Exception {
        Path home = temp.resolve("home");
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        CountingRuntimes runtimes = new CountingRuntimes();
        try (MinosApplication application = MinosApplication.builder(home).providerRuntimeManager(runtimes).build()) {
            application.projectRegistry().registerProject(project, "fixture");
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));
            Map<String, String> before = snapshot(home);

            for (String tool : new String[]{"minos_index_status", "minos_project_structure"}) {
                var result = McpToolCalls.call(tools, tool, Map.of("project", "fixture"));
                assertFalse(McpToolCalls.isError(result), tool + " -> " + McpToolCalls.text(result));
            }

            assertEquals(0, runtimes.calls.get(), "no runtime inspection, listing or installation");
            assertEquals(before, snapshot(home), "MINOS_HOME must be identical before and after");
        }
    }

    @Test
    void theProfilesDeclareTheRuntimeStateAsNotInspectedAndNeverSimulateAState() throws Exception {
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        CountingRuntimes runtimes = new CountingRuntimes();
        try (MinosApplication application = MinosApplication.builder(temp.resolve("home"))
                .providerRuntimeManager(runtimes).build()) {
            application.projectRegistry().registerProject(project, "fixture");
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));

            for (String tool : new String[]{"minos_index_status", "minos_project_structure"}) {
                String text = McpToolCalls.text(McpToolCalls.call(tools, tool, Map.of("project", "fixture")));

                assertTrue(text.contains("\"runtimeState\":\"NOT_INSPECTED\""), tool + " -> " + text);
                assertTrue(text.contains("minos providers"), tool + " must say where the real state is read");
                assertFalse(text.contains("\"runtimeState\":\"READY\""), tool);
                assertFalse(text.contains("\"runtimeState\":\"NOT_INSTALLED\""), tool);
            }
            assertEquals(0, runtimes.calls.get());
        }
    }

    /** The real runtime manager, on Windows where inspecting it writes a launcher script and runs a sandbox probe. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void withTheRealRuntimeManagerNoLauncherOrProbeArtifactAppearsUnderTheHome() throws Exception {
        Path home = temp.resolve("home");
        Path project = Files.createDirectories(temp.resolve("project"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        try (MinosApplication application = MinosApplication.open(home)) {
            application.projectRegistry().registerProject(project, "fixture");
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));
            Map<String, String> before = snapshot(home);

            for (String tool : new String[]{"minos_index_status", "minos_project_structure"}) {
                assertFalse(McpToolCalls.isError(McpToolCalls.call(tools, tool, Map.of("project", "fixture"))), tool);
            }

            assertEquals(before, snapshot(home));
            assertFalse(Files.exists(home.resolve("sandbox")) && !before.containsKey("sandbox"),
                    "no sandbox probe directory may be created by a status call");
        }
    }

    /** Path, size and modification time of everything under {@code root}. */
    static Map<String, String> snapshot(Path root) throws IOException {
        Map<String, String> entries = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : (Iterable<Path>) walk::iterator) {
                entries.put(root.relativize(path).toString(),
                        Files.size(path) + "@" + Files.getLastModifiedTime(path).toMillis());
            }
        }
        return entries;
    }

    /** A runtime manager that only counts what it is asked. */
    static final class CountingRuntimes implements ProviderRuntimeManager {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public List<ProviderRuntimeStatus> list() {
            calls.incrementAndGet();
            return List.of();
        }

        @Override
        public ProviderRuntimeStatus inspect(String providerId) {
            calls.incrementAndGet();
            return new ProviderRuntimeStatus(providerId, "1", ProviderRuntimeStatus.State.READY, Optional.empty(),
                    List.of("counted"));
        }

        @Override
        public ProviderRuntimeStatus install(String providerId) {
            calls.incrementAndGet();
            return inspect(providerId);
        }

        @Override
        public IndexerExecutor executor(String providerId) {
            calls.incrementAndGet();
            throw new UnsupportedOperationException("not used by a status call");
        }
    }
}
