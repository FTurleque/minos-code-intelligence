package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
        assertFalse(qualification.managedLocalProviderClaimPermitted());
        assertTrue(qualification.limitations().contains("WORKER_SANDBOX_CLAIM_PROHIBITED"));
        assertTrue(qualification.limitations().contains("WORKER_AGGREGATE_RESOURCE_CONTAINMENT_UNAVAILABLE"));
        assertFalse(qualification.containment().qualifiedForUntrustedCode());
        assertFalse(qualification.containment().qualifiedForManagedLocalProvider());
    }

    @Test
    void qualificationCannotClaimDenyOrUntrustedCodeWithoutOsEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new WorkerSandboxQualification(
                "invalid",
                com.minos.remote.DistributedIndexing.WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.NONE,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                LinuxBubblewrapWorkerSandboxBackend.containment(),
                java.util.Map.of(
                        WorkerSandboxQualification.Platform.LINUX,
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                java.util.List.of()));
    }

    @Test
    void incompleteHardContainmentDowngradesUntrustedClaimFailClosed() {
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "per-process-limits-only",
                com.minos.remote.DistributedIndexing.WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                WorkerResourceContainment.none("per-process-limits-only"),
                java.util.Map.of(
                        WorkerSandboxQualification.currentPlatform(),
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                java.util.List.of());

        assertEquals(
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                qualification.trustDisposition());
        assertFalse(qualification.sandboxClaimPermitted());
        assertFalse(qualification.managedLocalProviderClaimPermitted());
        assertTrue(qualification.limitations().contains(
                "WORKER_UNTRUSTED_CODE_FAIL_CLOSED_INCOMPLETE_HARD_CONTAINMENT"));
        assertTrue(qualification.limitations().stream().anyMatch(value -> value.startsWith("AGGREGATE_MEMORY")));
    }

    @Test
    void supervisedFilesystemQuotaKeepsLocalProviderClaimButNotUntrustedClaim() {
        WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                "sampled-storage",
                com.minos.remote.DistributedIndexing.WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED,
                WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                LinuxBubblewrapWorkerSandboxBackend.containment(),
                Map.of(
                        WorkerSandboxQualification.currentPlatform(),
                        WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                java.util.List.of());

        assertEquals(
                WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                qualification.trustDisposition());
        assertFalse(qualification.sandboxClaimPermitted(),
                "supervised filesystem quotas must never become a hostile-code claim");
        assertTrue(qualification.managedLocalProviderClaimPermitted(),
                "the narrower managed local provider contract accepts actively supervised filesystem quotas");
        assertFalse(qualification.containment().qualifiedForUntrustedCode());
        assertTrue(qualification.containment().qualifiedForManagedLocalProvider());
        assertTrue(qualification.limitations().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_BYTES")));
        assertTrue(qualification.limitations().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_ENTRIES")));
    }
}
