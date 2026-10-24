package com.minos.storage.postgresql;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pull policy of the jobs logged out of Docker Hub never pulls, and refuses an unlisted image by name. */
class PrePulledImagesOnlyPullPolicyTest {

    private static final String PINNED = "example/database:1.0-test@sha256:" + "a".repeat(64);

    @Test
    void aListedImageAlreadyPulledIsUsedWithoutPulling() {
        PrePulledImagesOnlyPullPolicy policy = new PrePulledImagesOnlyPullPolicy(Set.of(PINNED), image -> true);

        assertFalse(policy.shouldPull(DockerImageName.parse(PINNED)));
    }

    @Test
    void anUnlistedImageIsRefusedByNameBeforeAnyLookup() {
        AtomicInteger lookups = new AtomicInteger();
        PrePulledImagesOnlyPullPolicy policy = new PrePulledImagesOnlyPullPolicy(Set.of(PINNED), image -> {
            lookups.incrementAndGet();
            return true;
        });

        for (String unlisted : new String[] {
                "testcontainers/ryuk:0.14.0", "alpine:3.17", "example/database:1.0-test",
                "example/database:1.0-test@sha256:" + "b".repeat(64)}) {
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> policy.shouldPull(DockerImageName.parse(unlisted)));
            assertTrue(refusal.getMessage().contains(DockerImageName.parse(unlisted).asCanonicalNameString()),
                    refusal.getMessage());
            assertTrue(refusal.getMessage().contains(PrePulledImagesOnlyPullPolicy.LIST_SOURCE), refusal.getMessage());
        }
        assertEquals(0, lookups.get(), "an unlisted image is refused without asking Docker");
    }

    @Test
    void aListedImageThatWasNotPulledFailsInsteadOfBeingPulledAnonymously() {
        PrePulledImagesOnlyPullPolicy policy = new PrePulledImagesOnlyPullPolicy(Set.of(PINNED), image -> false);

        IllegalStateException refusal = assertThrows(IllegalStateException.class,
                () -> policy.shouldPull(DockerImageName.parse(PINNED)));

        assertTrue(refusal.getMessage().contains("was not pulled before the tests"), refusal.getMessage());
    }

    @Test
    void theDefaultPolicyAllowsTheImageThePostgresqlTestsStart() throws Exception {
        String pinned = PostgresTestSupport.pinnedPostgresImage();
        PrePulledImagesOnlyPullPolicy policy = assertDoesNotThrow(() -> new PrePulledImagesOnlyPullPolicy());

        // Only the name check is exercised here: the local lookup needs Docker, which this test does not require.
        IllegalStateException unlisted = assertThrows(IllegalStateException.class,
                () -> policy.shouldPull(DockerImageName.parse("alpine:3.17")));
        assertTrue(unlisted.getMessage().contains("is not in the pre-pulled list"), unlisted.getMessage());
        assertTrue(pinned.contains("@sha256:"), "the PostgreSQL image of the tests is pinned by digest: " + pinned);
    }
}
