package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.runtime.IndexerProcessPlanFactory;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.StrongProcessOwnershipIndexerExecutor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Downgrades an otherwise-ready runtime when the host cannot provide the required ownership
     * boundary. Non-ready states keep their primary installation/qualification diagnosis.
     */
    static ProviderRuntimeStatus qualifyOwnership(ProviderRuntimeStatus status, Path minosHome) {
        if (!status.ready()) return status;
        StrongProcessOwnershipIndexerExecutor.Capability capability =
                StrongProcessOwnershipIndexerExecutor.detectCapability(minosHome);
        if (capability.strong()) return status;

        List<String> diagnostics = new ArrayList<>(status.diagnostics());
        diagnostics.add("strong process ownership is unavailable (" + capability.mechanism() + "): "
                + String.join("; ", capability.diagnostics()));
        return new ProviderRuntimeStatus(
                status.providerId(),
                status.version(),
                ProviderRuntimeStatus.State.BLOCKED,
                status.executable(),
                diagnostics,
                status.requiredByDefault());
    }
}
