package com.minos.bootstrap;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.Worker;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteIndexingRuntime;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.runtime.DistributedArtifactBundleStore;
import com.minos.runtime.DistributedIndexerExecutor;
import com.minos.runtime.LocalIsolatedIndexWorker;
import com.minos.runtime.WorkerSandboxBackends;
import com.minos.runtime.WorkerSandboxProbe;
import com.minos.runtime.WorkerSandboxSelection;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Moteur d'indexation distante M25 sur l'hôte local (ADR 0042) : reprend tel quel le câblage que
 * {@code LocalRemoteIndexOperations} (minos-cli) faisait lui-même — magasin de bundles
 * {@link DistributedArtifactBundleStore}, worker {@link LocalIsolatedIndexWorker}, exécuteur
 * {@link DistributedIndexerExecutor} et sélection {@link WorkerSandboxBackends#selectForUntrustedCode}.
 *
 * <p>La production passe par {@link #production(Path)}, qui sonde toujours l'hôte réel. Le constructeur
 * public ne sert qu'aux tests qui doivent faire atteindre le transport à leur worker : il exige une
 * sélection explicite, aucune valeur ne désactive le refus anticipé (V31).</p>
 */
public final class LocalRemoteIndexingRuntime implements RemoteIndexingRuntime {

    /** Fabrique de workers : {@link LocalIsolatedIndexWorker} en production, un worker de test sinon. */
    @FunctionalInterface
    public interface WorkerFactory {
        Worker create(String workerId, IndexerExecutor delegate, DistributedArtifactBundleStore store);
    }

    private final DistributedArtifactBundleStore artifactStore;
    private final WorkerFactory workerFactory;
    private final Supplier<WorkerSandboxSelection> untrustedCodeSandbox;

    /** Câblage de production : sélection réelle de l'hôte, worker isolé réel. */
    static LocalRemoteIndexingRuntime production(Path home) throws IOException {
        DistributedArtifactBundleStore store = new DistributedArtifactBundleStore(home);
        return new LocalRemoteIndexingRuntime(
                store,
                (workerId, delegate, bundleStore) -> new LocalIsolatedIndexWorker(workerId, home, delegate, bundleStore),
                () -> WorkerSandboxBackends.selectForUntrustedCode(home));
    }

    public LocalRemoteIndexingRuntime(
            DistributedArtifactBundleStore artifactStore,
            WorkerFactory workerFactory,
            Supplier<WorkerSandboxSelection> untrustedCodeSandbox
    ) {
        this.artifactStore = Objects.requireNonNull(artifactStore, "artifactStore");
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.untrustedCodeSandbox = Objects.requireNonNull(untrustedCodeSandbox, "untrustedCodeSandbox");
    }

    @Override
    public WorkerSandboxProbe.UntrustedCodeSandbox untrustedCodeSandbox() {
        return LocalWorkerSandboxProbe.assess(Objects.requireNonNull(
                untrustedCodeSandbox.get(), "untrusted-code sandbox selection"));
    }

    @Override
    public DistributedExecution distribute(
            String providerId,
            String providerVersion,
            RemoteMaterialization source,
            WorkerNetworkPolicy workerNetworkPolicy,
            String workerId,
            IndexerExecutor delegate
    ) {
        Worker worker = Objects.requireNonNull(
                workerFactory.create(workerId, delegate, artifactStore), "workerFactory result");
        return new Execution(new DistributedIndexerExecutor(
                providerId, providerVersion, source, workerNetworkPolicy, worker, artifactStore));
    }

    /** Transmet chaque appel à l'exécuteur distribué, et ses artefacts vérifiés sous forme de preuves. */
    private record Execution(DistributedIndexerExecutor executor) implements DistributedExecution {

        @Override
        public String indexerId() {
            return executor.indexerId();
        }

        @Override
        public IndexingArtifact execute(IndexingExecutionRequest request) throws Exception {
            return executor.execute(request);
        }

        @Override
        public List<VerifiedArtifactEvidence> verifiedArtifacts() {
            return executor.verifiedArtifacts().stream()
                    .map(artifact -> new VerifiedArtifactEvidence(
                            artifact.manifest(), artifact.bundleSha256(), artifact.cacheKey(), artifact.cacheHit()))
                    .toList();
        }

        @Override
        public void close() throws IOException {
            executor.close();
        }
    }
}
