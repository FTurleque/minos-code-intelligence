package com.minos.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;

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

    @Test
    void currentOsBackendsFailClosedUntilStorageIsOsEnforced() {
        WorkerResourceContainment linux = LinuxBubblewrapWorkerSandboxBackend.containment();
        WorkerResourceContainment windows = WindowsAppContainerWorkerSandboxBackend.containment();

        assertTrue(linux.aggregateJobBoundaryEnforced());
        assertTrue(windows.aggregateJobBoundaryEnforced());
        assertFalse(linux.hardFilesystemQuotaEnforced());
        assertFalse(windows.hardFilesystemQuotaEnforced());
        assertFalse(linux.qualifiedForUntrustedCode());
        assertFalse(windows.qualifiedForUntrustedCode());
        assertTrue(linux.evidence().contains("CGROUP_V2_CGROUP_KILL"));
        assertTrue(windows.evidence().contains("JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE"));
    }
}
