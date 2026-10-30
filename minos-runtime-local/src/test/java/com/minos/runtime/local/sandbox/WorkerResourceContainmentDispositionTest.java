package com.minos.runtime.local.sandbox;

import com.minos.runtime.local.sandbox.WorkerResourceContainment.Disposition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Dimension par dimension : ce qui qualifie, ou non, un {@link WorkerResourceContainment}. */
class WorkerResourceContainmentDispositionTest {

    private static final Disposition OS = Disposition.OS_ENFORCED;

    /** Ordre : processus, mémoire, CPU, horloge, octets, entrées, descendants, récupération. */
    private static WorkerResourceContainment containment(Disposition... dimensions) {
        return new WorkerResourceContainment(
                "fixture-boundary",
                dimensions[0], dimensions[1], dimensions[2], dimensions[3],
                dimensions[4], dimensions[5], dimensions[6], dimensions[7],
                List.of("FIXTURE"));
    }

    @Test
    void aBoundaryWithoutAnIdentifierIsRefused() {
        List<String> evidence = List.of();
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> new WorkerResourceContainment(" ", OS, OS, OS, OS, OS, OS, OS, OS, evidence));
        assertThrows(IllegalArgumentException.class,
                () -> new WorkerResourceContainment(null, OS, OS, OS, OS, OS, OS, OS, OS, evidence));
        NullPointerException undeclared = assertThrows(NullPointerException.class,
                () -> new WorkerResourceContainment("boundary", OS, OS, OS, OS, OS, null, OS, OS, evidence));

        assertEquals("containment boundaryId must not be blank", blank.getMessage());
        assertEquals("filesystemWriteEntries", undeclared.getMessage(),
                "a dimension that is not declared is refused, never assumed enforced");
    }

    @Test
    void aBoundaryEnforcedByTheOsOnEveryDimensionQualifiesForUntrustedCode() {
        WorkerResourceContainment hard = new WorkerResourceContainment(
                "hard-boundary", OS, OS, OS, OS, OS, OS, OS, OS, null);

        assertEquals(List.of(), hard.evidence(), "absent evidence is an empty list");
        assertTrue(hard.aggregateJobBoundaryEnforced());
        assertTrue(hard.hardFilesystemQuotaEnforced());
        assertTrue(hard.qualifiedForUntrustedCode());
        assertTrue(hard.qualifiedForManagedLocalProvider());
        assertEquals(List.of(), hard.unmetRequirements());
        assertEquals(List.of(), hard.managedLocalProviderUnmetRequirements());
    }

    @Test
    void eachAggregateDimensionMustBeOsEnforcedOnItsOwn() {
        Disposition supervised = Disposition.SUPERVISED_HARD_KILL;
        WorkerResourceContainment processes = containment(supervised, OS, OS, OS, OS, OS, OS, OS);
        WorkerResourceContainment memory = containment(OS, supervised, OS, OS, OS, OS, OS, OS);
        WorkerResourceContainment cpu = containment(OS, OS, supervised, OS, OS, OS, OS, OS);
        WorkerResourceContainment descendants = containment(OS, OS, OS, OS, OS, OS, supervised, OS);

        assertFalse(processes.aggregateJobBoundaryEnforced());
        assertFalse(memory.aggregateJobBoundaryEnforced());
        assertFalse(cpu.aggregateJobBoundaryEnforced());
        assertFalse(descendants.aggregateJobBoundaryEnforced());
        assertEquals(List.of("AGGREGATE_PROCESS_COUNT_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL"),
                processes.unmetRequirements());
        assertEquals(List.of("AGGREGATE_MEMORY_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL"),
                memory.managedLocalProviderUnmetRequirements());
        assertEquals(List.of("AGGREGATE_CPU_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL"),
                cpu.unmetRequirements());
        assertEquals(List.of("DESCENDANT_TERMINATION_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL"),
                descendants.managedLocalProviderUnmetRequirements());
        assertFalse(descendants.qualifiedForManagedLocalProvider(),
                "supervision never replaces the OS job boundary, even for the managed-local contract");
    }

    @Test
    void aSupervisedFilesystemQuotaQualifiesTheManagedLocalContractOnly() {
        Disposition supervised = Disposition.SUPERVISED_HARD_KILL;
        WorkerResourceContainment bytes = containment(OS, OS, OS, OS, supervised, OS, OS, OS);
        WorkerResourceContainment entries = containment(OS, OS, OS, OS, OS, supervised, OS, OS);

        assertFalse(bytes.hardFilesystemQuotaEnforced());
        assertFalse(entries.hardFilesystemQuotaEnforced());
        assertTrue(bytes.aggregateJobBoundaryEnforced());
        assertFalse(bytes.qualifiedForUntrustedCode());
        assertTrue(bytes.qualifiedForManagedLocalProvider());
        assertEquals(List.of("FILESYSTEM_WRITE_ENTRIES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL"),
                entries.unmetRequirements());
        assertEquals(List.of(), entries.managedLocalProviderUnmetRequirements());
    }

    @Test
    void aDimensionOnlyMeasuredAfterTheFactQualifiesNoContract() {
        WorkerResourceContainment wallClock = containment(OS, OS, OS, Disposition.MEASURED_ONLY, OS, OS, OS, OS);
        WorkerResourceContainment scratch = containment(OS, OS, OS, OS, OS, OS, OS, Disposition.UNAVAILABLE);
        WorkerResourceContainment bytes = containment(OS, OS, OS, OS, Disposition.MEASURED_ONLY, OS, OS, OS);

        assertEquals(List.of("WALL_CLOCK_NOT_ENFORCED_DURING_EXECUTION_BUT_IS_MEASURED_ONLY"),
                wallClock.unmetRequirements());
        assertEquals(wallClock.unmetRequirements(), wallClock.managedLocalProviderUnmetRequirements());
        assertEquals(List.of("SCRATCH_RECLAMATION_NOT_ENFORCED_DURING_EXECUTION_BUT_IS_UNAVAILABLE"),
                scratch.managedLocalProviderUnmetRequirements());
        assertEquals(List.of("FILESYSTEM_WRITE_BYTES_NOT_ENFORCED_DURING_EXECUTION_BUT_IS_MEASURED_ONLY"),
                bytes.managedLocalProviderUnmetRequirements());
        assertFalse(wallClock.qualifiedForManagedLocalProvider());
        assertFalse(scratch.qualifiedForUntrustedCode());
    }

    @Test
    void onlyOsEnforcementAndSupervisedHardKillAreEnforcedDuringExecution() {
        assertTrue(Disposition.OS_ENFORCED.enforcedDuringExecution());
        assertTrue(Disposition.SUPERVISED_HARD_KILL.enforcedDuringExecution());
        assertFalse(Disposition.MEASURED_ONLY.enforcedDuringExecution());
        assertFalse(Disposition.UNAVAILABLE.enforcedDuringExecution());
    }
}
