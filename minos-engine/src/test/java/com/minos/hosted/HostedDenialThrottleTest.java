package com.minos.hosted;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostedDenialThrottleTest {
    private static final Instant NOW = Instant.parse("2026-07-29T09:00:00Z");

    @Test
    void defaultBudgetIsTenChainedRefusalsPerMinuteAndPerPrincipal() {
        HostedDenialThrottle throttle = new HostedDenialThrottle();
        UUID tenant = UUID.randomUUID();

        for (int index = 0; index < 10; index++) {
            assertTrue(throttle.tryAcquire(tenant, "viewer", NOW), "attempt " + index);
        }
        assertFalse(throttle.tryAcquire(tenant, "viewer", NOW.plusSeconds(59)));
        assertTrue(throttle.tryAcquire(tenant, "other", NOW.plusSeconds(59)));
        assertTrue(throttle.tryAcquire(UUID.randomUUID(), "viewer", NOW.plusSeconds(59)));

        Instant afterWindow = NOW.plusSeconds(61);
        for (int index = 0; index < 10; index++) {
            assertTrue(throttle.tryAcquire(tenant, "viewer", afterWindow), "attempt " + index + " after window");
        }
        assertFalse(throttle.tryAcquire(tenant, "viewer", afterWindow));
    }

    @Test
    void trackedPrincipalsAreBoundedByEvictingTheLeastRecentlyActive() {
        HostedDenialThrottle throttle = new HostedDenialThrottle(10, Duration.ofMinutes(1), 1_024);
        UUID tenant = UUID.randomUUID();

        for (int index = 0; index < 5_000; index++) {
            throttle.tryAcquire(tenant, "principal-" + index, NOW);
        }

        assertEquals(1_024, throttle.trackedPrincipals());
        assertTrue(throttle.tryAcquire(tenant, "principal-0", NOW));
        assertEquals(1_024, throttle.trackedPrincipals());
    }

    @Test
    void concurrentAttemptsNeverExceedTheBudget() throws Exception {
        HostedDenialThrottle throttle = new HostedDenialThrottle(10, Duration.ofMinutes(1), 64);
        UUID tenant = UUID.randomUUID();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                Callable<Integer> attempts = () -> {
                    start.await();
                    int acquired = 0;
                    for (int index = 0; index < 500; index++) {
                        if (throttle.tryAcquire(tenant, "viewer", NOW)) acquired++;
                    }
                    return acquired;
                };
                results.add(pool.submit(attempts));
            }
            start.countDown();
            int total = 0;
            for (Future<Integer> result : results) {
                total += result.get(30, TimeUnit.SECONDS);
            }
            assertEquals(10, total);
        } finally {
            pool.shutdownNow();
        }
    }
}
