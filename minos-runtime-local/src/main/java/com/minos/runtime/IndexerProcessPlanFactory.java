package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.nio.file.Path;

/**
 * Construit la commande concrète d'un provider sans faire fuiter cette logique
 * dans l'orchestration MINOS.
 */
@FunctionalInterface
public interface IndexerProcessPlanFactory {

    IndexerProcessPlan create(IndexingExecutionRequest request, Path runDirectory) throws Exception;

    /**
     * Network access granted to the untrusted provider inside the qualified local sandbox.
     *
     * <p>DENY is the safe default. Providers that must resolve project dependencies during
     * indexing have to opt into ALLOW explicitly in their process-plan factory.</p>
     */
    default WorkerNetworkPolicy networkPolicy() {
        return WorkerNetworkPolicy.DENY;
    }
}
