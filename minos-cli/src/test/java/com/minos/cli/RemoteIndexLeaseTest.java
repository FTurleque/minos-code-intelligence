package com.minos.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteIndexLeaseTest {
    @TempDir Path home;

    @Test
    void sameSourceSerializesCompetingOperations() throws Exception {
        try (RemoteIndexLease first = RemoteIndexLease.acquire(home, "remote-cache-key")) {
            CountDownLatch acquired = new CountDownLatch(1);
            try (var executor = Executors.newSingleThreadExecutor()) {
                var future = executor.submit(() -> {
                    try (RemoteIndexLease ignored = RemoteIndexLease.acquire(home, "remote-cache-key")) {
                        acquired.countDown();
                    }
                    return null;
                });
                assertFalse(acquired.await(Duration.ofMillis(200).toMillis(), TimeUnit.MILLISECONDS));
                first.close();
                assertTrue(acquired.await(5, TimeUnit.SECONDS));
                future.get(5, TimeUnit.SECONDS);
            }
        }
    }
}
