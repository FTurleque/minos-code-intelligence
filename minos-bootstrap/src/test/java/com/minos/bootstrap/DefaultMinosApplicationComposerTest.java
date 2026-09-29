package com.minos.bootstrap;

import com.minos.application.MinosApplication;
import com.minos.application.MinosApplicationComposer;
import com.minos.application.MinosApplicationComposers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A2 / ADR 0042 — minos-bootstrap enregistre exactement une racine de composition, utilisée par open(). */
class DefaultMinosApplicationComposerTest {

    @Test
    void theModuleRegistersExactlyOneCompositionRoot() {
        assertEquals(1, ServiceLoader.load(MinosApplicationComposer.class).stream().count());
        assertInstanceOf(DefaultMinosApplicationComposer.class, MinosApplicationComposers.resolve());
    }

    /**
     * V48 (a) — un hôte embarqué peut exécuter MINOS avec un chargeur de contexte qui ne voit pas
     * minos-bootstrap. Avant A2 l'ouverture locale ne dépendait d'aucun ServiceLoader : elle ne doit pas
     * dépendre de ce chargeur-là.
     */
    @Test
    void openDoesNotDependOnTheThreadContextClassLoader(@TempDir Path temp) throws Exception {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader foreign = new URLClassLoader(new URL[0], ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(foreign);
            try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
                assertEquals("local", application.storageBackendId());
            }
            assertInstanceOf(DefaultMinosApplicationComposer.class, MinosApplicationComposers.resolve());
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    @Test
    void openComposesTheLocalApplicationThroughTheDiscoveredRoot(@TempDir Path temp) throws Exception {
        try (MinosApplication application = MinosApplication.open(temp.resolve("home"))) {
            assertEquals("local", application.storageBackendId());
        }
    }

    /** L1 — les marqueurs de run reprenable fournis par la composition sont ceux du répertoire de run R1. */
    @Test
    void resumableRunMarkersAreTheRunDirectoryMarkersOfTheHome(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("home");
        java.util.UUID runId = java.util.UUID.fromString("0f0f0f0f-0000-4000-8000-000000000001");
        com.minos.orchestration.ResumableRunMarkers markers =
                new DefaultMinosApplicationComposer().resumableRunMarkers(home);
        com.minos.runtime.local.FileResumableRunMarkers files = new com.minos.runtime.local.FileResumableRunMarkers(home);

        assertEquals(java.util.Optional.of(files.runDirectory(runId)), markers.runDirectory(runId));
        try (MinosApplication application = MinosApplication.open(home)) {
            assertInstanceOf(RunDirectoryResumableRunMarkers.class,
                    application.compositionRoot().resumableRunMarkers(application.home()));
        }
    }

    /** L2 — la sonde de production interroge l'hôte réel ; la cause « décision » suit l'énumération. */
    @Test
    void productionWorkerSandboxProbeAndCommandLocatorAreTheRealHostOnes(@TempDir Path temp) {
        Path home = temp.resolve("home");
        DefaultMinosApplicationComposer composer = new DefaultMinosApplicationComposer();
        com.minos.runtime.WorkerSandboxProbe probe = composer.workerSandboxProbe();
        com.minos.runtime.local.WorkerSandboxBackend managedLocal =
                com.minos.runtime.local.WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(home);
        com.minos.runtime.local.WorkerSandboxSelection untrusted =
                com.minos.runtime.local.WorkerSandboxBackends.selectForUntrustedCode(home);

        assertEquals(new com.minos.runtime.WorkerSandboxProbe.ManagedLocalSandbox(
                managedLocal.id(), managedLocal.supportsManagedLocalProvider()), probe.managedLocalProvider(home));
        com.minos.runtime.WorkerSandboxProbe.UntrustedCodeSandbox assessed = probe.untrustedCode(home);
        assertEquals(untrusted.backend().id(), assessed.backendId());
        assertEquals(untrusted.supportsUntrustedCode(), assessed.supportsUntrustedCode());
        assertEquals(untrusted.cause().name(), assessed.cause());
        assertEquals(untrusted.rejectedBackendId(), assessed.rejectedBackendId());
        assertEquals(untrusted.rejectionReasons(), assessed.rejectionReasons());
        assertEquals(untrusted.supportsUntrustedCode() ? "" : untrusted.refusalReport(), assessed.refusalReport());
        assertEquals(com.minos.runtime.local.WorkerSandboxSelection.Cause.REJECTED_BY_DECISION.name(),
                com.minos.runtime.WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION);
        assertEquals(com.minos.runtime.local.CommandLocator.find("java"), composer.hostCommandLocator().find("java"));
        assertEquals(com.minos.runtime.local.CommandLocator.invocation(Path.of("docker"), "version"),
                composer.hostCommandLocator().invocation(Path.of("docker"), "version"));
    }

    /** Port de minos-app — la correspondance de chemins de production est le magasin fichier du home. */
    @Test
    void projectPathMappingsAreTheFileStoreOfTheHome(@TempDir Path temp) throws Exception {
        Path home = java.nio.file.Files.createDirectories(temp.resolve("home"));
        com.minos.registry.ProjectPathMapping mapping = new com.minos.registry.ProjectPathMapping("N:/workspace-dev", "/workspace/projects");

        new DefaultMinosApplicationComposer().projectPathMappings(home).save(mapping);

        assertEquals(java.util.Optional.of(mapping), new com.minos.storage.local.registry.ProjectPathMappingStore(home).loadOptional());
        assertInstanceOf(com.minos.storage.local.registry.ProjectPathMappingStore.class,
                new DefaultMinosApplicationComposer().projectPathMappings(home));
    }
}
