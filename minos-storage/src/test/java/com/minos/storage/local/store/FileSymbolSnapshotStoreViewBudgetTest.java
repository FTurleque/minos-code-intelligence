package com.minos.storage.local.store;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.diagnostics.SnapshotMemoryExhaustedException;
import com.minos.storage.codec.QueryViewMemoryBudget;
import com.minos.storage.codec.QueryViewWeightFixtures;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-15 — the file store reserves the estimated weight of a view on the process-wide view budget before it
 * reads the snapshot. Two projects of the same shape are loaded against a budget that holds one view and a half:
 * the second load waits for the first, expires, or evicts a cached view, and a view heavier than the budget is
 * refused before any byte is decoded. The first load is held inside its reservation by the build hook.
 */
class FileSymbolSnapshotStoreViewBudgetTest {

    private static final Duration LONG = Duration.ofSeconds(30);
    private static final Duration SHORT = Duration.ofMillis(300);

    private final UUID projectA = UUID.randomUUID();
    private final UUID projectB = onAnotherBuildLockStripe(projectA);

    @Test
    void aSecondLoadWaitsForTheFirstThenSucceedsWithinTheBudget(@TempDir Path root) throws Exception {
        long weight = publishBoth(root);
        QueryViewMemoryBudget budget = budgetOfOneViewAndAHalf(weight, LONG);
        HeldLoad held = new HeldLoad(projectA);
        AtomicLong highestUse = new AtomicLong();
        FileSymbolSnapshotStore store = store(root, budget, (projectId, descriptor) -> {
            QueryViewMemoryBudget.Stats stats = budget.stats();
            highestUse.accumulateAndGet(stats.reservedBytes() + stats.residentBytes(), Math::max);
            held.hook(projectId);
        });

        CompletableFuture<SnapshotReadView> first = load(store, projectA);
        held.awaitEntered();
        CompletableFuture<SnapshotReadView> second = load(store, projectB);
        awaitWaits(budget, 1L);
        assertFalse(second.isDone(), "B waits while A holds the budget");

        held.release();

        assertEquals(projectA, first.get(30, TimeUnit.SECONDS).snapshot().projectId());
        assertEquals(projectB, second.get(30, TimeUnit.SECONDS).snapshot().projectId());
        assertTrue(highestUse.get() <= budget.budgetBytes(), highestUse.get() + " > " + budget.budgetBytes());
        assertEquals(0L, budget.stats().reservedBytes());
        assertEquals(1, store.cacheStats().entries(), "A was evicted to admit B");
        assertEquals(0L, budget.stats().refusals());
    }

    @Test
    void anExpiredWaitRefusesTheSecondLoadBeforeItDecodesAnything(@TempDir Path root) throws Exception {
        long weight = publishBoth(root);
        QueryViewMemoryBudget budget = budgetOfOneViewAndAHalf(weight, SHORT);
        HeldLoad held = new HeldLoad(projectA);
        FileSymbolSnapshotStore store = store(root, budget, (projectId, descriptor) -> held.hook(projectId));

        CompletableFuture<SnapshotReadView> first = load(store, projectA);
        held.awaitEntered();
        SnapshotMemoryExhaustedException refused =
                assertThrows(SnapshotMemoryExhaustedException.class, () -> store.loadActiveReadView(projectB));

        assertTrue(refused.getMessage().startsWith("insufficient memory to load this snapshot"), refused.getMessage());
        assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
        assertEquals(1L, store.cacheStats().fullSnapshotLoads(), "only A was decoded");
        held.release();
        assertEquals(projectA, first.get(30, TimeUnit.SECONDS).snapshot().projectId(), "A ends normally");
        assertEquals(0L, budget.stats().reservedBytes());
    }

