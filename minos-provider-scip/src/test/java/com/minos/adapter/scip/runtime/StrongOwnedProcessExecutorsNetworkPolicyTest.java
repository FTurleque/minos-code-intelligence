package com.minos.adapter.scip.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.IndexerProcessPlanFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StrongOwnedProcessExecutorsNetworkPolicyTest {

    @Test
    void defaultFactoryPolicyKeepsRepositoryControlledDescendantsOffline() {
        IndexerProcessPlanFactory factory = (request, runDirectory) -> {
            throw new AssertionError("plan creation is not part of the policy test");
        };

        assertEquals(
                WorkerNetworkPolicy.DENY,
                StrongOwnedProcessExecutors.localNetworkPolicy(factory));
    }

    @Test
    void networkAccessExistsOnlyWhenTheFactoryOptsInExplicitly() {
        IndexerProcessPlanFactory factory = new IndexerProcessPlanFactory() {
            @Override
            public com.minos.runtime.IndexerProcessPlan create(
                    com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest request,
                    java.nio.file.Path runDirectory
            ) {
                throw new AssertionError("plan creation is not part of the policy test");
            }

            @Override
            public WorkerNetworkPolicy networkPolicy() {
                return WorkerNetworkPolicy.ALLOW;
            }
        };

        assertEquals(
                WorkerNetworkPolicy.ALLOW,
                StrongOwnedProcessExecutors.localNetworkPolicy(factory));
    }
}
