package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.ProviderRuntimeStatus;
import com.minos.runtime.StrongProcessOwnershipIndexerExecutor;
import com.minos.runtime.WorkerResourceContainment;
import com.minos.runtime.WorkerSandboxBackend;
import com.minos.runtime.WorkerSandboxQualification;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrongOwnedProcessExecutorsQualificationTest {

    @Test
    void supervisedFilesystemQuotaKeepsManagedLocalProviderReadyWithoutClaimingHostileCode() {
        WorkerResourceContainment containment = new WorkerResourceContainment(
                "fixture-local-provider-boundary",
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of("fixture"));
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "fixture-local-provider",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                containment,
                Map.of(
                        WorkerSandboxQualification.currentPlatform(),
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                List.of());
        WorkerSandboxBackend backend = backend(qualification);
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus(
                "fixture-provider", "1.0.0", ProviderRuntimeStatus.State.READY,
                Optional.of(Path.of("fixture-provider")), List.of());

        ProviderRuntimeStatus qualified = StrongOwnedProcessExecutors.qualifyOwnership(
                ready,
                StrongProcessOwnershipIndexerExecutor.Capability.available("fixture-job-boundary"),
                backend);

        assertFalse(backend.supportsUntrustedCode(),
                "supervised filesystem quotas must not become hostile-code support");
        assertTrue(backend.supportsManagedLocalProvider(),
                "managed local provider execution has an explicit narrower qualification");
        assertTrue(qualified.ready(),
                "the production provider composition must not self-block on its own documented supervised quota");
    }

    @Test
    void missingOsSandboxStillBlocksManagedLocalProvider() {
        ProviderRuntimeStatus ready = new ProviderRuntimeStatus(
                "fixture-provider", "1.0.0", ProviderRuntimeStatus.State.READY,
                Optional.of(Path.of("fixture-provider")), List.of());

        ProviderRuntimeStatus qualified = StrongOwnedProcessExecutors.qualifyOwnership(
                ready,
                StrongProcessOwnershipIndexerExecutor.Capability.available("fixture-job-boundary"),
                WorkerSandboxBackend.nativeEphemeralWorkspace());

        assertFalse(qualified.ready());
        assertTrue(qualified.diagnostics().stream()
                .anyMatch(value -> value.contains("qualified managed local provider sandbox is unavailable")));
    }

    private static WorkerSandboxBackend backend(WorkerSandboxQualification qualification) {
        return new WorkerSandboxBackend() {
            @Override public String id() { return qualification.backendId(); }
            @Override public WorkerIsolation isolation() { return qualification.isolation(); }
            @Override public NetworkGuarantee networkGuarantee() { return qualification.networkGuarantee(); }
            @Override public WorkerSandboxQualification qualification() { return qualification; }

            @Override
            public IndexingArtifact execute(
                    IndexerExecutor delegate,
                    IndexingExecutionRequest request,
                    WorkerNetworkPolicy networkPolicy
            ) {
                throw new AssertionError("execution is outside the composition qualification test");
            }
        };
    }
}