    @Test
    void aViewHeavierThanTheBudgetIsRefusedAtOnceWithoutReadingTheSnapshot(@TempDir Path root) throws Exception {
        long weight = publishBoth(root);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(weight, 50, LONG);
        FileSymbolSnapshotStore store = store(root, budget, ActiveQueryBuildHook.NOOP);

        SnapshotMemoryExhaustedException refused =
                assertThrows(SnapshotMemoryExhaustedException.class, () -> store.loadActiveReadView(projectA));

        assertTrue(refused.getMessage().contains(QueryViewMemoryBudget.VARIABLE), refused.getMessage());
        assertTrue(refused.getMessage().contains(weight + "-byte maximum heap"), refused.getMessage());
        assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
        assertFalse(refused.getMessage().contains(root.toString()));
        assertEquals(0L, store.cacheStats().fullSnapshotLoads());
        assertEquals(0L, budget.stats().reservedBytes(), "no reservation is kept");
        assertEquals(0L, budget.stats().waits());
    }

    @Test
    void aCachedViewIsEvictedToAdmitALoadWithoutWaiting(@TempDir Path root) throws Exception {
        long weight = publishBoth(root);
        QueryViewMemoryBudget budget = budgetOfOneViewAndAHalf(weight, LONG);
        FileSymbolSnapshotStore store = store(root, budget, ActiveQueryBuildHook.NOOP);

        SnapshotReadView first = store.loadActiveReadView(projectA).orElseThrow();
        assertEquals(weight, budget.stats().residentBytes());
        store.loadActiveReadView(projectB).orElseThrow();
        SnapshotReadView again = store.loadActiveReadView(projectA).orElseThrow();

        assertEquals(0L, budget.stats().waits(), "the cached view was evicted, nothing waited");
        assertNotSame(first, again, "A was evicted to admit B, then reloaded");
        assertEquals(3L, store.cacheStats().fullSnapshotLoads());
        assertEquals(weight, budget.stats().residentBytes());
    }

    @Test
    void underASmallHeapTheEffectiveCacheCeilingIsTheBudget(@TempDir Path root) throws Exception {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(256L * 1024L * 1024L, 50, LONG);

        FileSymbolSnapshotStore store = store(root, budget, ActiveQueryBuildHook.NOOP);

        assertEquals(budget.budgetBytes(), store.cacheStats().maximumWeightBytes());
        assertTrue(store.cacheStats().maximumWeightBytes() < SnapshotLimits.production().largestPersistableViewWeightBytes());
    }

    @Test
    void underALargeHeapTheEffectiveCacheCeilingIsTheConfiguredOne(@TempDir Path root) throws Exception {
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(4_096L * 1024L * 1024L, 50, LONG);

        FileSymbolSnapshotStore store = store(root, budget, ActiveQueryBuildHook.NOOP);

        assertEquals(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES, store.cacheStats().maximumWeightBytes());
    }

    @Test
    void anInvalidBudgetSettingRefusesTheLoadWithoutReadingTheSnapshot(@TempDir Path root) throws Exception {
        publishBoth(root);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.resolve(4_096L * 1024L * 1024L,
                Map.of(QueryViewMemoryBudget.VARIABLE, "95"), new Properties(), LONG);
        FileSymbolSnapshotStore store = store(root, budget, ActiveQueryBuildHook.NOOP);

        IOException refused = assertThrows(IOException.class, () -> store.loadActiveReadView(projectA));

        assertTrue(refused.getMessage().contains("MINOS_VIEW_HEAP_PERCENT"), refused.getMessage());
        assertTrue(refused.getMessage().contains("from 10 to 80"), refused.getMessage());
        assertEquals(0L, store.cacheStats().fullSnapshotLoads());
    }

