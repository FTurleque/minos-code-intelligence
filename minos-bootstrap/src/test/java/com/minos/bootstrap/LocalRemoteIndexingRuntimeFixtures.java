package com.minos.bootstrap;

import com.minos.remote.RemoteIndexingRuntime;
import com.minos.runtime.local.DistributedArtifactBundleStore;
import com.minos.runtime.local.WorkerSandboxSelection;

import java.util.function.Supplier;

/**
 * Fabrique de TEST (sources de test de minos-bootstrap, publiées par son test-jar) : construit le moteur
 * d'indexation distante avec une sélection de sandbox et une fabrique de workers choisies par le test.
 * Elle n'existe pas dans le JAR de production (A1-3) : aucun code qui embarque MINOS ne peut s'en servir
 * pour passer le refus anticipé de {@code remote index}.
 */
public final class LocalRemoteIndexingRuntimeFixtures {
    private LocalRemoteIndexingRuntimeFixtures() {
    }

    public static RemoteIndexingRuntime withSelection(
            DistributedArtifactBundleStore artifactStore,
            LocalRemoteIndexingRuntime.WorkerFactory workerFactory,
            Supplier<WorkerSandboxSelection> untrustedCodeSandbox
    ) {
        return new LocalRemoteIndexingRuntime(artifactStore, workerFactory, untrustedCodeSandbox);
    }
}
