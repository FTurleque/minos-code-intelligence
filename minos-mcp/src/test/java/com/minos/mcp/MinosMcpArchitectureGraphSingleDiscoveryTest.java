package com.minos.mcp;

import com.minos.application.MinosApplication;
import com.minos.architecture.ProjectArchitectureQuery;
import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDetector;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.output.ArchitectureResultRenderer;
import com.minos.registry.RegisteredProject;
import com.minos.storage.local.store.FileSymbolSnapshotStore;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-05 sur MCP : {@code minos_architecture_graph} filtré sur un module découvre le dépôt une seule fois et
 * charge le snapshot actif une seule fois, pour la même sortie et la même erreur de module inconnu. Branchement réel
 * (application, découverte et store de fichiers), les deux derniers comptant leurs appels.
 */
class MinosMcpArchitectureGraphSingleDiscoveryTest {
    private static final String PROJECT = "perf05";
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @TempDir
    Path home;

    private final AtomicInteger discoveries = new AtomicInteger();
    private final AtomicInteger snapshotLoads = new AtomicInteger();
    private Path projectRoot;

    @BeforeEach
    void createsATwoModuleProject() throws Exception {
        projectRoot = Files.createDirectories(home.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        for (String module : List.of("api", "app")) {
            Path sources = Files.createDirectories(projectRoot.resolve(module + "/src/main/java/com/acme/" + module));
            Files.writeString(projectRoot.resolve(module + "/pom.xml"), "<project/>");
            Files.writeString(sources.resolve("Type.java"), "package com.acme." + module + "; public class Type {}");
        }
    }

    @Test
    void aMermaidGraphWithAModuleDiscoversOnceAndLoadsTheSnapshotOnce() throws Exception {
        try (MinosApplication application = openCountingApplication()) {
            ProjectArchitectureQuery query = application.architectureQuery();
            String expected = ArchitectureResultRenderer.renderGraph(query.getArchitectureIntelligence(PROJECT),
                    query.getModuleContext(PROJECT, "api").module().id(), ArchitectureResultRenderer.GraphFormat.MERMAID);
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));
            resetCounters();

            var result = McpToolCalls.call(tools, "minos_architecture_graph",
                    Map.of("project", PROJECT, "module", "api", "format", "mermaid"));

            assertFalse(McpToolCalls.isError(result), McpToolCalls.text(result));
            assertEquals(expected, McpToolCalls.text(result));
            assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                    "project discoveries and active snapshot loads for one graph request with a module");
        }
    }

    @Test
    void aGraphWithoutAModuleStillDiscoversOnce() throws Exception {
        try (MinosApplication application = openCountingApplication()) {
            String expected = ArchitectureResultRenderer.renderGraph(
                    application.architectureQuery().getArchitectureIntelligence(PROJECT), null,
                    ArchitectureResultRenderer.GraphFormat.DOT);
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));
            resetCounters();

            var result = McpToolCalls.call(tools, "minos_architecture_graph", Map.of("project", PROJECT, "format", "dot"));

            assertFalse(McpToolCalls.isError(result), McpToolCalls.text(result));
            assertEquals(expected, McpToolCalls.text(result));
            assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                    "project discoveries and active snapshot loads for one graph request without a module");
        }
    }

    @Test
    void anUnknownModuleKeepsItsPublicErrorAfterASingleDiscovery() throws Exception {
        try (MinosApplication application = openCountingApplication()) {
            MinosMcpTools tools = new MinosMcpTools(new MinosApplicationMcpBackend(application));
            String moduleContextError = McpToolCalls.text(McpToolCalls.call(tools, "minos_module_context",
                    Map.of("project", PROJECT, "module", "absent")));
            resetCounters();

            var result = McpToolCalls.call(tools, "minos_architecture_graph",
                    Map.of("project", PROJECT, "module", "absent", "format", "dot"));

            assertTrue(McpToolCalls.isError(result), McpToolCalls.text(result));
            assertEquals("error: unknown module: absent", McpToolCalls.text(result));
            assertEquals(moduleContextError, McpToolCalls.text(result));
            assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                    "project discoveries and active snapshot loads for one refused graph request");
        }
    }

    private MinosApplication openCountingApplication() throws Exception {
        MinosApplication application = MinosApplication.builder(home.resolve("app"))
                .discoveryService(countingDiscovery())
                .snapshotStore(countingStore(new FileSymbolSnapshotStore(home.resolve("counted-snapshots"))))
                .build();
        RegisteredProject project = application.projectRegistry().registerProject(projectRoot, PROJECT);
        application.snapshotStore().publish(project.id(), "snapshot-perf05",
                List.of(symbol(project, "api"), symbol(project, "app")), List.of(), List.of());
        return application;
    }

    private void resetCounters() {
        discoveries.set(0);
        snapshotLoads.set(0);
    }

    /** Le détecteur ajouté ne reconnaît aucun module : il compte la visite de la racine (une par découverte). */
    private ProjectDiscoveryService countingDiscovery() {
        List<ProjectDetector> detectors = new ArrayList<>();
        detectors.add((root, directory, ignorePolicy) -> {
            if (directory.equals(root)) {
                discoveries.incrementAndGet();
            }
            return false;
        });
        detectors.addAll(DefaultDiscoveryPlugins.projectDetectors());
        return new ProjectDiscoveryService(detectors, DefaultDiscoveryPlugins.buildSystemDetectors(),
                DefaultDiscoveryPlugins.sourceRootDetectors(), DefaultDiscoveryPlugins.languageDetectors());
    }

    private CodeKnowledgeSnapshotStore countingStore(CodeKnowledgeSnapshotStore delegate) {
        return (CodeKnowledgeSnapshotStore) Proxy.newProxyInstance(CodeKnowledgeSnapshotStore.class.getClassLoader(),
                new Class<?>[]{CodeKnowledgeSnapshotStore.class}, (proxy, method, arguments) -> {
                    if ("loadActiveKnowledge".equals(method.getName())) {
                        snapshotLoads.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Symbol symbol(RegisteredProject project, String module) {
        return new Symbol("sym:" + module, "key:" + module, SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(), "main", module + "/src/main/java/com/acme/" + module + "/Type.java", null,
                SymbolKind.CLASS, "Type", "com.acme." + module + ".Type", null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}
