package com.minos.runtime.local.sandbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the Windows AppContainer backend states and refuses before it touches the operating system:
 * the aggregate CPU budget it derives from the provider timeout, the containment it declares, and the
 * arguments it rejects. None of this needs Windows, so it is checked on every runner.
 */
class WindowsAppContainerBackendContractTest {

    @TempDir
    Path temporary;

    @Test
    void theJobCpuBudgetFollowsTheWallClockTimeout() {
        assertEquals(240L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(30)));
        assertEquals(
                30L * WindowsAppContainerWorkerSandboxBackend.JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND,
                WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(30)));
    }

    @Test
    void aSubSecondTimeoutStillGrantsOneWallClockSecondOfBudget() {
        assertEquals(8L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofMillis(1)));
        assertEquals(8L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(1)));
    }

    @Test
    void theJobCpuBudgetIsCappedAtOneDay() {
        assertEquals(86_400L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(10_800)));
        assertEquals(86_400L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(10_801)));
        assertEquals(86_392L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(10_799)));
    }

    @Test
    void aTimeoutWhoseBudgetWouldOverflowIsCappedInsteadOfWrappingToANegativeBudget() {
        long overflowing = Long.MAX_VALUE / WindowsAppContainerWorkerSandboxBackend.JOB_CPU_SECONDS_PER_WALL_CLOCK_SECOND + 1;

        assertEquals(86_400L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(overflowing)));
        assertEquals(86_400L, WindowsAppContainerWorkerSandboxBackend.jobCpuSeconds(Duration.ofSeconds(Long.MAX_VALUE)));
    }

    @Test
    void theDeclaredContainmentEnforcesTheJobBoundaryButOnlySupervisesTheFilesystemQuota() {
        WorkerResourceContainment containment = WindowsAppContainerWorkerSandboxBackend.containment();

        assertEquals("windows-appcontainer-job-object", containment.boundaryId());
        assertTrue(containment.aggregateJobBoundaryEnforced());
        assertTrue(containment.qualifiedForManagedLocalProvider());
        assertFalse(containment.hardFilesystemQuotaEnforced());
        assertFalse(containment.qualifiedForUntrustedCode(),
                "a sampled write quota must never qualify the execution of untrusted code");
        assertEquals(
                List.of("FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL",
                        "FILESYSTEM_WRITE_ENTRIES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL"),
                containment.unmetRequirements());
        assertTrue(containment.evidence().contains("JOB_OBJECT_LIMIT_KILL_ON_CLOSE"));
        assertTrue(containment.evidence().contains("MINOS_APPCONTAINER_PRIVATE_STORAGE_QUOTA_SUPERVISOR"));
    }

    @Test
    void aMissingPowerShellIsRefusedBeforeAnythingIsWrittenUnderMinosHome() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path missing = temporary.resolve("no-such-powershell.exe");

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new WindowsAppContainerWorkerSandboxBackend(home, missing));

        assertTrue(refusal.getMessage().startsWith("powershell must be a regular file"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), refusal.getMessage());
        assertNothingUnder(home);
    }

    @Test
    void aDirectoryGivenAsPowerShellIsRefused() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));
        Path directory = Files.createDirectory(temporary.resolve("powershell.exe"));

        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                () -> new WindowsAppContainerWorkerSandboxBackend(home, directory));

        assertTrue(refusal.getMessage().startsWith("powershell must be a regular file"), refusal.getMessage());
        assertFalse(refusal.getMessage().contains(temporary.toString()), refusal.getMessage());
        assertNothingUnder(home);
    }

    @Test
    void aNullPowerShellIsRefused() throws Exception {
        Path home = Files.createDirectory(temporary.resolve("home"));

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> new WindowsAppContainerWorkerSandboxBackend(home, null));

        assertEquals("powershell", refusal.getMessage());
        assertNothingUnder(home);
    }

    @Test
    void aNullMinosHomeIsRefusedByTheConstructorAndByDiscovery() {
        Path anyFile = temporary.resolve("unused.exe");

        NullPointerException constructed = assertThrows(NullPointerException.class,
                () -> new WindowsAppContainerWorkerSandboxBackend(null, anyFile));
        NullPointerException discovered = assertThrows(NullPointerException.class,
                () -> WindowsAppContainerWorkerSandboxBackend.discover(null));
        NullPointerException jobObject = assertThrows(NullPointerException.class,
                () -> WindowsJobObjectProcessOwnership.discover(null));

        assertEquals("minosHome", constructed.getMessage());
        assertEquals("minosHome", discovered.getMessage());
        assertEquals("minosHome", jobObject.getMessage());
    }

    private static void assertNothingUnder(Path home) throws Exception {
        try (Stream<Path> entries = Files.list(home)) {
            assertEquals(List.of(), entries.toList(), "a refused backend must leave MINOS_HOME untouched");
        }
    }
}
