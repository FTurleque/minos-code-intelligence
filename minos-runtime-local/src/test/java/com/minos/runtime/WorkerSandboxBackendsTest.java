package com.minos.runtime;

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
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(Optional.of(supervised))));

        WorkerSandboxSelection result = selection.get();
        assertEquals("native-process-ephemeral-workspace-v1", result.backend().id());
        assertFalse(result.supportsUntrustedCode());
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
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(Optional.of(hard))));

        WorkerSandboxSelection result = selection.get();
        assertEquals("fake-hard-backend", result.backend().id());
        assertTrue(result.supportsUntrustedCode());
        assertTrue(result.rejectedBackendId().isEmpty());
        assertTrue(result.rejectionReasons().isEmpty());
        assertTrue(records.stream().noneMatch(record -> record.getLevel() == Level.WARNING));
    }

    @Test
    void anAbsentOsBackendIsReportedWithoutClaimingARejection() {
        AtomicReference<WorkerSandboxSelection> selection = new AtomicReference<>();

        List<LogRecord> records = capture(() ->
                selection.set(WorkerSandboxBackends.selectForUntrustedCode(Optional.empty())));

        WorkerSandboxSelection result = selection.get();
        assertEquals("native-process-ephemeral-workspace-v1", result.backend().id());
        assertTrue(result.rejectedBackendId().isEmpty());
        assertTrue(result.rejectionReasons().stream()
                .anyMatch(reason -> reason.startsWith("NO_OS_SANDBOX_BACKEND_DISCOVERED")));
        assertTrue(result.refusalReport().contains("ADR 0041"));
        // discover() already logged why nothing was found; the selection must not log it twice.
        assertTrue(records.stream().noneMatch(record -> record.getLevel() == Level.WARNING));
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
}
