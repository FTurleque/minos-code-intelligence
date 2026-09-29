package com.minos.bootstrap;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.runtime.ManagedPolyglotScipRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.ManagedScipPythonRuntimeManager;
import com.minos.adapter.scip.runtime.ScipProjectSnapshotLifecycle;
import com.minos.application.MinosApplicationComposer;
import com.minos.git.GitIntelligence;
import com.minos.integration.git.GitIntelligenceService;
import com.minos.integration.git.JGitRemoteRepositoryMaterializer;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.registry.ProjectPathMappingStore;
import com.minos.registry.ProjectPathMappings;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.runtime.local.CompositeProviderRuntimeManager;
import com.minos.runtime.HostCommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.WorkerSandboxProbe;
import com.minos.storage.LocalStorageBackend;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.store.EnvironmentHostedTenantKeyProvider;
import com.minos.store.FileHostedControlPlaneStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

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

    /** Raccourci {@code local} exact, puis {@code ServiceLoader} : voir {@link StorageBackendSelection}. */
    @Override
    public StorageBackend openStorageBackend(StorageBackendConfiguration configuration) throws IOException {
        return StorageBackendSelection.open(configuration);
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

    /** Déplacé de minos-cli (LocalAutonomousIndexOperations) : un adaptateur neuf à chaque demande. */
    @Override
    public ResumableRunMarkers resumableRunMarkers(Path home) {
        return new RunDirectoryResumableRunMarkers(home);
    }

    /** Sonde réelle de l'hôte : aucun paramètre, aucune substitution possible depuis la production. */
    @Override
    public WorkerSandboxProbe workerSandboxProbe() {
        return new LocalWorkerSandboxProbe();
    }

    @Override
    public HostCommandLocator hostCommandLocator() {
        return new LocalHostCommandLocator();
    }

    /** Déplacé de minos-cli (LocalRemoteIndexOperations) : matérialisation JGit du MINOS_HOME. */
    @Override
    public RemoteRepositoryMaterializer remoteRepositoryMaterializer(Path home) throws IOException {
        return new JGitRemoteRepositoryMaterializer(home);
    }

    /** Déplacé de minos-cli : magasin de bundles, worker isolé et sélection réelle de l'hôte. */
    @Override
    public RemoteIndexingRuntime remoteIndexingRuntime(Path home) throws IOException {
        return LocalRemoteIndexingRuntime.production(home);
    }

    /** Déplacé de minos-app (DockerRuntimeBootstrap) : le magasin fichier du MINOS_HOME. */
    @Override
    public ProjectPathMappings projectPathMappings(Path home) {
        return new ProjectPathMappingStore(home);
    }
}
