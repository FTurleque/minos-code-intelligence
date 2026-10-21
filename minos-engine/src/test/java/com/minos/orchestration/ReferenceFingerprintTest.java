package com.minos.orchestration;

import com.minos.incremental.BuildDescriptorPolicy;
import com.minos.incremental.ProjectFingerprint;
import com.minos.incremental.ProjectFingerprintService;
import com.minos.source.SourceBudgetPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReferenceFingerprintTest {

    private static final ProjectFingerprint FINGERPRINT = new ProjectFingerprint("a".repeat(64), "b".repeat(64), List.of());

    @Test
    void aServiceCapturingLikeTheCheckpointsLetsTheRootScopeReuseTheReference() {
        ReferenceFingerprint reference = new ReferenceFingerprint(FINGERPRINT, new ProjectFingerprintService());

        assertEquals(Optional.of("a".repeat(64)), reference.reusableRootScopeFingerprint());
    }

    @Test
    void otherBuildDescriptorsForceANewCaptureOfTheRootScope() {
        ReferenceFingerprint reference = new ReferenceFingerprint(FINGERPRINT,
                new ProjectFingerprintService(new BuildDescriptorPolicy(Set.of("custom.build"))));

        assertEquals(Optional.empty(), reference.reusableRootScopeFingerprint());
    }

    @Test
    void anotherSourceBudgetForcesANewCaptureOfTheRootScope() {
        ReferenceFingerprint reference = new ReferenceFingerprint(FINGERPRINT,
                new ProjectFingerprintService(BuildDescriptorPolicy.m24Defaults(), new SourceBudgetPolicy(10, 1024)));

        assertEquals(Optional.empty(), reference.reusableRootScopeFingerprint());
    }

    @Test
    void capturesLikeComparesBothCapturePolicies() {
        ProjectFingerprintService defaults = new ProjectFingerprintService();

        assertTrue(defaults.capturesLike(new ProjectFingerprintService()));
        assertFalse(defaults.capturesLike(new ProjectFingerprintService(BuildDescriptorPolicy.m24Defaults(),
                new SourceBudgetPolicy(10, 1024))));
        assertThrows(NullPointerException.class, () -> defaults.capturesLike(null));
    }

    @Test
    void bothComponentsAreRequired() {
        ProjectFingerprintService service = new ProjectFingerprintService();

        assertThrows(NullPointerException.class, () -> new ReferenceFingerprint(null, service));
        assertThrows(NullPointerException.class, () -> new ReferenceFingerprint(FINGERPRINT, null));
    }
}
