package com.minos.program.analysis;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceStateKeyMemoTest {

    private static final Duration DELAY = Duration.ofSeconds(5);
    private static final UUID PROJECT = UUID.fromString("00000000-0000-0000-0000-000000000007");

    private final ProgramGraphSourceFixture.ManualClock clock = new ProgramGraphSourceFixture.ManualClock();
    private final SourceStateKeyMemo memo = new SourceStateKeyMemo(DELAY, clock);
    private final AtomicInteger computations = new AtomicInteger();

    @Test
    void keyIsComputedOnceThenReusedUntilTheDelayElapses() throws Exception {
        assertEquals("key-1", resolve("snapshot-a", true));
        clock.advance(DELAY.minusNanos(1));
        assertEquals("key-1", resolve("snapshot-a", true), "one nanosecond before the delay the key is reused");
        assertEquals(1, computations.get());

        clock.advance(Duration.ofNanos(1));
        assertEquals("key-2", resolve("snapshot-a", true), "at the delay the key is computed again");
        assertEquals("key-2", resolve("snapshot-a", true), "the delay restarts from the new computation");
        assertEquals(2, computations.get());
    }

    @Test
    void anotherSnapshotOfTheProjectIsNeverServedTheRememberedKey() throws Exception {
        assertEquals("key-1", resolve("snapshot-a", true));
        assertEquals("key-2", resolve("snapshot-b", true));
        assertEquals("key-3", resolve("snapshot-a", true), "one entry per project: the older snapshot was replaced");
        assertEquals(1, memo.size());
    }

    @Test
    void keyThatReadNoSourceIsNotRemembered() throws Exception {
        assertEquals("key-1", resolve("snapshot-a", false));
        assertEquals("key-2", resolve("snapshot-a", false), "nothing was hashed: there is nothing to spare");
        assertEquals(0, memo.size());

        assertEquals("key-3", resolve("snapshot-a", true));
        clock.advance(DELAY);
        assertEquals("key-4", resolve("snapshot-a", false));
        assertEquals(0, memo.size(), "a key that no longer covers sources drops the remembered one");
    }

    @Test
    void clockThatWentBackwardsIsNotTrusted() throws Exception {
        assertEquals("key-1", resolve("snapshot-a", true));
        clock.advance(Duration.ofSeconds(-1));
        assertEquals("key-2", resolve("snapshot-a", true));
    }

    @Test
    void memoKeepsABoundedNumberOfProjects() throws Exception {
        UUID first = new UUID(1L, 0L);
        for (long index = 0L; index <= SourceStateKeyMemo.MAX_ENTRIES; index++) {
            memo.resolve(new UUID(1L, index), "snapshot-a", () -> key(true));
        }
        assertEquals(SourceStateKeyMemo.MAX_ENTRIES, memo.size());
        int before = computations.get();
        memo.resolve(first, "snapshot-a", () -> key(true));
        assertEquals(before + 1, computations.get(), "the least recently used project was evicted");
        assertEquals(SourceStateKeyMemo.MAX_ENTRIES, memo.size());
    }

    @Test
    void delayMustBePositive() {
        Duration negative = Duration.ofSeconds(-1);
        assertThrows(IllegalArgumentException.class, () -> new SourceStateKeyMemo(Duration.ZERO, clock));
        assertThrows(IllegalArgumentException.class, () -> new SourceStateKeyMemo(negative, clock));
        assertThrows(NullPointerException.class, () -> new SourceStateKeyMemo(DELAY, null));
        assertThrows(NullPointerException.class, () -> memo.resolve(PROJECT, "snapshot-a", () -> null));
    }

    @RepeatedTest(50)
    void concurrentCallersOfOneProjectShareOneComputation() throws Exception {
        int callers = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return resolve("snapshot-a", true);
                }));
            }
            start.countDown();
            for (Future<String> result : results) {
                assertEquals("key-1", result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        }
        assertEquals(1, computations.get(), "the sources are hashed once, not once per concurrent caller");
    }

    private String resolve(String snapshotId, boolean readsSources) throws Exception {
        return memo.resolve(PROJECT, snapshotId, () -> key(readsSources)).key();
    }

    private JavaProgramGraphEngine.SourceStateKey key(boolean readsSources) {
        return new JavaProgramGraphEngine.SourceStateKey("key-" + computations.incrementAndGet(), readsSources);
    }
}
