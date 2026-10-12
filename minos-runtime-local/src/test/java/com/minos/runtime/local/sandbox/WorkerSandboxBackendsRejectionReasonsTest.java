package com.minos.runtime.local.sandbox;

import com.minos.orchestration.IndexingRuntimePorts.IndexerExecutor;
import com.minos.orchestration.IndexingRuntimePorts.IndexingArtifact;
import com.minos.orchestration.IndexingRuntimePorts.IndexingExecutionRequest;
import com.minos.remote.DistributedIndexing.WorkerIsolation;
import com.minos.remote.DistributedIndexing.WorkerNetworkPolicy;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.NetworkDenyDisposition;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.Platform;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.PlatformDisposition;
import com.minos.runtime.local.sandbox.WorkerSandboxQualification.TrustDisposition;
import com.minos.testsupport.LogCapture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The strict selector names why a discovered OS backend is refused for untrusted code. When the
 * containment itself has no unmet dimension, the reason is one of the dispositions (platform, network
 * denial, trust), most specific first: a refusal is never reported without a machine-readable code.
 * Every case runs on any host: the backend is a fake and the platform is the one of the host.
 */
class WorkerSandboxBackendsRejectionReasonsTest {

    private static final String NATIVE_FALLBACK = "native-process-ephemeral-workspace-v1";

    @Test
    void aBackendQualifiedOnAnotherPlatformOnlyIsRejectedWithThePlatformCode() {
        WorkerSandboxBackend elsewhere = backend(
                "fake-foreign-platform",
                NetworkDenyDisposition.QUALIFIED,
                TrustDisposition.UNTRUSTED_CODE_SUPPORTED,
                Map.of(anotherPlatform(), PlatformDisposition.QUALIFIED));

        Outcome outcome = select(elsewhere);

        assertRejected(outcome, "fake-foreign-platform",
                List.of("BACKEND_NOT_QUALIFIED_ON_" + WorkerSandboxQualification.currentPlatform().name()));
    }

    @Test
    void aBackendWithoutQualifiedNetworkDenialIsRejectedWithTheNetworkCode() {
        WorkerSandboxBackend open = backend(
                "fake-open-network",
                NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                Map.of(WorkerSandboxQualification.currentPlatform(), PlatformDisposition.QUALIFIED));

        Outcome outcome = select(open);

        assertRejected(outcome, "fake-open-network", List.of("NETWORK_DENY_NOT_QUALIFIED"));
    }

    @Test
    void platformAndNetworkReasonsAreBothReportedPlatformFirst() {
        WorkerSandboxBackend neither = backend(
                "fake-foreign-open",
                NetworkDenyDisposition.FAIL_CLOSED_NOT_ENFORCED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                Map.of(anotherPlatform(), PlatformDisposition.QUALIFIED));

        Outcome outcome = select(neither);

        assertRejected(outcome, "fake-foreign-open", List.of(
                "BACKEND_NOT_QUALIFIED_ON_" + WorkerSandboxQualification.currentPlatform().name(),
                "NETWORK_DENY_NOT_QUALIFIED"));
    }

    @Test
    void aBackendThatOnlyDeclaresItselfUnsupportedIsRejectedWithItsTrustDisposition() {
        WorkerSandboxBackend declared = backend(
                "fake-declared-unsupported",
                NetworkDenyDisposition.QUALIFIED,
                TrustDisposition.UNTRUSTED_CODE_UNSUPPORTED,
                Map.of(WorkerSandboxQualification.currentPlatform(), PlatformDisposition.QUALIFIED));

        Outcome outcome = select(declared);

        assertRejected(outcome, "fake-declared-unsupported",
                List.of("TRUST_DISPOSITION_IS_UNTRUSTED_CODE_UNSUPPORTED"));
    }

    @Test
    void aNullDiscoveryIsRefusedInsteadOfBeingReadAsAnAbsentBackend() {
        List<String> none = List.of();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> WorkerSandboxBackends.selectForUntrustedCode(null, none));

