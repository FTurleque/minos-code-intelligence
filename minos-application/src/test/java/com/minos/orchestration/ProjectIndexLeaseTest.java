package com.minos.orchestration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectIndexLeaseTest {

    @TempDir
    Path temporary;

    @Test
    void serializesConcurrentAcquisitionForSameProject() throws Exception {
        UUID projectId = UUID.randomUUID();
        AtomicBoolean secondAcquired = new AtomicBoolean(false);
        CountDownLatch started = new CountDownLatch(1);

        try (ProjectIndexLease first = ProjectIndexLease.acquire(temporary, projectId);
             var executor = Executors.newSingleThreadExecutor()) {
            var second = executor.submit(() -> {
                started.countDown();
                try (ProjectIndexLease ignored = ProjectIndexLease.acquire(temporary, projectId)) {
                    secondAcquired.set(true);
                }
                return null;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            Thread.sleep(100L);
            assertFalse(secondAcquired.get());
            first.close();
            second.get(2, TimeUnit.SECONDS);
            assertTrue(secondAcquired.get());
        }
    }
}
