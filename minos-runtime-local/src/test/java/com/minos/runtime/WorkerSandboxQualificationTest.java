package com.minos.runtime;

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
}
