package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A1 / ADR 0041: {@code minos doctor} says whether remote indexing is available and, if not, why. */
class DoctorCommandTest {

    private static final String BYTES_UNMET =
            "FILESYSTEM_WRITE_BYTES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";
    private static final String ENTRIES_UNMET =
            "FILESYSTEM_WRITE_ENTRIES_REQUIRES_OS_ENFORCED_JOB_BOUNDARY_BUT_IS_SUPERVISED_HARD_KILL";

    @Test
    void textReportSaysRemoteIndexingIsUnavailableAndWhyWithoutAnyPath(@TempDir Path home) throws Exception {
        DoctorCommand doctor = new DoctorCommand(home, operations(), ignored -> rejectedReport());
        StringBuilder output = new StringBuilder();

        int exit = doctor.run(new String[0], output, new StringBuilder());

        String text = output.toString();
        assertEquals(FindSymbolCommand.SUCCESS, exit, "a closed-by-decision remote path is not an ACTION_REQUIRED");
        assertTrue(text.contains("workerSandbox[managedLocalProvider]: linux-bubblewrap-cgroup2-v5 AVAILABLE"), text);
        assertTrue(text.contains("workerSandbox[untrustedCode]: native-process-ephemeral-workspace-v1 UNAVAILABLE"), text);
        assertTrue(text.contains("workerSandbox[remoteIndexing]: UNAVAILABLE (decision: ADR 0041)"), text);
        assertTrue(text.contains("workerSandbox[cause]: REJECTED_BY_DECISION"), text);
        assertTrue(text.contains("workerSandbox[reason]: "), text);
        assertTrue(text.contains("linux-bubblewrap-cgroup2-v5 was rejected"), text);
        assertTrue(text.contains(BYTES_UNMET), text);
        assertTrue(text.contains(ENTRIES_UNMET), text);
        assertTrue(text.contains("verdict: READY"), text);
        String sandboxLines = text.lines().filter(line -> line.startsWith("workerSandbox[")).reduce("", (a, b) -> a + b + "\n");
        assertFalse(sandboxLines.contains(home.toString()), "no MINOS_HOME in the sandbox section: " + sandboxLines);
        assertFalse(sandboxLines.contains("/") || sandboxLines.contains("\\"),
                "no filesystem path in the sandbox section: " + sandboxLines);
    }

