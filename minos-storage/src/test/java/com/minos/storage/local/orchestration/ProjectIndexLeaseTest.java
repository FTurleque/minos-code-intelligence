package com.minos.storage.local.orchestration;

import com.minos.testsupport.ParkedThreads;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexLeaseTest {

    private static final String BOUNDED_FILE_LEASE = "com.minos.io.BoundedFileLease";

    @TempDir
    Path temporary;

    @Test
    void serializesConcurrentAcquisitionForSameProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Thread> secondCaller = new AtomicReference<>();

        try (ProjectIndexLease first = ProjectIndexLease.acquire(temporary, projectId);
             var executor = Executors.newSingleThreadExecutor()) {
            var second = executor.submit(() -> {
                secondCaller.set(Thread.currentThread());
                started.countDown();
                try (ProjectIndexLease ignored = ProjectIndexLease.acquire(temporary, projectId)) {
                    secondAcquired.set(true);
                }
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            // The second acquisition is observed waiting in the bounded file lease, not assumed after a delay.
            ParkedThreads.awaitParkedIn(secondCaller.get(), BOUNDED_FILE_LEASE, second::isDone, Duration.ofSeconds(5));
            assertFalse(secondAcquired.get(), "the second lease must wait while the first one is held");
            first.close();
            second.get(2, TimeUnit.SECONDS);
            assertTrue(secondAcquired.get());
        }
    }

    @Test
    void releasesJvmLockStateAfterLastLeaseCloses() throws Exception {
        int baseline = ProjectIndexLease.retainedJvmLockCount();
        for (int index = 0; index < 128; index++) {
            try (ProjectIndexLease ignored = ProjectIndexLease.acquire(temporary, UUID.randomUUID())) {
                assertTrue(ProjectIndexLease.retainedJvmLockCount() >= baseline + 1);
            }
        }
        assertTrue(ProjectIndexLease.retainedJvmLockCount() <= baseline);
    }
}
