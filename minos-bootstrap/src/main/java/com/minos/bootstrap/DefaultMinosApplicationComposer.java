package com.minos.bootstrap;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.adapter.scip.ingestion.ScipSymbolSnapshotImporter;
import com.minos.adapter.scip.runtime.install.ManagedPolyglotScipRuntimeManager;
import com.minos.adapter.scip.runtime.install.ManagedScipProviderRuntimeManager;
import com.minos.adapter.scip.runtime.install.ManagedScipPythonRuntimeManager;
import com.minos.adapter.scip.runtime.plan.ScipJavaMavenOffline;
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
import com.minos.storage.local.registry.LocalProjectPathMappings;
import com.minos.registry.ProjectPathMappings;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.runtime.local.CompositeProviderRuntimeManager;
import com.minos.runtime.HostCommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.WorkerSandboxProbe;
import com.minos.storage.local.LocalStorageBackend;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.storage.MinosRuntimeSettings;
import com.minos.store.CodeKnowledgeSnapshotStore;
import com.minos.storage.local.store.EnvironmentHostedTenantKeyProvider;
import com.minos.storage.local.store.FileHostedControlPlaneStore;
import com.minos.storage.local.store.FileSymbolSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Composition root of MINOS (ADR 0042): the only non-adapter module that knows the concrete adapter classes.
 * The application assembler calls each method at the point where it needs that adapter.
 *
 * <p>Registered by {@code META-INF/services/com.minos.application.MinosApplicationComposer}. The constructor has
 * no side effect: discovery may instantiate it without opening anything.</p>
 */
public final class DefaultMinosApplicationComposer implements MinosApplicationComposer {

    public DefaultMinosApplicationComposer() {
    }

    /** The exact {@code local} shortcut, then {@code ServiceLoader}: see {@link StorageBackendSelection}. */
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
        return ScipIndexerCatalog.qualifiedDescriptors();
    }

    /** Method reference: the catalog is read only on demand. */
    @Override
    public IndexerProviderCatalog providerCatalog() {
        return ScipIndexerCatalog::qualifiedProviders;
    }

    /**
     * Managers in the order scip-java, python, polyglot. The Maven repository of scip-java comes from the MINOS
     * settings (JVM property, environment, {@code MINOS_HOME/config/minos.properties}), never from the analysed project.
     */
    @Override
    public ProviderRuntimeManager providerRuntimeManager(Path home) {
        return new CompositeProviderRuntimeManager(List.of(
                new ManagedScipProviderRuntimeManager(
                        home, ScipJavaMavenOffline.configured(
                                scipJavaMavenRepository(home), home, () -> hostCommandLocator().find("mvn"))),
                new ManagedScipPythonRuntimeManager(home),
                new ManagedPolyglotScipRuntimeManager(home)));
    }

    /** The designated Maven repository of scip-java; an unreadable configuration is refused, never ignored. */
    private static String scipJavaMavenRepository(Path home) {
        try {
            return MinosRuntimeSettings.load(home).value(
                    ScipJavaMavenOffline.REPOSITORY_SETTING, ScipJavaMavenOffline.REPOSITORY_ENVIRONMENT);
        } catch (IOException unreadable) {
            throw new IllegalStateException("MINOS runtime configuration cannot be read", unreadable);
        }
    }

    @Override
    public SnapshotLifecycle snapshotLifecycle(
            Path home,
            CodeKnowledgeSnapshotStore snapshots,
            List<IndexerDescriptor> descriptors
    ) throws IOException {
        ScipProjectSnapshotLifecycle lifecycle = new ScipProjectSnapshotLifecycle(
                home, snapshots, FileSymbolSnapshotStore::new, descriptors);
        return new SnapshotLifecycle(lifecycle, lifecycle);
    }

    @Override
    public GitIntelligence gitIntelligence() {
        return new GitIntelligenceService();
    }

    /** A new importer for each import. */
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

    /** A new adapter for each request. */
    @Override
    public ResumableRunMarkers resumableRunMarkers(Path home) {
        return new RunDirectoryResumableRunMarkers(home);
    }

    /** The real host probe: no parameter, no substitution possible from production. */
    @Override
    public WorkerSandboxProbe workerSandboxProbe() {
        return new LocalWorkerSandboxProbe();
    }

    @Override
    public HostCommandLocator hostCommandLocator() {
        return new LocalHostCommandLocator();
    }

    /** JGit materialization in MINOS_HOME. */
    @Override
    public RemoteRepositoryMaterializer remoteRepositoryMaterializer(Path home) throws IOException {
        return new JGitRemoteRepositoryMaterializer(home);
    }

    /** Bundle store, isolated worker and real host selection. */
    @Override
    public RemoteIndexingRuntime remoteIndexingRuntime(Path home) throws IOException {
        return LocalRemoteIndexingRuntime.production(home);
    }

    /** The file store and local registry of MINOS_HOME. */
    @Override
    public ProjectPathMappings projectPathMappings(Path home) {
        return new LocalProjectPathMappings(home);
    }
}