        assertEquals("discovered", refusal.getMessage());
    }

    @Test
    void nullPrerequisiteCodesAreRefusedInsteadOfBeingReadAsNoMissingPrerequisite() {
        Optional<WorkerSandboxBackend> absent = Optional.empty();

        NullPointerException refusal = assertThrows(NullPointerException.class,
                () -> WorkerSandboxBackends.selectForUntrustedCode(absent, null));

        assertEquals("missingPrerequisites", refusal.getMessage());
    }

    @Test
    void aNullMinosHomeIsRefusedByBothSelectors() {
        NullPointerException strict = assertThrows(NullPointerException.class,
                () -> WorkerSandboxBackends.selectForUntrustedCode(null));
        NullPointerException managed = assertThrows(NullPointerException.class,
                () -> WorkerSandboxBackends.strongestAvailableForManagedLocalProvider(null));

        assertEquals("minosHome", strict.getMessage());
        assertEquals("minosHome", managed.getMessage());
    }

    // ------------------------------------------------------------------------------ helpers

    private record Outcome(WorkerSandboxSelection selection, List<String> warnings) { }

    private static Outcome select(WorkerSandboxBackend candidate) {
        try (LogCapture logs = new LogCapture(WorkerSandboxBackends.class)) {
            WorkerSandboxSelection selection =
                    WorkerSandboxBackends.selectForUntrustedCode(Optional.of(candidate), List.of());
            return new Outcome(selection, logs.warnings());
        }
    }

    private static void assertRejected(Outcome outcome, String rejectedId, List<String> reasons) {
        WorkerSandboxSelection selection = outcome.selection();
        assertEquals(NATIVE_FALLBACK, selection.backend().id(), "the refused backend is never the one returned");
        assertFalse(selection.supportsUntrustedCode());
        assertEquals(WorkerSandboxSelection.Cause.REJECTED_BY_DECISION, selection.cause());
        assertEquals(Optional.of(rejectedId), selection.rejectedBackendId());
        assertEquals(reasons, selection.rejectionReasons());
        assertEquals(1, outcome.warnings().size(), "exactly one WARNING names the rejected backend");
        String warning = outcome.warnings().get(0);
        assertTrue(warning.contains(rejectedId), warning);
        for (String reason : reasons) {
            assertTrue(warning.contains(reason), warning);
            assertTrue(selection.refusalReport().contains(reason), selection.refusalReport());
        }
        assertPathFree(warning);
        assertPathFree(selection.refusalReport());
    }

    private static void assertPathFree(String text) {
        assertFalse(text.contains("/") || text.contains("\\"),
                "diagnostic must not carry a filesystem path: " + text);
    }

    private static Platform anotherPlatform() {
        return WorkerSandboxQualification.currentPlatform() == Platform.WINDOWS ? Platform.LINUX : Platform.WINDOWS;
    }

    /** Every containment dimension is enforced, so only the dispositions can explain a refusal. */
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

    private static WorkerSandboxBackend backend(
            String id,
            NetworkDenyDisposition networkDeny,
            TrustDisposition trust,
            Map<Platform, PlatformDisposition> platforms
    ) {
        return new WorkerSandboxBackend() {
            @Override public String id() { return id; }
            @Override public WorkerIsolation isolation() { return WorkerIsolation.PROCESS_EPHEMERAL_WORKSPACE; }
            @Override public NetworkGuarantee networkGuarantee() { return NetworkGuarantee.OS_ENFORCED; }

            @Override
            public WorkerSandboxQualification qualification() {
                return new WorkerSandboxQualification(
                        id, isolation(), networkGuarantee(), networkDeny, trust, hardContainment(), platforms, List.of());
            }

            @Override
            public IndexingArtifact execute(
                    IndexerExecutor delegate, IndexingExecutionRequest request, WorkerNetworkPolicy policy) {
                throw new UnsupportedOperationException("selection tests never execute a provider");
            }
        };
    }
}
