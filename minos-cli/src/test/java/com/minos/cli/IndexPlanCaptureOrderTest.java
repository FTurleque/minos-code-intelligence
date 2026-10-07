package com.minos.cli;

import com.minos.application.MinosApplication;
import com.minos.discovery.DefaultDiscoveryPlugins;
import com.minos.discovery.ProjectDiscovery.SourceRoot;
import com.minos.discovery.ProjectDiscoveryService;
import com.minos.discovery.spi.SourceRootDetector;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.ProjectIndexState;
import com.minos.registry.RegisteredProject;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MINOS-AUD-D06 pour la CLI : {@code prepare} capturait l'empreinte de référence APRÈS la découverte. Une structure
 * créée pendant la découverte (ici un module) entrait dans l'empreinte « courante » sans avoir été découverte, et le
 * plan la comparait à la baseline comme si elle avait été vue. Capturée AVANT, l'empreinte ne contient pas ce que la
 * découverte n'a pas vu ; le changement est détecté au run suivant. Le service de découverte injecté est celui du
 * constructeur de {@code MinosApplication}.
 */
class IndexPlanCaptureOrderTest {

    @TempDir Path temp;

    @Test
    void aModuleCreatedDuringDiscoveryIsNotPartOfTheReferenceFingerprintOfThePlan() throws Exception {
        Path home = temp.resolve("home");
        Path root = Files.createDirectories(temp.resolve("project"));
        List<SourceRootDetector> detectors = new ArrayList<>(DefaultDiscoveryPlugins.sourceRootDetectors());
        detectors.add(new ModuleAppearingDuringDiscovery(root));
        ProjectDiscoveryService discovery = new ProjectDiscoveryService(
                DefaultDiscoveryPlugins.projectDetectors(),
                DefaultDiscoveryPlugins.buildSystemDetectors(),
                detectors,
                DefaultDiscoveryPlugins.languageDetectors());

        try (MinosApplication application = MinosApplication.builder(home)
                .providerRuntimeManager(readyRuntime())
                .discoveryService(discovery)
                .build()) {
            RegisteredProject project = indexedBaseline(application, root);
            LocalAutonomousIndexOperations operations =
                    new LocalAutonomousIndexOperations(application, UnaryOperator.identity());

            AutonomousIndexOperations.IndexPlanView plan = operations.plan(project.id().toString(), null, false);

            assertTrue(Files.isRegularFile(root.resolve("late/pom.xml")), "the module did appear during discovery");
            assertEquals(IndexingMode.NONE, plan.mode(),
                    "the reference fingerprint was taken before the discovery: the new module is not in it");
        }
    }

    private RegisteredProject indexedBaseline(MinosApplication application, Path root) throws IOException {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Path source = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(source.resolve("Demo.java"), "package demo; public class Demo { }\n");
        RegisteredProject project = application.projectRegistry().registerProject(root, "demo");
        CodeKnowledgeSnapshot snapshot = application.snapshotStore().publish(
                project.id(), "snapshot-1", List.of(), List.of(), List.of());
        application.indexStateStore().saveProjectState(new ProjectIndexState(project.id(),
                ProjectIndexState.Availability.READY, Optional.of(snapshot.snapshotId()), Optional.empty(),
                Instant.parse("2026-09-30T08:00:00Z"), Optional.empty()));
        var fingerprint = application.fingerprintService().capture(project.rootPath());
        application.fingerprintStore().publish(project.id(), snapshot.snapshotId(), fingerprint);
        application.fingerprintStore().promote(project.id(), snapshot.snapshotId());
        return project;
    }

    private static ProviderRuntimeManager readyRuntime() {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus("scip-java", "1.0", ProviderRuntimeStatus.State.READY,
                Optional.empty(), List.of(), true);
        return new ProviderRuntimeManager() {
            @Override public List<ProviderRuntimeStatus> list() { return List.of(ready); }
            @Override public ProviderRuntimeStatus inspect(String providerId) { return ready; }
            @Override public ProviderRuntimeStatus install(String providerId) { return ready; }
            @Override public IndexerExecutor executor(String providerId) {
                throw new IllegalStateException("a plan never reaches a provider");
            }
        };
    }

    /** Appelé par la découverte une fois la marche des modules finie : un module apparaît à ce moment précis. */
    private static final class ModuleAppearingDuringDiscovery implements SourceRootDetector {
        private final Path project;
        private boolean created;

        private ModuleAppearingDuringDiscovery(Path project) {
            this.project = project;
        }

        @Override
        public List<SourceRoot> detect(Path projectRoot, Path moduleRoot,
                                       com.minos.discovery.ProjectIgnorePolicy ignorePolicy) throws IOException {
            if (!created) {
                created = true;
                Files.createDirectories(project.resolve("late"));
                Files.writeString(project.resolve("late/pom.xml"), "<project/>");
            }
            return List.of();
        }
    }
}
