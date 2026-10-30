package com.minos.orchestration;

import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReferenceFingerprintTest {

    private static final ProjectFingerprint FINGERPRINT = new ProjectFingerprint("a".repeat(64), "b".repeat(64), List.of());

    @Test
    void theServiceOfTheRunLetsTheRootScopeReuseTheReferenceItCaptured() {
        ProjectFingerprintService runService = new ProjectFingerprintService();
        ReferenceFingerprint reference = new ReferenceFingerprint(FINGERPRINT, runService);

        assertEquals(Optional.of("a".repeat(64)), reference.reusableRootScopeFingerprint(runService));
    }

    @Test
    void anyOtherInstanceForcesANewCaptureOfTheRootScope() {
        ReferenceFingerprint reference = new ReferenceFingerprint(FINGERPRINT, new ProjectFingerprintService());

        // Same rules, other instance: not the service the run checkpoints with, so nothing is taken on trust.
        assertEquals(Optional.empty(), reference.reusableRootScopeFingerprint(new ProjectFingerprintService()));
        assertThrows(NullPointerException.class, () -> reference.reusableRootScopeFingerprint(null));
    }

    @Test
    void bothComponentsAreRequired() {
        ProjectFingerprintService service = new ProjectFingerprintService();

        assertThrows(NullPointerException.class, () -> new ReferenceFingerprint(null, service));
        assertThrows(NullPointerException.class, () -> new ReferenceFingerprint(FINGERPRINT, null));
    }
}
