package com.minos.cli;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.WorkerResourceContainment;
import com.minos.runtime.WorkerResourceContainment.Disposition;
import com.minos.runtime.WorkerSandboxBackend;
import com.minos.runtime.WorkerSandboxQualification;
import com.minos.runtime.WorkerSandboxSelection;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Test-only, explicit choice to let {@link LocalRemoteIndexOperations} reach its worker transport:
 * a selection whose fake backend is fully OS-enforced, hence genuinely qualified for untrusted code
 * by {@link WorkerSandboxQualification}. It never executes anything. No production code can build
 * it, since no integrated backend has such a containment (ADR 0041).
 */
final class QualifiedSandboxForTests {

    private QualifiedSandboxForTests() {
    }

    static Supplier<WorkerSandboxSelection> selection() {
        return () -> WorkerSandboxSelection.of(backend());
    }

    static WorkerSandboxBackend backend() {
        return new WorkerSandboxBackend() {
            @Override public String id() { return "fake-hard-backend"; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public NetworkGuarantee networkGuarantee() { return NetworkGuarantee.OS_ENFORCED; }

            @Override
            public WorkerSandboxQualification qualification() {
                WorkerResourceContainment hard = new WorkerResourceContainment(
                        "fake-hard",
                        Disposition.OS_ENFORCED,
                        Disposition.OS_ENFORCED,
                        Disposition.OS_ENFORCED,
                        Disposition.SUPERVISED_HARD_KILL,
                        Disposition.OS_ENFORCED,
                        Disposition.OS_ENFORCED,
                        Disposition.OS_ENFORCED,
                        Disposition.SUPERVISED_HARD_KILL,
                        List.of("FAKE_KERNEL_QUOTA"));
                return new WorkerSandboxQualification(
                        id(), isolation(), networkGuarantee(),
                        WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                        WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                        hard,
                        Map.of(WorkerSandboxQualification.currentPlatform(),
                                WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                        List.of());
            }

            @Override
            public IndexingArtifact execute(
                    IndexerExecutor delegate, IndexingExecutionRequest request, WorkerNetworkPolicy policy) {
                throw new UnsupportedOperationException("the qualified test selection never executes a provider");
            }
        };
    }
}
