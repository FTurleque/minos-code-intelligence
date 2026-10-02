package com.minos.runtime.local;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerSandboxBackendsTest {

    @Test
    void strictSelectorNeverClaimsOsEnforcementWithoutHostileCodeQualification() throws Exception {
        Path home = Files.createTempDirectory("minos-worker-sandbox-selector-strict-");
        WorkerSandboxBackend selected = WorkerSandboxBackends.strongestAvailable(home);
        if (selected.networkGuarantee() == WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            assertTrue(selected.supportsUntrustedCode());
            assertTrue(selected.qualification().sandboxClaimPermitted());
            assertTrue(selected.enforcesNetworkDeny());
            assertTrue(selected.qualification().qualifiedForCurrentPlatform());
        } else {
            assertEquals(WorkerSandboxBackend.NetworkGuarantee.NONE, selected.networkGuarantee());
            assertFalse(selected.supportsUntrustedCode());
            assertFalse(selected.qualification().sandboxClaimPermitted());
        }
    }

    @Test
    void managedLocalSelectorDoesNotPromoteItsNarrowerContractToUntrustedCode() throws Exception {
        Path home = Files.createTempDirectory("minos-worker-sandbox-selector-local-");
        WorkerSandboxBackend selected = WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(home);
        if (selected.networkGuarantee() == WorkerSandboxBackend.NetworkGuarantee.OS_ENFORCED) {
            assertTrue(selected.supportsManagedLocalProvider());
            assertTrue(selected.qualification().managedLocalProviderClaimPermitted());
            assertTrue(selected.enforcesNetworkDeny());
            // Current Linux/Windows backends use a supervised filesystem quota, so the strict
            // untrusted claim normally remains false. Do not require that forever: a future real
            // kernel quota may legitimately make both claims true.
            if (!selected.resourceContainment().hardFilesystemQuotaEnforced()) {
                assertFalse(selected.supportsUntrustedCode());
                assertFalse(selected.qualification().sandboxClaimPermitted());
            }
        } else {
            assertEquals(WorkerSandboxBackend.NetworkGuarantee.NONE, selected.networkGuarantee());
            assertFalse(selected.supportsManagedLocalProvider());
        }
    }

    private static final String BYTES_UNMET =
            "FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";
    private static final String ENTRIES_UNMET =
            "FILESYSTEM_WRITE_ENTRIES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";

    /** A1 (ADR 0041): the rejection of a discovered OS backend must be reported and logged, never silent. */
    @Test
    void anOsBackendRejectedForUntrustedCodeIsReportedAndLoggedWithItsMissingDimensions() {
        WorkerSandboxBackend supervised = fakeBackend(
                "fake-os-backend", LinuxBubblewrapWorkerSandboxBackend.containment());
        AtomicReference<WorkerSandboxSelection> selection = new AtomicReference<>();

        List<LogRecord> records = capture(() ->
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(Optional.of(supervised), List.of())));

        WorkerSandboxSelection result = selection.get();
        assertEquals("native-process-ephemeral-workspace-v1", result.backend().id());
        assertFalse(result.supportsUntrustedCode());
        assertEquals(WorkerSandboxSelection.Cause.REJECTED_BY_DECISION, result.cause());
        assertTrue(result.closedByDecision());
        assertEquals(Optional.of("fake-os-backend"), result.rejectedBackendId());
        assertEquals(List.of(BYTES_UNMET, ENTRIES_UNMET), result.rejectionReasons());
        assertTrue(result.refusalReport().contains("fake-os-backend"));
        assertTrue(result.refusalReport().contains(BYTES_UNMET));
        assertTrue(result.refusalReport().contains("ADR 0041"));

        List<LogRecord> warnings = records.stream()
                .filter(record -> record.getLevel() == Level.WARNING)
                .toList();
        assertEquals(1, warnings.size(), "exactly one WARNING names the rejected backend");
        String message = warnings.get(0).getMessage();
        assertTrue(message.contains("fake-os-backend"));
        assertTrue(message.contains(BYTES_UNMET));
        assertTrue(message.contains(ENTRIES_UNMET));
        assertTrue(message.contains("ADR 0041"));
        assertPathFree(message);
        assertPathFree(result.refusalReport());
    }

    @Test
    void aQualifiedOsBackendIsRetainedWithoutAnyWarning() {
        WorkerSandboxBackend hard = fakeBackend("fake-hard-backend", hardContainment());
        AtomicReference<WorkerSandboxSelection> selection = new AtomicReference<>();

        List<LogRecord> records = capture(() ->
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(Optional.of(hard), List.of())));

        WorkerSandboxSelection result = selection.get();
        assertEquals("fake-hard-backend", result.backend().id());
        assertTrue(result.supportsUntrustedCode());
        assertEquals(WorkerSandboxSelection.Cause.QUALIFIED, result.cause());
        assertEquals("", result.refusalReport());
        assertTrue(result.rejectedBackendId().isEmpty());
        assertTrue(result.rejectionReasons().isEmpty());
        assertTrue(records.stream().noneMatch(record -> record.getLevel() == Level.WARNING));
    }

    /** V24: a missing operator prerequisite is never presented as the ADR 0041 decision. */
    @Test
    void anAbsentOsBackendIsReportedAsAMissingPrerequisiteNotAsADecision() {
        AtomicReference<WorkerSandboxSelection> selection = new AtomicReference<>();

        List<LogRecord> records = capture(() ->
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(
                        Optional.empty(), List.of("LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"))));

        WorkerSandboxSelection result = selection.get();
        assertEquals("native-process-ephemeral-workspace-v1", result.backend().id());
        assertEquals(WorkerSandboxSelection.Cause.NO_OS_BACKEND_AVAILABLE, result.cause());
        assertFalse(result.closedByDecision());
        assertTrue(result.rejectedBackendId().isEmpty());
        assertEquals(List.of("LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"), result.rejectionReasons());
        assertTrue(result.refusalReport().contains("missing prerequisite: LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"),
                result.refusalReport());
        assertFalse(result.refusalReport().contains("ADR 0041"),
                "a missing prerequisite is an operator action, not the decision: " + result.refusalReport());
        // V33: providing the prerequisite does not reopen untrusted execution, so the report must not
        // promise it ("until it is installed" was wrong).
        assertTrue(result.refusalReport().contains("managed local providers only"), result.refusalReport());
        assertFalse(result.refusalReport().contains("until it is installed"), result.refusalReport());
        assertPathFree(result.refusalReport());
        // V33: discover() does not log every missing prerequisite (bwrap, prlimit, PowerShell), so the
        // selection itself logs the codes as a WARNING, never silently.
        String warning = singleWarning(records);
        assertTrue(warning.contains("LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"), warning);
        assertTrue(warning.contains("missing prerequisite"), warning);
        assertFalse(warning.contains("ADR 0041"), warning);
        assertPathFree(warning);
    }

    /** V33: an OS without any integrated backend has nothing to install, and says so. */
    @Test
    void anUnsupportedPlatformIsReportedAsSuchNotAsAnInstallablePrerequisite() {
        AtomicReference<WorkerSandboxSelection> selection = new AtomicReference<>();

        List<LogRecord> records = capture(() ->
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(
                        Optional.empty(), List.of("PLATFORM_OTHER_HAS_NO_OS_SANDBOX_BACKEND"))));

        WorkerSandboxSelection result = selection.get();
        assertEquals(WorkerSandboxSelection.Cause.NO_OS_BACKEND_AVAILABLE, result.cause());
        assertFalse(result.closedByDecision());
        String report = result.refusalReport();
        assertTrue(report.contains("no OS sandbox backend exists for this platform"), report);
        assertTrue(report.contains("PLATFORM_OTHER_HAS_NO_OS_SANDBOX_BACKEND"), report);
        assertTrue(report.contains("nothing to install"), report);
        assertFalse(report.contains("missing prerequisite"), report);
        assertFalse(report.contains("installed"), report);
        assertFalse(report.contains("ADR 0041"), report);
        assertPathFree(report);
        String warning = singleWarning(records);
        assertTrue(warning.contains("PLATFORM_OTHER_HAS_NO_OS_SANDBOX_BACKEND"), warning);
        assertPathFree(warning);
    }

    private static String singleWarning(List<LogRecord> records) {
        List<LogRecord> warnings = records.stream()
                .filter(record -> record.getLevel() == Level.WARNING)
                .toList();
        assertEquals(1, warnings.size(), "exactly one WARNING reports the absent OS backend");
        return warnings.get(0).getMessage();
    }

    @Test
    void missingPrerequisitesAreNamedInDiscoveryOrderAndProbeFailureOnlyWhenAllArePresent() {
        assertEquals(
                List.of("LINUX_BUBBLEWRAP_NOT_FOUND", "LINUX_PRLIMIT_NOT_FOUND", "LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"),
                WorkerSandboxBackends.missingLinuxPrerequisites(false, false, false));
        assertEquals(
                List.of("LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"),
                WorkerSandboxBackends.missingLinuxPrerequisites(true, true, false));
        assertEquals(
                List.of("LINUX_SANDBOX_CAPABILITY_PROBE_FAILED"),
                WorkerSandboxBackends.missingLinuxPrerequisites(true, true, true));
        assertEquals(
                List.of("WINDOWS_POWERSHELL_NOT_FOUND"),
                WorkerSandboxBackends.missingWindowsPrerequisites(false));
        assertEquals(
                List.of("WINDOWS_APPCONTAINER_CAPABILITY_PROBE_OR_LAUNCHER_FAILED"),
                WorkerSandboxBackends.missingWindowsPrerequisites(true));
    }

    @Test
    void anExecutorWithoutSandboxCapabilityIsItsOwnCause() {
        WorkerSandboxSelection result = WorkerSandboxSelection.executorNotSandboxCapable();

        assertEquals("native-process-ephemeral-workspace-v1", result.backend().id());
        assertEquals(WorkerSandboxSelection.Cause.EXECUTOR_NOT_SANDBOX_CAPABLE, result.cause());
        assertFalse(result.supportsUntrustedCode());
        assertFalse(result.closedByDecision());
        assertEquals(List.of(WorkerSandboxSelection.EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE), result.rejectionReasons());
        assertTrue(result.refusalReport().contains(WorkerSandboxSelection.EXECUTOR_NOT_PROCESS_SANDBOX_CAPABLE));
        assertFalse(result.refusalReport().contains("ADR 0041"), result.refusalReport());
        assertPathFree(result.refusalReport());
    }

    private static void assertPathFree(String text) {
        assertFalse(text.contains("/") || text.contains("\\"),
                "diagnostic must not carry a filesystem path: " + text);
    }

    private static WorkerResourceContainment hardContainment() {
        return new WorkerResourceContainment(
                "fake-hard",
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.OS_ENFORCED,
                WorkerResourceContainment.Disposition.SUPERVISED_HARD_KILL,
                List.of("FAKE_KERNEL_QUOTA"));
    }

    private static WorkerSandboxBackend fakeBackend(String id, WorkerResourceContainment containment) {
        return new WorkerSandboxBackend() {
            @Override public String id() { return id; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public NetworkGuarantee networkGuarantee() { return NetworkGuarantee.OS_ENFORCED; }

            @Override
            public WorkerSandboxQualification qualification() {
                return new WorkerSandboxQualification(
                        id,
                        isolation(),
                        networkGuarantee(),
                        WorkerSandboxQualification.NetworkDenyDisposition.QUALIFIED,
                        WorkerSandboxQualification.TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                        containment,
                        Map.of(WorkerSandboxQualification.currentPlatform(),
                                WorkerSandboxQualification.PlatformDisposition.QUALIFIED),
                        List.of());
            }

            @Override
            public IndexingArtifact execute(
                    IndexerExecutor delegate, IndexingExecutionRequest request, WorkerNetworkPolicy policy) {
                throw new UnsupportedOperationException("selection tests never execute a provider");
            }
        };
    }

    private static List<LogRecord> capture(Runnable action) {
        Logger logger = Logger.getLogger(WorkerSandboxBackends.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) { records.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        };
        logger.addHandler(handler);
        try {
            action.run();
        } finally {
            logger.removeHandler(handler);
        }
        return records;
    }

    @Test
    void aSelectionRejectsANullOptionalInsteadOfSilentlyReplacingIt() {
        assertThrows(NullPointerException.class, () -> new WorkerSandboxSelection(
                WorkerSandboxBackend.nativeEphemeralWorkspace(),
                WorkerSandboxSelection.Cause.NOT_QUALIFIED,
                null,
                List.of()));
    }
}
