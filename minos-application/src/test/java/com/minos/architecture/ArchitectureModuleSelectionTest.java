package com.minos.architecture;

import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDetector;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.registry.ProjectRegistry;
import com.minos.registry.RegisteredProject;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * AUD-PERF-05 : la vue d'architecture et le module d'un graphe filtré s'obtiennent par un seul chargement du
 * contexte du projet (une découverte, un chargement du snapshot actif), avec le résultat et les refus de
 * l'enchaînement {@code getArchitectureIntelligence} puis {@code getModuleContext} que garde l'opération par défaut
 * du port.
 */
class ArchitectureModuleSelectionTest {
    private static final String PROJECT = "perf05";
    private static final Origin ORIGIN = new Origin("fixture", "TEST", "1", "run-1", OriginType.OTHER);

    @TempDir
    Path root;

    private final AtomicInteger discoveries = new AtomicInteger();
    private final AtomicInteger snapshotLoads = new AtomicInteger();
    private LocalProjectArchitectureQuery query;

    @BeforeEach
    void indexesATwoModuleProject() throws Exception {
        Path projectRoot = Files.createDirectories(root.resolve("project"));
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>");
        for (String module : List.of("api", "app")) {
            Path sources = Files.createDirectories(projectRoot.resolve(module + "/src/main/java/com/acme/" + module));
            Files.writeString(projectRoot.resolve(module + "/pom.xml"), "<project/>");
            Files.writeString(sources.resolve("Type.java"), "package com.acme." + module + "; public class Type {}");
        }
        RegisteredProject project = new RegisteredProject(
                UUID.randomUUID(), projectRoot, PROJECT, Optional.empty(), Instant.EPOCH, Instant.EPOCH);
        CodeKnowledgeSnapshot snapshot = new CodeKnowledgeSnapshot(project.id(), "snapshot-perf05",
                List.of(symbol(project, "api"), symbol(project, "app")), List.of(), List.of());
        ProjectRegistry registry = fake(ProjectRegistry.class, (proxy, method, arguments) -> {
            if ("listProjects".equals(method.getName())) {
                return List.of(project);
            }
            throw new AssertionError("unexpected registry call: " + method.getName());
        });
        CodeKnowledgeSnapshotStore snapshots = fake(CodeKnowledgeSnapshotStore.class, (proxy, method, arguments) -> {
            if ("loadActiveKnowledge".equals(method.getName())) {
                snapshotLoads.incrementAndGet();
                return Optional.of(snapshot);
            }
            throw new AssertionError("unexpected snapshot store call: " + method.getName());
        });
        query = LocalProjectArchitectureQuery.defaults(registry, snapshots, countingDiscovery());
    }

    @Test
    void theLocalQueryLoadsTheProjectContextOnceAndAgreesWithTheTwoReads() throws Exception {
        ArchitectureIntelligenceView view = query.getArchitectureIntelligence(PROJECT);
        ArchitectureModuleContext api = query.getModuleContext(PROJECT, "api");
        discoveries.set(0);
        snapshotLoads.set(0);

        ArchitectureModuleSelection selection = query.getArchitectureIntelligenceForModule(PROJECT, "api");

        assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                "project discoveries and active snapshot loads");
        assertEquals(view, selection.view());
        assertEquals(api.module().id(), selection.moduleId());
        assertEquals(selection.moduleId(),
                query.getArchitectureIntelligenceForModule(PROJECT, selection.moduleId()).moduleId());
    }

    @Test
    void theLocalQueryRefusesAnUnknownOrBlankModuleLikeTheModuleContext() {
        for (String module : Arrays.asList("absent", " ", null)) {
            IllegalArgumentException expected = assertThrows(IllegalArgumentException.class,
                    () -> query.getModuleContext(PROJECT, module));
            discoveries.set(0);
            snapshotLoads.set(0);

            IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                    () -> query.getArchitectureIntelligenceForModule(PROJECT, module));

            assertEquals(expected.getMessage(), refusal.getMessage());
            assertEquals(List.of(1, 1), List.of(discoveries.get(), snapshotLoads.get()),
                    "project discoveries and active snapshot loads for a refused module");
        }
        assertEquals("unknown module: absent", assertThrows(IllegalArgumentException.class,
                () -> query.getArchitectureIntelligenceForModule(PROJECT, "absent")).getMessage());
    }

    @Test
    void thePortDefaultChainsTheTwoReadsAndGivesTheSameSelection() throws Exception {
        List<String> calls = new ArrayList<>();
        ProjectArchitectureQuery port = fake(ProjectArchitectureQuery.class, (proxy, method, arguments) -> {
            if (method.isDefault()) {
                return InvocationHandler.invokeDefault(proxy, method, arguments);
            }
            calls.add(method.getName());
            return switch (method.getName()) {
                case "getArchitectureIntelligence" -> query.getArchitectureIntelligence((String) arguments[0]);
                case "getModuleContext" -> query.getModuleContext((String) arguments[0], (String) arguments[1]);
                default -> throw new AssertionError("unexpected port call: " + method.getName());
            };
        });

        ArchitectureModuleSelection selection = port.getArchitectureIntelligenceForModule(PROJECT, "app");

        assertEquals(List.of("getArchitectureIntelligence", "getModuleContext"), calls);
        assertEquals(query.getArchitectureIntelligenceForModule(PROJECT, "app"), selection);
        assertEquals("unknown module: absent", assertThrows(IllegalArgumentException.class,
                () -> port.getArchitectureIntelligenceForModule(PROJECT, "absent")).getMessage());
    }

    @Test
    void aSelectionNeedsAViewAndAModuleIdentifier() throws Exception {
        ArchitectureIntelligenceView view = query.getArchitectureIntelligence(PROJECT);

        assertEquals("view", assertThrows(NullPointerException.class,
                () -> new ArchitectureModuleSelection(null, "module")).getMessage());
        assertEquals("moduleId must not be blank", assertThrows(IllegalArgumentException.class,
                () -> new ArchitectureModuleSelection(view, " ")).getMessage());
    }

    /** Le détecteur ajouté ne reconnaît aucun module : il compte la visite de la racine (une par découverte). */
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

    private static <T> T fake(Class<T> contract, InvocationHandler handler) {
        return contract.cast(Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[]{contract}, handler));
    }

    private static Symbol symbol(RegisteredProject project, String module) {
        return new Symbol("sym:" + module, "key:" + module, SymbolIdentityQuality.STRUCTURAL_FALLBACK,
                project.id().toString(), "main", module + "/src/main/java/com/acme/" + module + "/Type.java", null,
                SymbolKind.CLASS, "Type", "com.acme." + module + ".Type", null, "java", null,
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of());
    }
}