    @Test
    void jsonReportIsDeterministicAndCarriesTheReasonCodes(@TempDir Path home) throws Exception {
        DoctorCommand doctor = new DoctorCommand(home, operations(), ignored -> rejectedReport());
        StringBuilder first = new StringBuilder();
        StringBuilder second = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[]{"--format", "json"}, first, new StringBuilder()));
        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[]{"--format", "json"}, second, new StringBuilder()));

        String json = first.toString();
        assertEquals(json, second.toString(), "doctor JSON must be deterministic");
        assertTrue(json.contains("\"workerSandbox\":{"), json);
        assertTrue(json.contains("\"managedLocalProvider\":{\"backend\":\"linux-bubblewrap-cgroup2-v5\",\"available\":true}"), json);
        assertTrue(json.contains("\"untrustedCode\":{\"backend\":\"native-process-ephemeral-workspace-v1\",\"available\":false}"), json);
        assertTrue(json.contains("\"remoteIndexing\":\"UNAVAILABLE\""), json);
        assertTrue(json.contains("\"cause\":\"REJECTED_BY_DECISION\""), json);
        assertTrue(json.contains("\"rejectedBackend\":\"linux-bubblewrap-cgroup2-v5\""), json);
        assertTrue(json.contains("\"reasons\":[\"" + BYTES_UNMET + "\",\"" + ENTRIES_UNMET + "\"]"), json);
        assertTrue(json.contains("\"decision\":\"ADR 0041\""), json);
        assertTrue(json.contains("\"ready\":true"), json);
    }

    @Test
    void anAvailableSandboxIsReportedWithoutRejection(@TempDir Path home) throws Exception {
        DoctorCommand.WorkerSandboxReport available = new DoctorCommand.WorkerSandboxReport(
                "fake-hard-backend", true, "fake-hard-backend", true, "QUALIFIED", Optional.empty(), List.of(), "");
        DoctorCommand doctor = new DoctorCommand(home, operations(), ignored -> available);
        StringBuilder output = new StringBuilder();

        doctor.run(new String[]{"--format", "json"}, output, new StringBuilder());

        String json = output.toString();
        assertTrue(json.contains("\"remoteIndexing\":\"AVAILABLE\""), json);
        assertTrue(json.contains("\"cause\":\"QUALIFIED\""), json);
        assertTrue(json.contains("\"rejectedBackend\":null"), json);
        assertTrue(json.contains("\"reasons\":[]"), json);
        assertTrue(json.contains("\"decision\":null"), json);
    }

    /** V24, case (1): a missing operator prerequisite is reported as such, never as the ADR 0041 decision. */
    @Test
    void aMissingPrerequisiteIsReportedAsAnOperatorActionNotAsADecision(@TempDir Path home) throws Exception {
        DoctorCommand.WorkerSandboxReport missing = new DoctorCommand.WorkerSandboxReport(
                "native-process-ephemeral-workspace-v1", false,
                "native-process-ephemeral-workspace-v1", false,
                "NO_OS_BACKEND_AVAILABLE", Optional.empty(),
                List.of("LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"),
                "no OS sandbox backend is available on this host (missing prerequisite: "
                        + "LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING); untrusted remote execution stays fail-closed: "
                        + "providing it makes the OS backend available to managed local providers only, "
                        + "not to untrusted code");
        DoctorCommand doctor = new DoctorCommand(home, operations(), ignored -> missing);
        StringBuilder text = new StringBuilder();
        StringBuilder json = new StringBuilder();

        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[0], text, new StringBuilder()));
        assertEquals(FindSymbolCommand.SUCCESS, doctor.run(new String[]{"--format", "json"}, json, new StringBuilder()));

        assertTrue(text.toString().contains("workerSandbox[managedLocalProvider]: native-process-ephemeral-workspace-v1 UNAVAILABLE"), text.toString());
        assertTrue(text.toString().contains("workerSandbox[remoteIndexing]: UNAVAILABLE\n"), text.toString());
        assertTrue(text.toString().contains("workerSandbox[cause]: NO_OS_BACKEND_AVAILABLE"), text.toString());
        assertTrue(text.toString().contains("missing prerequisite: LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING"), text.toString());
        assertFalse(text.toString().contains("ADR 0041"), "a missing prerequisite is not the decision: " + text);
        assertTrue(json.toString().contains("\"cause\":\"NO_OS_BACKEND_AVAILABLE\""), json.toString());
        assertTrue(json.toString().contains("\"reasons\":[\"LINUX_DELEGATED_CGROUP_V2_ROOT_MISSING\"]"), json.toString());
        assertTrue(json.toString().contains("\"decision\":null"), json.toString());
    }

    /** On every OS the integrated backends support today, the real probe must say UNAVAILABLE and cite ADR 0041. */
    @Test
    void theRealProbeReportsRemoteIndexingClosedByDecisionOnThisHost(@TempDir Path home) throws Exception {
        DoctorCommand doctor = new DoctorCommand(home, operations());
        StringBuilder output = new StringBuilder();

        int exit = doctor.run(new String[0], output, new StringBuilder());

        String text = output.toString();
        assertEquals(FindSymbolCommand.SUCCESS, exit, text);
        assertTrue(text.contains("workerSandbox[remoteIndexing]: UNAVAILABLE"), text);
        // Either an OS backend exists here and is rejected by decision, or a prerequisite is missing:
        // the report must say which, and only the former cites the ADR.
        boolean byDecision = text.contains("workerSandbox[cause]: REJECTED_BY_DECISION");
        boolean prerequisite = text.contains("workerSandbox[cause]: NO_OS_BACKEND_AVAILABLE");
        assertTrue(byDecision ^ prerequisite, text);
        assertEquals(byDecision, text.contains("ADR 0041"), text);
        // A missing prerequisite names its code; a platform without any integrated backend says so.
        assertEquals(prerequisite, text.contains("missing prerequisite: ")
                || text.contains("no OS sandbox backend exists for this platform"), text);
        String sandboxLines = text.lines().filter(line -> line.startsWith("workerSandbox[")).reduce("", (a, b) -> a + b + "\n");
        assertFalse(sandboxLines.contains(home.toString()), "no MINOS_HOME in the sandbox section: " + sandboxLines);
        assertFalse(sandboxLines.contains("/") || sandboxLines.contains("\\"),
                "no filesystem path in the sandbox section: " + sandboxLines);
    }

    private static DoctorCommand.WorkerSandboxReport rejectedReport() {
        return new DoctorCommand.WorkerSandboxReport(
                "linux-bubblewrap-cgroup2-v5", true,
                "native-process-ephemeral-workspace-v1", false,
                "REJECTED_BY_DECISION",
                Optional.of("linux-bubblewrap-cgroup2-v5"),
                List.of(BYTES_UNMET, ENTRIES_UNMET),
                "untrusted remote execution is fail-closed by decision (ADR 0041); OS sandbox backend "
                        + "linux-bubblewrap-cgroup2-v5 was rejected for untrusted code: "
                        + BYTES_UNMET + ", " + ENTRIES_UNMET);
    }

    private static AutonomousIndexOperations operations() {
        List<AutonomousIndexOperations.ProviderView> providers = List.of(
                new AutonomousIndexOperations.ProviderView("scip-java", "1.0", "READY", null, List.of(), true));
        return new AutonomousIndexOperations() {
            @Override
            public IndexPlanView plan(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("plan must not be called");
            }

            @Override
            public IndexExecutionView execute(String projectIdentifier, String providerOverride, boolean forceFull) {
                throw new AssertionError("execute must not be called");
            }

            @Override
            public List<ProviderView> providers() {
                return providers;
            }

            @Override
            public ProviderView installProvider(String providerId) {
                throw new AssertionError("installProvider must not be called");
            }
        };
    }

    @Test
    void aWorkerSandboxReportRejectsANullOptionalInsteadOfSilentlyReplacingIt() {
        assertThrows(NullPointerException.class, () -> new DoctorCommand.WorkerSandboxReport(
                "native", true, "native", false, "NOT_QUALIFIED", null, List.of(), ""));
    }
}