    @Test
    void aMissingSnapshotFileIsStillReportedByItsNameBeforeAnyReservation(@TempDir Path root) throws Exception {
        publishBoth(root);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(4_096L * 1024L * 1024L, 50, LONG);
        FileSymbolSnapshotStore store = store(root, budget, ActiveQueryBuildHook.NOOP);
        try (var files = java.nio.file.Files.walk(root.resolve(projectA.toString()))) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".knowledge")).toList()) {
                java.nio.file.Files.delete(file);
            }
        }

        IOException refused = assertThrows(IOException.class, () -> store.loadActiveReadView(projectA));

        assertTrue(refused.getMessage().startsWith("active symbol snapshot file is missing: "), refused.getMessage());
        assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
        assertEquals(0L, budget.stats().reservedBytes());
    }

    /**
     * A project whose build lock is not the stripe of {@code other} (64 stripes, {@code FileSymbolSnapshotStore}):
     * on a shared stripe, B would wait for A's project lock instead of the budget.
     */
    static UUID onAnotherBuildLockStripe(UUID other) {
        UUID candidate = UUID.randomUUID();
        while (Math.floorMod(candidate.hashCode(), 64) == Math.floorMod(other.hashCode(), 64)) {
            candidate = UUID.randomUUID();
        }
        return candidate;
    }

    /** Publishes A and B with the same shape and returns the estimated weight of one view. */
    private long publishBoth(Path root) throws IOException {
        FileSymbolSnapshotStore publisher = new FileSymbolSnapshotStore(root);
        for (UUID projectId : List.of(projectA, projectB)) {
            CodeKnowledgeSnapshot snapshot = QueryViewWeightFixtures.repository(projectId, "budget", 2,
                    List.<String[]>of(QueryViewWeightFixtures.ASCII));
            publisher.publish(projectId, "budget", snapshot.symbols(), snapshot.occurrences(), snapshot.relationships());
        }
        FileSymbolSnapshotStore measuring = store(root, QueryViewMemoryBudget.of(1L << 40, 50, LONG),
                ActiveQueryBuildHook.NOOP);
        measuring.loadActiveReadView(projectA).orElseThrow();
        long weightA = measuring.cacheStats().weightBytes();
        measuring.loadActiveReadView(projectB).orElseThrow();
        assertEquals(2L * weightA, measuring.cacheStats().weightBytes(), "both views weigh the same");
        return weightA;
    }

    /** A budget that holds one view and a half: 50 percent of a heap of three views. */
    private static QueryViewMemoryBudget budgetOfOneViewAndAHalf(long weight, Duration wait) {
        return QueryViewMemoryBudget.of(3L * weight, 50, wait);
    }

    private static FileSymbolSnapshotStore store(Path root, QueryViewMemoryBudget budget, ActiveQueryBuildHook hook)
            throws IOException {
        return new FileSymbolSnapshotStore(root, FileSymbolSnapshotStore.DEFAULT_MAX_QUERY_CACHE_ENTRIES,
                SnapshotLimits.production(), FileSymbolSnapshotStore.DEFAULT_MAX_ACTIVE_QUERY_RETRIES, hook, budget);
    }

    private static CompletableFuture<SnapshotReadView> load(FileSymbolSnapshotStore store, UUID projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.loadActiveReadView(projectId).orElseThrow();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static void awaitWaits(QueryViewMemoryBudget budget, long expected) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (budget.stats().waits() < expected) {
            if (System.nanoTime() > deadline) throw new AssertionError("no load started waiting");
            Thread.onSpinWait();
        }
    }

    /** Holds the load of one project inside its reservation until the test releases it. */
    private static final class HeldLoad {
        private final UUID projectId;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        HeldLoad(UUID projectId) {
            this.projectId = projectId;
        }

        void hook(UUID loading) throws IOException {
            if (!projectId.equals(loading)) return;
            entered.countDown();
            try {
                if (!released.await(30, TimeUnit.SECONDS)) throw new IOException("held load was never released");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("held load interrupted", interrupted);
            }
        }

        void awaitEntered() throws InterruptedException {
            assertTrue(entered.await(30, TimeUnit.SECONDS), "the first load never reached its build hook");
        }

        void release() {
            released.countDown();
        }
    }
}
