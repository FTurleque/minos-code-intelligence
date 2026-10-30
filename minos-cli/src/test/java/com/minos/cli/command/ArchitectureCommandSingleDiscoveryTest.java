package com.minos.cli.command;

import com.minos.architecture.LocalProjectArchitectureQuery;
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
import com.minos.storage.local.registry.LocalProjectRegistry;
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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AUD-PERF-05 on the CLI: {@code minos architecture --format <graph> --module <module>} discovers the project once
 * and loads the active snapshot once, for the same output and the same unknown-module error. Real wiring (registry,
 * file snapshot store, discovery); the last two count their calls.
 */
class ArchitectureCommandSingleDiscoveryTest {
    private static final String PROJECT = "perf05";
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @TempDir
    Path root;

    private final AtomicInteger discoveries = new AtomicInteger();
    private final AtomicInteger snapshotLoads = new AtomicInteger();
    private ProjectArchitectureQuery query;

    @BeforeEach
    void indexesATwoModuleProject() throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        for (String module : List.of("api", "app")) {
            Path sources = Files.createDirectories(projectRoot.resolve(module + "/src/main/java/com/acme/" + module));
            Files.writeString(projectRoot.resolve(module + "/pom.xml"), "<project/>");
            Files.writeString(sources.resolve("Type.java"), "package com.acme." + module + "; public class Type {}");
        }
        LocalProjectRegistry registry = new LocalProjectRegistry(root.resolve("registry"));
        RegisteredProject project = registry.registerProject(projectRoot, PROJECT);
        FileSymbolSnapshotStore snapshots = new FileSymbolSnapshotStore(root.resolve("snapshots"));
        snapshots.publish(project.id(), "snapshot-perf05",
                List.of(symbol(project, "api"), symbol(project, "app")), List.of(), List.of());
        query = LocalProjectArchitectureQuery.defaults(registry, countingStore(snapshots), countingDiscovery());
    }

    @Test
    void aDotGraphWithAModuleDiscoversOnceAndLoadsTheSnapshotOnce() throws Exception {
        String expected = ArchitectureResultRenderer.renderGraph(query.getArchitectureIntelligence(PROJECT),
                query.getModuleContext(PROJECT, "api").module().id(), ArchitectureResultRenderer.GraphFormat.DOT);
        resetCounters();
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = new ArchitectureCommand(query)
                .run(new String[]{PROJECT, "--format", "dot", "--module", "api"}, output, error);

        assertEquals(FindSymbolCommand.SUCCESS, exitCode, error::toString);
        assertEquals(expected + "\n", output.toString());
        assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                "project discoveries and active snapshot loads for one graph command with a module");
    }

    @Test
    void anUnknownModuleKeepsItsPublicErrorAfterASingleDiscovery() throws Exception {
        StringBuilder moduleContextError = new StringBuilder();
        int moduleContextExitCode = new ArchitectureCommand(query)
                .run(new String[]{PROJECT, "--module", "absent"}, new StringBuilder(), moduleContextError);
        resetCounters();
        StringBuilder output = new StringBuilder();
        StringBuilder error = new StringBuilder();

        int exitCode = new ArchitectureCommand(query)
                .run(new String[]{PROJECT, "--format", "mermaid", "--module", "absent"}, output, error);

        assertEquals(moduleContextExitCode, exitCode);
        assertEquals("error: architecture failed: unknown module: absent\n", error.toString());
        assertEquals(moduleContextError.toString(), error.toString());
        assertEquals("", output.toString());
        assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                "project discoveries and active snapshot loads for one refused graph command");
    }

    private void resetCounters() {
        discoveries.set(0);
        snapshotLoads.set(0);
    }

    /** The added detector recognises no module: it counts the visit of the root, done once per discovery. */
    private ProjectDiscoveryService countingDiscovery() {
        List<ProjectDetector> detectors = new ArrayList<>();
        detectors.add((projectRoot, directory, ignorePolicy) -> {
            if (directory.equals(projectRoot)) {
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
