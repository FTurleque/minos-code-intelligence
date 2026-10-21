package com.minos.application.indexing;

import com.minos.discovery.ProjectDiscovery;
import com.minos.discovery.ProjectIgnorePolicy;
import com.minos.discovery.SourceRootDetector;
import com.minos.incremental.ProjectFingerprintSnapshot;
import com.minos.incremental.ProjectInvalidationReason;
import com.minos.orchestration.InMemoryIndexStateStore;
import com.minos.orchestration.IndexingMode;
import com.minos.orchestration.IndexingResumePolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviours the removed {@code IncrementalIndexingCoordinator} proved, carried over to the single orchestration
 * (AUD-ARC-02, D4): the reference fingerprint is captured before the discovery (MINOS-AUD-D06), the project lease is
 * held through the fingerprint promotion, and an unreadable baseline is declared without a path (D3 a).
 */
class IndexingBaselineAndLeaseTest {

    @TempDir Path temp;

    @Test
    void aStructureCreatedDuringDiscoveryPreventsThePromotionOfTheBaseline() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        fixture.extraDetectors = List.of(new ModuleCreatedAtDiscovery(fixture.root));

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertTrue(Files.isRegularFile(fixture.root.resolve("late/pom.xml")), "the module did appear during discovery");
        assertEquals(1, fixture.executor.calls.get(), "the run itself succeeded");
        assertFalse(outcome.fingerprintPromoted(),
                "the baseline of a snapshot that never covered the new module must not be promoted");
        assertTrue(outcome.diagnostic().orElseThrow().contains("workspace changed during indexing"));
        assertTrue(fixture.fingerprints.loadActive(fixture.project.id()).isEmpty());
    }

    @Test
    void aStableTreePromotesTheBaseline() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);

        var outcome = fixture.useCase().execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertTrue(outcome.fingerprintPromoted());
        assertEquals("snapshot-1", fixture.fingerprints.loadActive(fixture.project.id()).orElseThrow().indexSnapshotId());
    }

    @Test
    void anUnreadableBaselineRequiresAFullIndexingAndIsDeclaredWithoutItsPath() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        String absolute = temp.toAbsolutePath().resolve("fingerprints").resolve("active.pointer").toString();
        fixture.fingerprints = new IndexingFixture.FingerprintStore() {
            @Override
            public Optional<ProjectFingerprintSnapshot> loadActive(UUID projectId) throws IOException {
                throw new IOException("active fingerprint missing at " + absolute);
            }
        };
        ProjectIndexingUseCase useCase = fixture.useCase();

        var dryRun = useCase.plan(fixture.id(), null, false);
        var outcome = useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);

        assertEquals(IndexingMode.FULL, dryRun.effectiveMode());
        assertEquals(List.of(ProjectInvalidationReason.FINGERPRINT_BASELINE_UNREADABLE),
                dryRun.plan().invalidation().reasons());
        assertEquals(Optional.of(ProjectIndexingUseCase.BASELINE_UNREADABLE_DIAGNOSTIC), dryRun.baselineDiagnostic());
        assertEquals("SUCCEEDED", outcome.status());
        String diagnostic = outcome.diagnostic().orElseThrow();
        assertTrue(diagnostic.startsWith("active fingerprint baseline unreadable"), diagnostic);
        assertFalse(diagnostic.contains(absolute), diagnostic);
        assertFalse(diagnostic.contains(fixture.root.toString()), diagnostic);
    }

    @Test
    void theProjectLeaseIsHeldThroughTheFingerprintPromotion() throws Exception {
        IndexingFixture fixture = new IndexingFixture(temp);
        BlockingPromotion fingerprints = new BlockingPromotion();
        fixture.fingerprints = fingerprints;
        ProjectIndexingUseCase useCase = fixture.useCase();
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME));
            assertTrue(fingerprints.promoteEntered.await(10, TimeUnit.SECONDS));
            AtomicReference<Thread> secondCaller = new AtomicReference<>();
            var second = pool.submit(() -> {
                secondCaller.set(Thread.currentThread());
                secondStarted.countDown();
                return useCase.execute(fixture.id(), null, false, IndexingResumePolicy.RESUME);
            });
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS));
            awaitWaiting(secondCaller.get(), Duration.ofSeconds(10));
            assertFalse(second.isDone(), "a second indexing stays behind the project lease during the promotion");
            assertEquals(1, fixture.executor.calls.get(), "no second provider run begins before the promotion ends");

            fingerprints.releasePromote.countDown();
            assertTrue(first.get(10, TimeUnit.SECONDS).fingerprintPromoted());
            assertEquals(ProjectIndexingUseCase.Outcome.NO_CHANGES, second.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(1, fixture.executor.calls.get());
    }

    /** Observes the caller parked on the lease instead of assuming it after a delay. */
    private static void awaitWaiting(Thread thread, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            boolean parked = state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING
                    || state == Thread.State.BLOCKED;
            if (parked && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                    frame.getClassName().startsWith(InMemoryIndexStateStore.class.getName()))) {
                return;
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("the second caller never waited on the project lease");
    }

    private static final class BlockingPromotion extends IndexingFixture.FingerprintStore {
        private final CountDownLatch promoteEntered = new CountDownLatch(1);
        private final CountDownLatch releasePromote = new CountDownLatch(1);

        @Override
        public void promote(UUID projectId, String indexSnapshotId) throws IOException {
            promoteEntered.countDown();
            try {
                if (!releasePromote.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("test timed out while blocking fingerprint promotion");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("fingerprint promotion test was interrupted", interrupted);
            }
            super.promote(projectId, indexSnapshotId);
        }
    }

    /** Called by the discovery once the module walk is over: a module appears at that precise moment. */
    private static final class ModuleCreatedAtDiscovery implements SourceRootDetector {
        private final Path project;
        private boolean created;

        private ModuleCreatedAtDiscovery(Path project) {
            this.project = project;
        }

        @Override
        public List<ProjectDiscovery.SourceRoot> detect(Path projectRoot, Path moduleRoot, ProjectIgnorePolicy ignorePolicy)
                throws IOException {
            if (!created) {
                created = true;
                Files.createDirectories(project.resolve("late"));
                Files.writeString(project.resolve("late/pom.xml"), "<project/>");
            }
            return List.of();
        }
    }
}
