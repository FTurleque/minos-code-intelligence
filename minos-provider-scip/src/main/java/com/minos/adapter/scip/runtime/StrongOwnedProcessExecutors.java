package com.minos.adapter.scip.runtime;

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
import java.util.Objects;

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
                localNetworkPolicy(planFactory));
    }

    /**
     * Downgrades an otherwise-ready runtime unless the exact sandbox selected by managed local
     * execution is qualified on this host.
     *
     * <p>The three-argument {@link StrongProcessOwnershipIndexerExecutor} does not execute through
     * its ownership-only boundary. It copies the project and then delegates directly to
     * {@link WorkerSandboxBackends#strongestAvailableForManagedLocalProvider(Path)}. Requiring a
     * second, unused ownership-only capability here would make READY describe a different execution
     * path from the one production actually uses. The managed sandbox already requires aggregate
     * process/memory/CPU and descendant ownership to be OS-enforced, so it is the single readiness
     * authority for this composition.</p>
     *
     * <p>This deliberately does not require {@code supportsUntrustedCode()}: the current Linux and
     * Windows backends enforce filesystem quotas through a supervised hard kill rather than a kernel
     * quota. That narrower contract is acceptable for managed local indexing, while remote/hostile
     * execution remains fail-closed on the stricter worker selector.</p>
     */
    static ProviderRuntimeStatus qualifyOwnership(ProviderRuntimeStatus status, Path minosHome) {
        if (!status.ready()) return status;
        WorkerSandboxBackend sandbox = WorkerSandboxBackends
                .strongestAvailableForManagedLocalProvider(minosHome);
        return qualifySandbox(status, sandbox);
    }

    /** Package-visible seam used to lock the production composition contract without OS assumptions. */
    static ProviderRuntimeStatus qualifySandbox(
            ProviderRuntimeStatus status,
            WorkerSandboxBackend sandbox
    ) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sandbox, "sandbox");
        if (!status.ready()) return status;
        if (!sandbox.supportsManagedLocalProvider()) {
            return blocked(status, "qualified managed local provider sandbox is unavailable: " + sandbox.id());
        }
        return status;
    }

    /**
     * A provider never receives network access merely because its ecosystem normally resolves
     * dependencies online. Maven wrappers, Maven plugins, MSBuild targets, Cargo build scripts and
     * equivalent repository-controlled hooks execute inside the same descendant tree as the
     * provider, so an implicit ALLOW would let untrusted project code exfiltrate the isolated source
     * copy. A process-plan factory that can prove it does not execute repository-controlled code may
     * opt into ALLOW explicitly; DENY remains the production default for every current SCIP factory.
     */
    static WorkerNetworkPolicy localNetworkPolicy(IndexerProcessPlanFactory planFactory) {
        return planFactory.networkPolicy();
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
