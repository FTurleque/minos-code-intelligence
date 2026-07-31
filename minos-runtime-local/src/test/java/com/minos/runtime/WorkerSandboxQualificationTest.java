package com.minos.runtime;

import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSandboxQualificationTest {

    @Test
    void nativeBackendIsExplicitlyProcessOnlyAndProhibitsSandboxClaim() {
        WorkerSandboxQualification qualification = WorkerSandboxBackend
                .nativeEphemeralWorkspace()
                .qualification();

        assertEquals(
                WorkerSandboxQualification.NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                qualification.networkDeny());
        assertEquals(
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                qualification.trustDisposition());
        assertEquals(
                WorkerSandboxQualification.PlatformDisposition.BLOCKED_NO_RESTRICTED_TOKEN_JOB_OBJECT_BACKEND,
                qualification.platforms().get(WorkerSandboxQualification.Platform.WINDOWS));
        assertEquals(
                WorkerSandboxQualification.PlatformDisposition.BLOCKED_NO_NAMESPACE_SECCOMP_BACKEND,
                qualification.platforms().get(WorkerSandboxQualification.Platform.LINUX));
        assertFalse(qualification.sandboxClaimPermitted());
        assertTrue(qualification.limitations().contains("WORKER_SANDBOX_CLAIM_PROHIBITED"));
    }

    @Test
    void qualificationCannotClaimDenyOrUntrustedCodeWithoutOsEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerSandboxQualification(
                "invalid",
                com.minos.remote.DistributedIndexing.WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.NONE,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                java.util.Map.of(
                        WorkerSandboxQualification.Platform.LINUX,
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                java.util.List.of()));
    }

    @Test
    void nativeBackendStillRejectsDenyBeforeExecutingProvider() {
        WorkerSandboxBackend backend = WorkerSandboxBackend.nativeEphemeralWorkspace();
        java.util.concurrent.atomic.AtomicBoolean executed = new java.util.concurrent.atomic.AtomicBoolean();

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> backend.execute(
                request -> {
                    executed.set(true);
                    return null;
                },
                new com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest(
                        "provider", "1", java.nio.file.Path.of("."), java.util.List.of(), java.util.Map.of()),
                WorkerNetworkPolicy.DENY));

        assertFalse(executed.get());
        assertTrue(failure.getMessage().contains("cannot prove OS-level network denial"));
    }
}
