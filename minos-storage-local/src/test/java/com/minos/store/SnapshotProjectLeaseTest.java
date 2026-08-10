package com.minos.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotProjectLeaseTest {
    @TempDir Path storage;

    @Test
    void sameProjectSerializesPublicationAndRetentionMutations() throws Exception {
        UUID projectId = UUID.randomUUID();
        try (SnapshotProjectLease first = SnapshotProjectLease.acquire(storage, projectId)) {
            CountDownLatch acquired = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future = executor.submit(() -> {
                    try (SnapshotProjectLease ignored = SnapshotProjectLease.acquire(storage, projectId)) {
                        acquired.countDown();
                    }
                    return null;
                });
                assertFalse(acquired.await(200, TimeUnit.MILLISECONDS));
                first.close();
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                future.get(5, TimeUnit.SECONDS);
            }
        }
    }
}
