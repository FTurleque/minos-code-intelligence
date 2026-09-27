package com.minos.bootstrap;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.runtime.ManagedPolyglotScipRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipPythonRuntimeManager;
import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.application.MinosApplicationComposer;
import com.minos.git.GitIntelligence;
import com.minos.git.GitIntelligenceService;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.runtime.CompositeProviderRuntimeManager;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.storage.LocalStorageBackend;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.StorageBackendProvider;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.EnvironmentHostedTenantKeyProvider;
import com.minos.store.FileHostedControlPlaneStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/**
 * Racine de composition de MINOS (ADR 0042) : le seul module non adaptateur qui connaît les classes
 * concrètes des adaptateurs. Chaque méthode reprend telle quelle l'instanciation que faisaient
 * {@code MinosApplicationAssembler}, {@code StorageBackends} et {@code MinosApplicationRuntimeConfiguration}
 * dans minos-application ; l'assembleur l'appelle exactement au même point.
 *
 * <p>Enregistré par {@code META-INF/services/com.minos.application.MinosApplicationComposer}. Le
 * constructeur n'a aucun effet de bord : la découverte peut l'instancier sans rien ouvrir.</p>
 */
public final class DefaultMinosApplicationComposer implements MinosApplicationComposer {

    public DefaultMinosApplicationComposer() {
    }

    /** Déplacé de {@code StorageBackends.open} : raccourci {@code local} exact, puis {@code ServiceLoader}. */
    @Override
    public StorageBackend openStorageBackend(StorageBackendConfiguration configuration) throws IOException {
        if ("local".equals(configuration.backend())) {
            return new LocalStorageBackend(configuration.home());
        }
        for (StorageBackendProvider provider : ServiceLoader.load(StorageBackendProvider.class)) {
            if (configuration.backend().equalsIgnoreCase(provider.id())) {
                return provider.open(configuration);
            }
        }
        throw new IOException("MINOS storage backend provider is not available: " + configuration.backend());
    }

    @Override
    public StorageBackend openLocalStorageBackend(Path home) throws IOException {
        return new LocalStorageBackend(home);
    }

    @Override
    public List<IndexerDescriptor> qualifiedIndexerDescriptors() {
        return ScipIndexerCatalog.qualifiedM24Descriptors();
    }

    /** Référence de méthode : le catalogue n'est lu qu'à la demande. */
    @Override
    public IndexerProviderCatalog providerCatalog() {
        return ScipIndexerCatalog::qualifiedM24Providers;
    }

    /** Gestionnaires dans l'ordre scip-java, python, polyglotte. */
    @Override
    public ProviderRuntimeManager providerRuntimeManager(Path home) {
        return new CompositeProviderRuntimeManager(List.of(
                new ManagedScipProviderRuntimeManager(home),
                new ManagedScipPythonRuntimeManager(home),
                new ManagedPolyglotScipRuntimeManager(home)));
    }

    @Override
    public SnapshotLifecycle snapshotLifecycle(
            Path home,
            CodeKnowledgeSnapshotStore snapshots,
            List<IndexerDescriptor> descriptors
    ) throws IOException {
        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(home, snapshots, descriptors);
        return new SnapshotLifecycle(lifecycle, lifecycle);
    }

    @Override
    public GitIntelligence gitIntelligence() {
        return new GitIntelligenceService();
    }

    /** Un importeur neuf par import, comme l'appel direct {@code new ScipSymbolSnapshotImporter()} d'origine. */
    @Override
    public ScipArtifactImporter scipArtifactImporter() {
        return (indexFile, request, snapshots) ->
                new ScipSymbolSnapshotImporter().importSnapshot(indexFile, request, snapshots);
    }

    @Override
    public HostedControlPlaneStore hostedControlPlaneStore(Path directory, HostedTenantKeyProvider keys)
            throws IOException {
        return new FileHostedControlPlaneStore(directory, keys);
    }

    @Override
    public HostedTenantKeyProvider environmentHostedTenantKeyProvider() {
        return new EnvironmentHostedTenantKeyProvider();
    }
}
