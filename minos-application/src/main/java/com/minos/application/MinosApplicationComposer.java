package com.minos.application;

import com.minos.git.GitIntelligence;
import com.minos.hosted.HostedControlPlaneStore;
import com.minos.hosted.HostedTenantKeyProvider;
import com.minos.orchestration.IndexerDescriptor;
import com.minos.orchestration.IndexerProviderCatalog;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotPromoter;
import com.minos.orchestration.IndexingRuntimePorts.SnapshotStager;
import com.minos.orchestration.ResumableRunMarkers;
import com.minos.orchestration.ScipArtifactImporter;
import com.minos.registry.ProjectPathMappings;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer;
import com.minos.runtime.HostCommandLocator;
import com.minos.runtime.ProviderRuntimeManager;
import com.minos.runtime.WorkerSandboxProbe;
import com.minos.storage.StorageBackend;
import com.minos.storage.StorageBackendConfiguration;
import com.minos.store.CodeKnowledgeSnapshotStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Port of the composition root (ADR 0042): supplies the default concrete adapters that
 * {@link MinosApplication#open(Path)} and {@link MinosApplication.Builder#build()} do not receive
 * explicitly. {@code minos-application} only knows this contract; its single implementation lives
 * in the {@code minos-bootstrap} module and is discovered by {@link java.util.ServiceLoader}
 * ({@link MinosApplicationComposers}).
 *
 * <p>Each method is called exactly where the assembler instantiated the concrete adapter, with the
 * same arguments: the initialization order does not depend on this port.</p>
 */
public interface MinosApplicationComposer {

    /** Configured backend: {@code local} built in, the others discovered by {@code ServiceLoader}. */
    StorageBackend openStorageBackend(StorageBackendConfiguration configuration) throws IOException;

    /** Fallback local backend, when only some stores are overridden. */
    StorageBackend openLocalStorageBackend(Path home) throws IOException;

    /** Descriptors of the default qualified indexers. */
    List<IndexerDescriptor> qualifiedIndexerDescriptors();

    /** Catalog of the qualified providers, read on demand. */
    IndexerProviderCatalog providerCatalog();

    /** Composite manager of the managed provider runtimes, for this MINOS_HOME. */
    ProviderRuntimeManager providerRuntimeManager(Path home);

    /** Default snapshot lifecycle (staging and promotion). */
    SnapshotLifecycle snapshotLifecycle(
            Path home,
            CodeKnowledgeSnapshotStore snapshots,
            List<IndexerDescriptor> descriptors
    ) throws IOException;

    /** Factual Git intelligence. */
    GitIntelligence gitIntelligence();

    /** Explicit SCIP import ({@code import-scip}). */
    ScipArtifactImporter scipArtifactImporter();

    /** Hosted control plane store, under the given directory. */
    HostedControlPlaneStore hostedControlPlaneStore(Path directory, HostedTenantKeyProvider keys) throws IOException;

    /** Hosted keys read from the operator environment (hosted mode enabled). */
    HostedTenantKeyProvider environmentHostedTenantKeyProvider();

    /** Resumable run markers (ADR 0039 section 5), kept in the run directory of this MINOS_HOME. */
    ResumableRunMarkers resumableRunMarkers(Path home);

    /** Worker sandbox probe of the real host (doctor, remote index). */
    WorkerSandboxProbe workerSandboxProbe();

    /** Lookup and invocation of the executables of the real host. */
    HostCommandLocator hostCommandLocator();

    /** Materialization of remote revisions, for this MINOS_HOME. */
    RemoteRepositoryMaterializer remoteRepositoryMaterializer(Path home) throws IOException;

    /**
     * Remote indexing engine for this MINOS_HOME: sandbox selection of the real host, isolated worker
     * and verified artifact transport.
     */
    RemoteIndexingRuntime remoteIndexingRuntime(Path home) throws IOException;

    /** WSL2 indexing route, empty when the host does not offer it (outside Windows, or route not shipped). */
    default Optional<WslIndexingRoute> wslIndexingRoute(Path home) {
        Objects.requireNonNull(home, "home");
        return Optional.empty();
    }

    /** Probe of the WSL2 route prerequisites (doctor), empty when the host does not offer the route. */
    default Optional<WslPrerequisiteDiagnostics> wslPrerequisiteDiagnostics() {
        return Optional.empty();
    }

    /** Persisted host/container root mapping of this MINOS_HOME (Docker plan). */
    ProjectPathMappings projectPathMappings(Path home);

    /** Snapshot staging and promotion, supplied together. */
    record SnapshotLifecycle(SnapshotStager stager, SnapshotPromoter promoter) {
        public SnapshotLifecycle {
            Objects.requireNonNull(stager, "stager");
            Objects.requireNonNull(promoter, "promoter");
        }
    }
}
