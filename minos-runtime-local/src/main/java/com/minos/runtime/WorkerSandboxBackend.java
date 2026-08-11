package com.minos.runtime;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;

import java.util.Objects;

/**
 * Execution boundary for a worker sandbox implementation.
 *
 * <p>The native backend deliberately exposes no OS network guarantee. A backend may advertise
 * {@link NetworkGuarantee#OS_ENFORCED} only when it actually launches the provider through an
 * independently qualified platform isolation mechanism. The worker never accepts a raw boolean.</p>
 */
public interface WorkerSandboxBackend {

    String id();

    WorkerIsolation isolation();

    NetworkGuarantee networkGuarantee();

    IndexingArtifact execute(
            IndexerExecutor delegate,
            IndexingExecutionRequest request,
            WorkerNetworkPolicy networkPolicy
    ) throws Exception;

    default boolean enforcesNetworkDeny() {
        if (networkGuarantee() != NetworkGuarantee.OS_ENFORCED) {
            return false;
        }
        WorkerSandboxQualification evidence = qualification();
        return evidence.networkGuarantee() == NetworkGuarantee.OS_ENFORCED
                && evidence.networkDeny() == WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED
                && evidence.qualifiedForCurrentPlatform();
    }

    /** Returns true only when this backend is qualified to execute untrusted code on this OS. */
    default boolean supportsUntrustedCode() {
        WorkerSandboxQualification evidence = qualification();
        return evidence.trustDisposition()
                == WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED
                && evidence.qualifiedForCurrentPlatform();
    }

    default WorkerSandboxQualification qualification() {
        if (networkGuarantee() == NetworkGuarantee.NONE) {
            return WorkerSandboxQualification.nativeProcessOnly(id(), isolation());
        }
        throw new IllegalStateException(
                "OS-enforced worker backend must provide an independently qualified platform disposition");
    }

    static WorkerSandboxBackend nativeEphemeralWorkspace() {
        return NativeEphemeralWorkspaceBackend.INSTANCE;
    }

    enum NetworkGuarantee {
        NONE,
        OS_ENFORCED
    }

    final class NativeEphemeralWorkspaceBackend implements WorkerSandboxBackend {
        private static final NativeEphemeralWorkspaceBackend INSTANCE =
                new NativeEphemeralWorkspaceBackend();

        private NativeEphemeralWorkspaceBackend() {
        }

        @Override
        public String id() {
            return "native-process-ephemeral-workspace-v1";
        }

        @Override
        public WorkerIsolation isolation() {
            return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE;
        }

        @Override
        public NetworkGuarantee networkGuarantee() {
            return NetworkGuarantee.NONE;
        }

        @Override
        public IndexingArtifact execute(
                IndexerExecutor delegate,
                IndexingExecutionRequest request,
                WorkerNetworkPolicy networkPolicy
        ) throws Exception {
            Objects.requireNonNull(delegate, "delegate");
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(networkPolicy, "networkPolicy");
            if (networkPolicy == WorkerNetworkPolicy.DENY) {
                throw new IllegalStateException(
                        "native worker cannot prove OS-level network denial; choose a qualified sandbox backend");
            }
            return delegate.execute(request);
        }
    }
}
