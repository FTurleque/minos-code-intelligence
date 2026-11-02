package com.minos.adapter.scip.runtime.install;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.local.process.IndexerProcessPlanFactory;
import com.minos.runtime.local.process.ProcessIndexerExecutor;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.local.StrongProcessOwnershipIndexerExecutor;
import com.minos.runtime.local.sandbox.WorkerSandboxBackend;
import com.minos.runtime.local.sandbox.WorkerSandboxBackends;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

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
        Supplier<WorkerSandboxBackend> sandbox =
                () -> WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(minosHome);
        return qualifyOwnership(status, sandbox, isDockerRuntimeLocation());
    }

    /**
     * Selects the sandbox only for a status it can change: a ready one on any backend, and a not-ready one on
     * the Docker backend, whose disposition of machine prerequisites depends on the sandbox tier.
     */
    static ProviderRuntimeStatus qualifyOwnership(
            ProviderRuntimeStatus status,
            Supplier<WorkerSandboxBackend> sandbox,
            boolean dockerBackend
    ) {
        if (!status.ready() && !dockerBackend) return status;
        return qualifySandbox(status, sandbox.get(), dockerBackend);
    }

    /**
     * Package-visible seam used to lock the production composition contract without OS assumptions.
     * Defaults to the native disposition: a missing sandbox always {@link #blocked}. Existing callers
     * (and every test written before the Docker MCP backend existed) keep exactly today's behavior.
     */
    static ProviderRuntimeStatus qualifySandbox(
            ProviderRuntimeStatus status,
            WorkerSandboxBackend sandbox
    ) {
        return qualifySandbox(status, sandbox, false);
    }

    /**
     * <p>On a native host, a provider whose managed-local-provider sandbox is unqualified is a real,
     * blocking failure -- {@code sandbox.id()} should have been a genuinely qualified backend on that
     * platform. Inside the Docker MCP backend, the container itself is already the hardened boundary
     * for this narrower (not remote-worker) contract, and it cannot nest a second OS sandbox inside
     * itself by construction -- that is expected, not broken, so it must never count as a blocking
     * failure. Either way, the provider must never be reported READY when the sandbox check itself
     * did not pass -- only the failure disposition (fatal vs. informational-and-non-blocking) differs
     * by backend. See {@link ProviderRuntimeStatus.State#UNSUPPORTED_BY_BACKEND}.</p>
     */
    static ProviderRuntimeStatus qualifySandbox(
            ProviderRuntimeStatus status,
            WorkerSandboxBackend sandbox,
            boolean dockerBackend
    ) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(sandbox, "sandbox");
        boolean dockerNeverRuns = dockerBackend && !sandbox.supportsManagedLocalProvider();
        if (!status.ready()) {
            return dockerNeverRuns && blockedOnlyByMachinePrerequisites(status)
                    ? unsupportedByBackend(status, dockerUnsupportedDiagnostic(sandbox))
                    : status;
        }
        if (dockerNeverRuns) return unsupportedByBackend(status, dockerUnsupportedDiagnostic(sandbox));
        if (!sandbox.supportsManagedLocalProvider()) {
            return blocked(status, "qualified managed local provider sandbox is unavailable: " + sandbox.id());
        }
        return status;
    }

    private static String dockerUnsupportedDiagnostic(WorkerSandboxBackend sandbox) {
        return "managed local provider sandbox tier is not provided by the Docker MCP backend "
                + "(the container itself is the hardened boundary for this plane; this is "
                + "not a failure and does not block installation or use): " + sandbox.id();
    }

    /**
     * A {@code BLOCKED} status whose diagnostics are only machine prerequisites of running the provider (a
     * designated Maven repository, a project JDK) and the informational origin of its tools. The Docker
     * plane runs no provider whose sandbox tier it lacks, so such prerequisites cannot block it there; any
     * other diagnostic (integrity, a missing MINOS-owned component) keeps the status blocked.
     */
    private static boolean blockedOnlyByMachinePrerequisites(ProviderRuntimeStatus status) {
        if (status.state() != ProviderRuntimeStatus.State.BLOCKED) return false;
        boolean anyPrerequisite = false;
        for (String diagnostic : status.diagnostics()) {
            if (diagnostic.startsWith(ExternalPrerequisite.PREFIX)) {
                anyPrerequisite = true;
            } else if (!diagnostic.startsWith(ManagedScipProviderRuntimeManager.TOOLS_ORIGIN_PREFIX)) {
                return false;
            }
        }
        return anyPrerequisite;
    }

    /**
     * {@code minos.runtime.location} system property first, then {@code MINOS_RUNTIME_LOCATION} --
     * matching {@code com.minos.domain.registry.ProjectPathMapping}'s established precedence. Read directly
     * rather than shared with that class: it lives in minos-application, which does not sit below
     * minos-provider-scip/minos-runtime-local in the module graph.
     */
    private static boolean isDockerRuntimeLocation() {
        String property = System.getProperty("minos.runtime.location");
        String configured = (property == null || property.isBlank())
                ? System.getenv("MINOS_RUNTIME_LOCATION")
                : property;
        return configured != null && "docker".equalsIgnoreCase(configured.trim());
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
        return withState(status, ProviderRuntimeStatus.State.BLOCKED, diagnostic);
    }

    private static ProviderRuntimeStatus unsupportedByBackend(ProviderRuntimeStatus status, String diagnostic) {
        return withState(status, ProviderRuntimeStatus.State.UNSUPPORTED_BY_BACKEND, diagnostic);
    }

    private static ProviderRuntimeStatus withState(
            ProviderRuntimeStatus status,
            ProviderRuntimeStatus.State state,
            String diagnostic
    ) {
        List<String> diagnostics = new ArrayList<>(status.diagnostics());
        diagnostics.add(diagnostic);
        return new ProviderRuntimeStatus(
                status.providerId(),
                status.version(),
                state,
                status.executable(),
                diagnostics,
                status.requiredByDefault());
    }
}
