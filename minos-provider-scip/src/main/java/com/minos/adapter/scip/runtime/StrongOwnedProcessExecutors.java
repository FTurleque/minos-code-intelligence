package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.IndexerProcessPlanFactory;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.StrongProcessOwnershipIndexerExecutor;

import java.nio.file.Path;

/** Applies the fail-closed strong process-ownership policy to every managed SCIP provider. */
final class StrongOwnedProcessExecutors {

    private StrongOwnedProcessExecutors() {
    }

    static IndexerExecutor required(
            String providerId,
            Path minosHome,
            IndexerProcessPlanFactory planFactory
    ) {
        ProcessIndexerExecutor processExecutor = new ProcessIndexerExecutor(providerId, minosHome, planFactory);
        return new StrongProcessOwnershipIndexerExecutor(processExecutor, minosHome);
    }
}
