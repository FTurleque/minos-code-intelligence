package com.minos.remote;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.remote.RemoteRepositoryMaterializer.RemoteMaterialization;
import com.minos.runtime.WorkerSandboxProbe;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Port : moteur d'exécution de l'indexation distante (M25) — sélection de sandbox pour du code non
 * fiable, worker isolé et transport d'artefacts vérifiés. {@code remote index} (minos-cli) n'en connaît
 * que ce contrat ; l'implémentation de production, câblée par minos-bootstrap, sonde l'hôte réel et
 * n'offre aucune valeur qui contournerait le refus anticipé (ADR 0041).
 */
public interface RemoteIndexingRuntime {

    /** Sélection de sandbox pour du code distant non fiable, évaluée à chaque appel. */
    WorkerSandboxProbe.UntrustedCodeSandbox untrustedCodeSandbox();

    /** Exécuteur distribué qui confie {@code delegate} à un worker et ne rend que des artefacts vérifiés. */
    DistributedExecution distribute(
            String providerId,
            String providerVersion,
            RemoteMaterialization source,
            WorkerNetworkPolicy workerNetworkPolicy,
            String workerId,
            IndexerExecutor delegate
    );

    /** Exécution distribuée d'un provider : exécuteur d'indexation, preuves d'artefacts, fermeture. */
    interface DistributedExecution extends IndexerExecutor, AutoCloseable {

        List<VerifiedArtifactEvidence> verifiedArtifacts();

        @Override
        void close() throws IOException;
    }

    /** Preuve d'un artefact vérifié : manifeste, empreinte du bundle, clé et statut de cache. */
    record VerifiedArtifactEvidence(
            DistributedArtifactManifest manifest,
            String bundleSha256,
            String cacheKey,
            boolean cacheHit
    ) {
        public VerifiedArtifactEvidence {
            Objects.requireNonNull(manifest, "manifest");
            Objects.requireNonNull(bundleSha256, "bundleSha256");
            Objects.requireNonNull(cacheKey, "cacheKey");
        }
    }
}
