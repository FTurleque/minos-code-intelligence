package com.minos.adapter.scip.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.ProviderRuntimeStatus;
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
        ProviderRuntimeStatus ready = readyStatus();

        ProviderRuntimeStatus qualified = StrongOwnedProcessExecutors.qualifySandbox(ready, backend);

        assertFalse(backend.supportsUntrustedCode(),
                "supervised filesystem quotas must not become hostile-code support");
        assertTrue(backend.supportsManagedLocalProvider(),
                "managed local provider execution has an explicit narrower qualification");
        assertTrue(qualified.ready(),
                "the production provider composition must not self-block on its own documented supervised quota");
    }

    @Test
    void managedReadinessDependsOnTheSandboxActuallyUsedAtExecution() {
        WorkerResourceContainment containment = new WorkerResourceContainment(
                "fixture-qualified-job",
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
                "fixture-execution-sandbox",
                WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                containment,
                Map.of(
                        WorkerSandboxQualification.currentPlatform(),
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                List.of());

        ProviderRuntimeStatus qualified = StrongOwnedProcessExecutors.qualifySandbox(
                readyStatus(), backend(qualification));

        assertTrue(qualified.ready(),
                "an unrelated ownership-only launcher must not be a second READY authority for managed execution");
    }

    @Test
    void missingOsSandboxStillBlocksManagedLocalProvider() {
        ProviderRuntimeStatus qualified = StrongOwnedProcessExecutors.qualifySandbox(
                readyStatus(),
                WorkerSandboxBackend.nativeEphemeralWorkspace());

        assertFalse(qualified.ready());
        assertTrue(qualified.diagnostics().stream()
                .anyMatch(value -> value.contains("qualified managed local provider sandbox is unavailable")));
    }

    private static ProviderRuntimeStatus readyStatus() {
        return new ProviderRuntimeStatus(
                "fixture-provider", "1.0.0", ProviderRuntimeStatus.State.READY,
                Optional.of(Path.of("fixture-provider")), List.of());
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
