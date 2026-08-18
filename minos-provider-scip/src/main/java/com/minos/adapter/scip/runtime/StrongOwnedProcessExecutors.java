package com.minos.adapter.scip.runtime;

import com.minos.adapter.scip.ScipIndexerCatalog;
import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.IndexerProcessPlanFactory;
import com.minos.runtime.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.StrongProcessOwnershipIndexerExecutor;
import com.minos.runtime.WorkerSandboxBackend;
import com.minos.runtime.WorkerSandboxBackends;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Applies the fail-closed local isolation policy to every managed SCIP provider. */
final class StrongOwnedProcessExecutors {

    private StrongOwnedProcessExecutors() {
    }

    static IndexerExecutor required(
            String providerId,
            Path minosHome,
            IndexerProcessPlanFactory planFactory
    ) {
        ProcessIndexerExecutor processExecutor = new ProcessIndexerExecutor(providerId, minosHome, planFactory);
        return new StrongProcessOwnershipIndexerExecutor(
                processExecutor,
                minosHome,
                localNetworkPolicy(providerId, planFactory));
    }

    /**
     * Downgrades an otherwise-ready runtime when the host cannot provide both strong descendant
     * ownership and the qualified OS sandbox used by production local indexing.
     */
    static ProviderRuntimeStatus qualifyOwnership(ProviderRuntimeStatus status, Path minosHome) {
        if (!status.ready()) return status;
        StrongProcessOwnershipIndexerExecutor.Capability ownership =
                StrongProcessOwnershipIndexerExecutor.detectCapability(minosHome);
        if (!ownership.strong()) {
            return blocked(status, "strong process ownership is unavailable (" + ownership.mechanism() + "): "
                    + String.join("; ", ownership.diagnostics()));
        }

        WorkerSandboxBackend sandbox = WorkerSandboxBackends.strongestAvailable(minosHome);
        if (!sandbox.supportsUntrustedCode()) {
            return blocked(status, "qualified local provider sandbox is unavailable: " + sandbox.id());
        }
        return status;
    }

    private static WorkerNetworkPolicy localNetworkPolicy(
            String providerId,
            IndexerProcessPlanFactory planFactory
    ) {
        return switch (providerId) {
            // These providers can legitimately resolve project dependencies while indexing.
            case "scip-java", ScipIndexerCatalog.SCIP_DOTNET_ID, ScipIndexerCatalog.SCIP_GO_ID,
                    ScipIndexerCatalog.RUST_ANALYZER_SCIP_ID -> WorkerNetworkPolicy.ALLOW;
            // scip-typescript, scip-python and scip-clang remain network-denied by default.
            default -> planFactory.networkPolicy();
        };
    }

    private static ProviderRuntimeStatus blocked(ProviderRuntimeStatus status, String diagnostic) {
        List<String> diagnostics = new ArrayList<>(status.diagnostics());
        diagnostics.add(diagnostic);
        return new ProviderRuntimeStatus(
                status.providerId(),
                status.version(),
                ProviderRuntimeStatus.State.BLOCKED,
                status.executable(),
                diagnostics,
                status.requiredByDefault());
    }
}
