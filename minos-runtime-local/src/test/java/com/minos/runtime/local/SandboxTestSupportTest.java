package com.minos.runtime.local;

import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class SandboxTestSupportTest {

    @Test
    void requiredAndUnavailableFailsWithTheReason() {
        AssertionFailedError failure = assertThrows(AssertionFailedError.class,
                () -> SandboxTestSupport.decide(true, false, () -> "bubblewrap is missing"));
        assertTrue(failure.getMessage().contains("bubblewrap is missing"), failure.getMessage());
        assertTrue(failure.getMessage().contains(SandboxTestSupport.REQUIRED_PROPERTY), failure.getMessage());
    }

    @Test
    void optionalAndUnavailableAbortsTheTestWithTheReason() {
        TestAbortedException aborted = assertThrows(TestAbortedException.class,
                () -> SandboxTestSupport.decide(false, false, () -> "bubblewrap is missing"));
        assertTrue(aborted.getMessage().contains("bubblewrap is missing"), aborted.getMessage());
    }

    @Test
    void anAvailableConfinementNeverFailsNorSkipsWhateverTheProperty() {
        assertDoesNotThrow(() -> SandboxTestSupport.decide(true, true, () -> "unused"));
        assertDoesNotThrow(() -> SandboxTestSupport.decide(false, true, () -> "unused"));
    }

    @Test
    void theReasonIsNotComputedWhenTheConfinementIsAvailable() {
        SandboxTestSupport.decide(true, true, () -> {
            throw new IllegalStateException("reason computed for an available confinement");
        });
    }

    @Test
    void requireBackendReturnsTheDiscoveredBackend() {
        assertEquals("backend", SandboxTestSupport.requireBackend(Optional.of("backend"), "a backend"));
    }

    @Test
    void anAbsentBackendFailsOrSkipsAccordingToTheProperty() {
        Class<? extends Throwable> expected = SandboxTestSupport.required()
                ? AssertionFailedError.class : TestAbortedException.class;
        assertThrows(expected, () -> SandboxTestSupport.requireBackend(Optional.empty(), "a backend"));
    }

    @Test
    void aWeakCapabilityReportsTheExecutorDiagnostics() {
        Throwable thrown = assertThrows(Throwable.class,
                () -> SandboxTestSupport.requireStrongCapability(false, List.of("no cgroup", "no job")));
        assertTrue(thrown.getMessage().contains("no cgroup; no job"), thrown.getMessage());
    }

    /**
     * A property lost between the Maven command line and the forked test JVM would silently restore
     * the skip-on-absence behaviour. On the pull-request verification job the property must be
     * visible here; elsewhere (workstation, manual replay workflows) this test does not apply.
     */
    @Test
    void requiredModeIsActiveOnThePullRequestVerificationJob() {
        assumeTrue("PR Validation".equals(System.getenv("GITHUB_WORKFLOW"))
                        && "verify".equals(System.getenv("GITHUB_JOB")),
                "only meaningful inside the verify job of the PR Validation workflow");
        assertTrue(SandboxTestSupport.required(),
                SandboxTestSupport.REQUIRED_PROPERTY + " must be true on the pull-request verification job");
    }
}
