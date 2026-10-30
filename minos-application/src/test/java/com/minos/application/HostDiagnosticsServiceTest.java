package com.minos.application;

import com.minos.io.PrivateLocalStorage;
import com.minos.runtime.HostCommandLocator;
import com.minos.runtime.WorkerSandboxProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The host diagnostics of {@code minos doctor} as an application service (AUD-ARC-01, lot 2): the worker sandbox
 * selection, the host commands and the privacy of the locations of MINOS_HOME, in JDK and application types, with
 * a probe and a locator injected so that no composition root is needed.
 */
class HostDiagnosticsServiceTest {

    private static final Path JAVA = Path.of("bin", "java");

    @TempDir
    Path home;

    @Test
    void anAvailableSandboxSelectionIsReportedAsItIs() {
        WorkerSandboxDiagnosis diagnosis = service(probe(new WorkerSandboxProbe.UntrustedCodeSandbox(
                "linux-bubblewrap", true, "SELECTED", Optional.empty(), List.of(), ""))).workerSandbox(home);

        assertEquals("managed-backend", diagnosis.managedLocalBackend());
        assertTrue(diagnosis.managedLocalAvailable());
        assertEquals("linux-bubblewrap", diagnosis.untrustedCodeBackend());
        assertTrue(diagnosis.remoteIndexingAvailable());
        assertEquals("SELECTED", diagnosis.cause());
        assertEquals(Optional.empty(), diagnosis.rejectedBackend());
        assertEquals(List.of(), diagnosis.reasons());
        assertEquals("", diagnosis.reason());
        assertFalse(diagnosis.closedByDecision());
    }

    @Test
    void aSelectionRejectedByDecisionCarriesTheBackendTheDimensionsAndTheDecision() {
        WorkerSandboxDiagnosis diagnosis = service(probe(new WorkerSandboxProbe.UntrustedCodeSandbox(
                "none", false, WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION, Optional.of("windows-appcontainer"),
                List.of("WRITE_QUOTA_SUPERVISED"), "write quota is supervised, not enforced by the OS")))
                .workerSandbox(home);

        assertFalse(diagnosis.remoteIndexingAvailable());
        assertEquals(Optional.of("windows-appcontainer"), diagnosis.rejectedBackend());
        assertEquals(List.of("WRITE_QUOTA_SUPERVISED"), diagnosis.reasons());
        assertTrue(diagnosis.closedByDecision());
        assertEquals(WorkerSandboxProbe.CAUSE_REJECTED_BY_DECISION, WorkerSandboxDiagnosis.CAUSE_REJECTED_BY_DECISION);
    }

    @Test
    void aMissingPrerequisiteIsNotADecision() {
        WorkerSandboxDiagnosis diagnosis = service(probe(new WorkerSandboxProbe.UntrustedCodeSandbox(
                "none", false, "NO_OS_BACKEND_AVAILABLE", Optional.empty(), List.of("BUBBLEWRAP_MISSING"),
                "no OS sandbox backend is available"))).workerSandbox(home);

        assertFalse(diagnosis.closedByDecision());
        assertEquals("NO_OS_BACKEND_AVAILABLE", diagnosis.cause());
    }

    @Test
    void aHostCommandIsFoundOrReportedAbsent() {
        HostDiagnosticsService service = service(probe(null));

        assertEquals(Optional.of(JAVA), service.locateCommand("java"));
        assertEquals(Optional.empty(), service.locateCommand("docker"));
    }

    @Test
    void theTenLocationsAreReportedInOrderWithTheirPrivacy() throws Exception {
        PrivateLocalStorage.ensurePrivateDirectory(home.resolve("snapshots"));

        Map<String, String> privacy = service(probe(null)).privateStorage(home);

        assertEquals(List.of("MINOS_HOME", "snapshots", "projects", "runs", "semantic-vectors", "runtime-observations",
                "remote-cache", "remote-cache/repositories", "distributed-artifacts", "postgresql-snapshot-scratch"),
                List.copyOf(privacy.keySet()));
        assertEquals("ENFORCED", privacy.get("snapshots"));
        assertEquals("ABSENT", privacy.get("projects"));
    }

    @Test
    void aLocationWhosePrivacyCannotBeReadIsUnknown() {
        HostDiagnosticsService service = new HostDiagnosticsService(probe(null), new FakeLocator(), target -> {
            throw new IOException("unreadable");
        });

        Map<String, String> privacy = service.privateStorage(home);

        assertTrue(privacy.values().stream().allMatch("UNKNOWN"::equals), privacy.toString());
    }

    @Test
    void aDiagnosisRefusesMissingFieldsAndCopiesItsReasons() {
        List<String> reasons = new java.util.ArrayList<>(List.of("A"));
        WorkerSandboxDiagnosis diagnosis = new WorkerSandboxDiagnosis("m", true, "u", false, "C", Optional.empty(),
                reasons, null);
        reasons.add("B");

        assertEquals(List.of("A"), diagnosis.reasons());
        assertEquals("", diagnosis.reason());
        assertThrows(NullPointerException.class, () -> new WorkerSandboxDiagnosis(
                "m", true, "u", false, "C", null, List.of(), ""));
    }

    // ------------------------------------------------------------------------------ helpers

    private HostDiagnosticsService service(WorkerSandboxProbe probe) {
        return new HostDiagnosticsService(probe, new FakeLocator());
    }

    private static WorkerSandboxProbe probe(WorkerSandboxProbe.UntrustedCodeSandbox untrusted) {
        return new WorkerSandboxProbe() {
            @Override
            public ManagedLocalSandbox managedLocalProvider(Path home) {
                return new ManagedLocalSandbox("managed-backend", true);
            }

            @Override
            public UntrustedCodeSandbox untrustedCode(Path home) {
                return untrusted;
            }
        };
    }

    private static final class FakeLocator implements HostCommandLocator {
        @Override
        public Optional<Path> find(String command) {
            return "java".equals(command) ? Optional.of(JAVA) : Optional.empty();
        }

        @Override
        public List<String> invocation(Path executable, String... arguments) {
            throw new UnsupportedOperationException("doctor never invokes a command");
        }
    }
}
