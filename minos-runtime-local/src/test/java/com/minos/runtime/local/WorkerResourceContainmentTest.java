package com.minos.runtime.local;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerResourceContainmentTest {

    @Test
    void aBackendWithoutAnAggregateJobBoundaryIsNeverQualifiedForUntrustedCode() {
        WorkerResourceContainment containment = WorkerResourceContainment.none("process-only");

        assertFalse(containment.aggregateJobBoundaryEnforced());
        assertFalse(containment.qualifiedForUntrustedCode());
        assertTrue(containment.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("AGGREGATE_PROCESS_COUNT")));
        assertTrue(containment.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("AGGREGATE_MEMORY")));
        assertTrue(containment.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("DESCENDANT_TERMINATION")));
    }

    @Test
    void supervisionIsNeverAcceptedAsASubstituteForAnAggregateOsJobBoundary() {
        WorkerResourceContainment supervisedOnly = new WorkerResourceContainment(
                "supervised-only",
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of("SAMPLING_ONLY"));

        assertFalse(supervisedOnly.qualifiedForUntrustedCode());
        assertFalse(supervisedOnly.hardFilesystemQuotaEnforced());
        assertEquals(6, supervisedOnly.unmetRequirements().size(), supervisedOnly.unmetRequirements().toString());
        assertTrue(supervisedOnly.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_BYTES")));
        assertTrue(supervisedOnly.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_ENTRIES")));
    }

    @Test
    void aMeasurementAfterExecutionIsNeverContainment() {
        WorkerResourceContainment measured = new WorkerResourceContainment(
                "measured",
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.MEASURED_ONLY,
                WorkerResourceContainment.Disposition.MEASURED_ONLY,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of("POST_MORTEM_MEASUREMENT"));

        assertTrue(measured.aggregateJobBoundaryEnforced());
        assertFalse(measured.hardFilesystemQuotaEnforced());
        assertFalse(measured.qualifiedForUntrustedCode());
        assertTrue(measured.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_BYTES")));
        assertTrue(measured.unmetRequirements().stream()
                .anyMatch(value -> value.startsWith("FILESYSTEM_WRITE_ENTRIES")));
    }

    /**
     * A1 / ADR 0041. Formerly {@code currentOsBackendsFailClosedUntilStorageIsOsEnforced}: the
     * integrated backends keep a supervised write quota <em>by decision</em>, so untrusted remote
     * execution stays fail-closed on every OS. The lock now also pins the exact unmet dimensions,
     * the machine-readable decision marker, and the managed-local contract that must not regress.
     */
    @Test
    void currentOsBackendsStayFailClosedByDecisionAdr0041() {
        WorkerResourceContainment linux = LinuxBubblewrapWorkerSandboxBackend.containment();
        WorkerResourceContainment windows = WindowsAppContainerWorkerSandboxBackend.containment();

        assertTrue(linux.aggregateJobBoundaryEnforced());
        assertTrue(windows.aggregateJobBoundaryEnforced());
        assertFalse(linux.hardFilesystemQuotaEnforced());
        assertFalse(windows.hardFilesystemQuotaEnforced());
        assertFalse(linux.qualifiedForUntrustedCode());
        assertFalse(windows.qualifiedForUntrustedCode());
        assertTrue(linux.evidence().contains("CGROUP_V2_CGROUP_KILL"));
        assertTrue(windows.evidence().contains("JOB_OBJECT_LIMIT_KILL_ON_CLOSE"));

        List<String> onlyTheWriteQuota = List.of(
                "FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL",
                "FILESYSTEM_WRITE_ENTRIES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL");
        assertEquals(onlyTheWriteQuota, linux.unmetRequirements());
        assertEquals(onlyTheWriteQuota, windows.unmetRequirements());
        assertTrue(linux.qualifiedForManagedLocalProvider(), "managed local indexing must not regress");
        assertTrue(windows.qualifiedForManagedLocalProvider(), "managed local indexing must not regress");

        for (WorkerResourceContainment containment : List.of(linux, windows)) {
            WorkerSandboxQualification qualification = new WorkerSandboxQualification(
                    containment.boundaryId(),
                    com.minos.remote.DistributedIndexing.WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE,
                    WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED,
                    WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                    WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                    containment,
                    Map.of(WorkerSandboxQualification.currentPlatform(),
                            WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                    List.of());
            assertEquals(
                    WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                    qualification.trustDisposition());
            assertTrue(qualification.limitations().contains("WORKER_UNTRUSTED_CODE_CLOSED_BY_DECISION_ADR_0041"),
                    containment.boundaryId() + " must carry the ADR 0041 decision marker");
            assertTrue(qualification.managedLocalProviderClaimPermitted());
        }
    }
}
