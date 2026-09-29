package com.minos.application;

import com.minos.git.GitIntelligence;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.store.CodeKnowledgeSnapshotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 / ADR 0042 — découverte de la racine de composition : échec explicite sans implémentation,
 * refus avec plusieurs. minos-application n'a pas minos-bootstrap sur son classpath de test : la
 * découverte réelle ({@code ServiceLoader}) y voit zéro implémentation.
 */
class MinosApplicationComposersTest {

    @Test
    void openFailsFastAndNamesTheMissingModuleWhenNoCompositionRootIsRegistered(@TempDir Path temp) {
        Path home = temp.resolve("home");

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> MinosApplication.open(home));

        assertEquals("MINOS composition root is missing: the minos-bootstrap module must be on the classpath"
                + " (no com.minos.application.MinosApplicationComposer service is registered)", failure.getMessage());
        assertFalse(Files.exists(home), "the lookup must fail before MINOS_HOME is created");
        assertNoPath(failure.getMessage(), temp);
        assertEquals(failure.getMessage(), assertThrows(IllegalStateException.class,
                () -> MinosApplication.builder(home).build()).getMessage());
        assertEquals(failure.getMessage(), assertThrows(IllegalStateException.class,
                () -> StorageBackends.open(new StorageBackendConfiguration("local", home, null, null, null, null)))
                .getMessage());
        assertFalse(Files.exists(home));
    }

    @Test
    void discoveryRefusesToChooseBetweenSeveralCompositionRoots(@TempDir Path temp) throws Exception {
        Path services = Files.createDirectories(temp.resolve("classes").resolve("META-INF").resolve("services"));
        Files.writeString(services.resolve(MinosApplicationComposer.class.getName()),
                SecondComposer.class.getName() + "\n" + FirstComposer.class.getName() + "\n",
                StandardCharsets.UTF_8);
        Path home = temp.resolve("home");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(
                new URL[]{temp.resolve("classes").toUri().toURL()}, MinosApplicationComposersTest.class.getClassLoader())) {
            // Découverte réelle (ServiceLoader) dans un chargeur qui voit deux racines.
            IllegalStateException failure = assertThrows(IllegalStateException.class,
                    () -> MinosApplicationComposers.resolve(loader));

            assertEquals("MINOS refuses to choose a composition root: 2 MinosApplicationComposer services are"
                    + " registered (" + FirstComposer.class.getName() + ", " + SecondComposer.class.getName()
                    + "); exactly one minos-bootstrap module must be on the classpath", failure.getMessage());
            assertNoPath(failure.getMessage(), temp);

            // V48 : positionnées par un hôte dans le chargeur de contexte, ces racines ne sont pas consultées.
            Thread.currentThread().setContextClassLoader(loader);
            assertEquals(MinosApplicationComposers.MISSING, assertThrows(IllegalStateException.class,
                    () -> MinosApplication.open(home)).getMessage());
            assertFalse(Files.exists(home), "the lookup must fail before MINOS_HOME is created");
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void assertNoPath(String message, Path temp) {
        assertFalse(message.contains(temp.toString()), message);
        assertFalse(message.contains(temp.toString().replace('\\', '/')), message);
        assertTrue(message.chars().noneMatch(character -> character == '\\'), message);
    }

    /** Implémentation factice : la découverte ne doit jamais l'utiliser quand elle en voit deux. */
    public static class FirstComposer implements MinosApplicationComposer {
        @Override public StorageBackend openStorageBackend(StorageBackendConfiguration configuration) { throw unused(); }
        @Override public StorageBackend openLocalStorageBackend(Path home) { throw unused(); }
        @Override public List<IndexerDescriptor> qualifiedIndexerDescriptors() { throw unused(); }
        @Override public IndexerProviderCatalog providerCatalog() { throw unused(); }
        @Override public ProviderRuntimeManager providerRuntimeManager(Path home) { throw unused(); }
        @Override public SnapshotLifecycle snapshotLifecycle(
                Path home, CodeKnowledgeSnapshotStore snapshots, List<IndexerDescriptor> descriptors) { throw unused(); }
        @Override public GitIntelligence gitIntelligence() { throw unused(); }
        @Override public ScipArtifactImporter scipArtifactImporter() { throw unused(); }
        @Override public HostedControlPlaneStore hostedControlPlaneStore(Path directory, HostedTenantKeyProvider keys) {
            throw unused();
        }
        @Override public HostedTenantKeyProvider environmentHostedTenantKeyProvider() { throw unused(); }
        @Override public ResumableRunMarkers resumableRunMarkers(Path home) { throw unused(); }
        @Override public com.minos.runtime.WorkerSandboxProbe workerSandboxProbe() { throw unused(); }
        @Override public com.minos.runtime.HostCommandLocator hostCommandLocator() { throw unused(); }
        @Override public com.minos.remote.RemoteRepositoryMaterializer remoteRepositoryMaterializer(Path home) {
            throw unused();
        }
        @Override public com.minos.remote.RemoteIndexingRuntime remoteIndexingRuntime(Path home) { throw unused(); }
        @Override public com.minos.registry.ProjectPathMappings projectPathMappings(Path home) { throw unused(); }

        private static AssertionError unused() {
            return new AssertionError("an ambiguous composition root must never be used");
        }
    }

    public static final class SecondComposer extends FirstComposer {
    }
}
