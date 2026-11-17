package com.minos.storage.postgresql.snapshot;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.diagnostics.SnapshotMemoryExhaustedException;
import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.QueryViewMemoryBudget;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.store.CodeKnowledgeSnapshot;
import com.minos.store.SnapshotReadView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-15 — the PostgreSQL store reads the size and the format header of the active payload with its metadata,
 * refuses a payload above the persisted ceiling without asking the server for it, and reserves on the view budget,
 * before the payload is fetched, the larger of the view weight and the transient of the driver while it fetches the
 * row. The payload asked for under a reservation is the version whose size was reserved (AUD-PERF-05), and a view
 * above the cache ceiling is held alone and stays counted (AUD-PERF-06). Only the API of
 * {@link PostgresTestSupport} is used ({@code connections}).
 */
class PostgresCodeKnowledgeSnapshotStoreViewBudgetTest extends PostgresTestSupport {

    private static final Duration LONG = Duration.ofSeconds(30);

    @TempDir Path tempDir;

    private final AtomicInteger scratchFiles = new AtomicInteger();
    private final AtomicInteger payloadFetches = new AtomicInteger();

    /** About 1 MB in V3: one large ASCII string, no relationship. */
    private static CodeKnowledgeSnapshot snapshot(UUID projectId) {
        return PersistedSizeFixtures.withTail(projectId, "budget", 0, 1_000_000);
    }

    @Test
    void aRowAboveThePersistedCeilingIsRefusedWithoutFetchingItsPayload() throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = publish(projectId);
        long payload = new SnapshotCodecV3().encodedSize(snapshot);
        SnapshotLimits lowered = SnapshotLimits.production().withMaxPersistedBytes(payload - 1L);
        PostgresCodeKnowledgeSnapshotStore reader =
                store(lowered, QueryViewMemoryBudget.of(1L << 40, 50, LONG), ignored -> payloadFetches.incrementAndGet());

        IOException refused = assertThrows(IOException.class, () -> reader.loadActiveReadView(projectId));

        assertEquals("snapshot payload exceeds persisted byte limit: " + payload + "/" + (payload - 1L),
                refused.getMessage());
        assertEquals(0, payloadFetches.get(), "the payload was never asked for");
        assertEquals(0, scratchFiles.get(), "no scratch file was created");
    }

    @Test
    void aFetchTransientAboveTheBudgetIsRefusedAtAdmissionBeforeThePayloadIsFetched() throws Exception {
        UUID projectId = UUID.randomUUID();
        CodeKnowledgeSnapshot snapshot = publish(projectId);
        long payload = new SnapshotCodecV3().encodedSize(snapshot);
        // A budget of twice the payload holds the view (125 percent) but not the fetch transient (300 percent).
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(4L * payload, 50, LONG);
        assertTrue(SnapshotLimits.production().amplifiedBytes(3, payload) < budget.budgetBytes());
        assertTrue(SnapshotLimits.production().fetchTransientBytes(payload) > budget.budgetBytes());
        PostgresCodeKnowledgeSnapshotStore reader =
                store(SnapshotLimits.production(), budget, ignored -> payloadFetches.incrementAndGet());

        SnapshotMemoryExhaustedException refused =
                assertThrows(SnapshotMemoryExhaustedException.class, () -> reader.loadActiveReadView(projectId));

        assertTrue(refused.getMessage().contains(QueryViewMemoryBudget.VARIABLE), refused.getMessage());
        assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
        assertEquals(0, payloadFetches.get());
        assertEquals(0, scratchFiles.get());
        assertEquals(0L, budget.stats().reservedBytes());
    }

    @Test
    void aSecondProjectWaitsForTheFirstFetchThenLoads() throws Exception {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        // 64 build-lock stripes: on a shared stripe, B would wait for A's project lock instead of the budget.
        while (Math.floorMod(projectB.hashCode(), 64) == Math.floorMod(projectA.hashCode(), 64)) {
            projectB = UUID.randomUUID();
        }
        long payload = new SnapshotCodecV3().encodedSize(publish(projectA));
        publish(projectB);
        // 4.5 payloads: one fetch transient (3) fits, two do not; a cached view (1.25) beside one fetch does.
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(9L * payload, 50, LONG);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        PostgresCodeKnowledgeSnapshotStore reader = store(SnapshotLimits.production(), budget, projectId -> {
            payloadFetches.incrementAndGet();
            if (!projectId.equals(projectA)) return;
            entered.countDown();
            try {
                assertTrue(released.await(30, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });

        CompletableFuture<SnapshotReadView> first = load(reader, projectA);
        assertTrue(entered.await(30, TimeUnit.SECONDS));
        CompletableFuture<SnapshotReadView> second = load(reader, projectB);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (budget.stats().waits() < 1L) {
            if (System.nanoTime() > deadline) throw new AssertionError("B never waited");
            Thread.onSpinWait();
        }
        assertFalse(second.isDone());
        assertEquals(1, payloadFetches.get(), "B waits before fetching anything");

        released.countDown();

        assertEquals(projectA, first.get(30, TimeUnit.SECONDS).snapshot().projectId());
        assertEquals(projectB, second.get(30, TimeUnit.SECONDS).snapshot().projectId());
        assertEquals(0L, budget.stats().reservedBytes());
        assertEquals(2, reader.queryCacheStats().entries());
    }

    @Test
    void underASmallHeapTheEffectiveCacheCeilingIsTheBudget() throws Exception {
        QueryViewMemoryBudget small = QueryViewMemoryBudget.of(256L * 1024L * 1024L, 50, LONG);
        QueryViewMemoryBudget large = QueryViewMemoryBudget.of(4_096L * 1024L * 1024L, 50, LONG);

        assertEquals(small.budgetBytes(), store(SnapshotLimits.production(), small, ignored -> { })
                .queryCacheStats().maximumWeightBytes());
        assertEquals(SnapshotLimits.DEFAULT_MAX_QUERY_CACHE_WEIGHT_BYTES,
                store(SnapshotLimits.production(), large, ignored -> { }).queryCacheStats().maximumWeightBytes());
    }

    @Test
    void aLargerSnapshotPublishedBeforeThePayloadFetchIsNeverFetchedUnderTheFormerReservation() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1L << 40, 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(SnapshotLimits.production(), budget, loading -> {
            if (payloadFetches.incrementAndGet() == 1) publishLarger(loading, "larger");
        });

        SnapshotReadView view = reader.loadActiveReadView(projectId).orElseThrow();

        assertEquals("larger", view.snapshot().snapshotId(), "the resolution started again on the new version");
        assertEquals(2, payloadFetches.get());
        assertEquals(1, scratchFiles.get(), "only the reserved version was copied");
        assertEquals(0L, budget.stats().reservedBytes());
        assertScratchDirectoryEmpty();
    }

    @Test
    void aNewVersionHeavierThanTheBudgetIsRefusedWithoutFetchingItsPayload() throws Exception {
        UUID projectId = UUID.randomUUID();
        long payload = new SnapshotCodecV3().encodedSize(publish(projectId));
        long fetch = SnapshotLimits.production().fetchTransientBytes(payload);
        // The budget holds the reservation of the first version, not that of a version three times larger.
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(2L * (fetch + 1_024L), 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(SnapshotLimits.production(), budget, loading -> {
            if (payloadFetches.incrementAndGet() == 1) publishLarger(loading, "larger");
        });

        SnapshotMemoryExhaustedException refused =
                assertThrows(SnapshotMemoryExhaustedException.class, () -> reader.loadActiveReadView(projectId));

        assertTrue(refused.getMessage().startsWith("insufficient memory to load this snapshot"), refused.getMessage());
        assertEquals(1, payloadFetches.get(), "the payload of the new version was never asked for");
        assertEquals(0, scratchFiles.get(), "no payload was copied");
        assertEquals(0L, budget.stats().reservedBytes());
        assertScratchDirectoryEmpty();
    }

    @Test
    void aStableActiveVersionIsFetchedOnce() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        PostgresCodeKnowledgeSnapshotStore reader = store(SnapshotLimits.production(),
                QueryViewMemoryBudget.of(1L << 40, 50, LONG), ignored -> payloadFetches.incrementAndGet());

        SnapshotReadView view = reader.loadActiveReadView(projectId).orElseThrow();

        assertEquals("budget", view.snapshot().snapshotId());
        assertEquals(1, payloadFetches.get());
        assertEquals(1, scratchFiles.get());
    }

    @Test
    void anActiveVersionReplacedBeforeEveryFetchExhaustsTheRetriesWithoutCopyingAPayload() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1L << 40, 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(SnapshotLimits.production(), budget,
                loading -> publishLarger(loading, "replaced-" + payloadFetches.incrementAndGet()));

        IOException refused = assertThrows(IOException.class, () -> reader.loadActiveReadView(projectId));

        assertTrue(refused.getMessage().contains("retry limit exceeded"), refused.getMessage());
        assertEquals(4, payloadFetches.get());
        assertEquals(0, scratchFiles.get());
        assertEquals(0L, budget.stats().reservedBytes(), "no reservation is kept");
        assertScratchDirectoryEmpty();
    }

    @Test
    void aViewAboveTheCacheCeilingIsHeldAloneAndCountedAsResident() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        long weight = viewWeight(projectId);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1L << 40, 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(ceilingBelow(weight), budget,
                ignored -> payloadFetches.incrementAndGet());

        SnapshotReadView first = reader.loadActiveReadView(projectId).orElseThrow();
        SnapshotReadView second = reader.loadActiveReadView(projectId).orElseThrow();

        assertSame(first, second, "the held view serves the next read");
        assertEquals(1, payloadFetches.get());
        assertEquals(0, reader.queryCacheStats().entries());
        assertEquals(weight, reader.queryCacheStats().heldWeightBytes());
        assertTrue(budget.stats().residentBytes() >= weight, "the held view is counted");
        assertEquals(0L, budget.stats().reservedBytes());
    }

    @Test
    void theCachedViewsGiveWayToAHeldView() throws Exception {
        UUID small = UUID.randomUUID();
        UUID large = UUID.randomUUID();
        CodeKnowledgeSnapshot tiny = PersistedSizeFixtures.withTail(small, "small", 0, 10);
        new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                .publish(small, "small", tiny.symbols(), List.of(), List.of());
        publish(large);
        long weight = viewWeight(large);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1L << 40, 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(ceilingBelow(weight), budget, ignored -> { });
        reader.loadActiveReadView(small).orElseThrow();
        assertEquals(1, reader.queryCacheStats().entries());

        reader.loadActiveReadView(large).orElseThrow();

        assertEquals(0, reader.queryCacheStats().entries(), "the small view left the cache");
        assertEquals(weight, reader.queryCacheStats().heldWeightBytes());
        QueryViewMemoryBudget.Stats stats = budget.stats();
        assertTrue(stats.reservedBytes() + stats.residentBytes() <= stats.budgetBytes());
    }

    @Test
    void aHeldViewIsReleasedToAdmitAnotherLoadWithoutWaiting() throws Exception {
        UUID projectA = UUID.randomUUID();
        UUID projectB = UUID.randomUUID();
        long payload = new SnapshotCodecV3().encodedSize(publish(projectA));
        publish(projectB);
        long weight = viewWeight(projectA);
        // Room for one fetch transient beside nothing else: B is admitted only once A is released.
        long fetch = SnapshotLimits.production().fetchTransientBytes(payload);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(2L * (fetch + weight / 2L), 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(ceilingBelow(weight), budget, ignored -> { });
        reader.loadActiveReadView(projectA).orElseThrow();

        SnapshotReadView other = reader.loadActiveReadView(projectB).orElseThrow();

        assertEquals(projectB, other.snapshot().projectId());
        assertEquals(0L, budget.stats().waits(), "the held view was released, nothing waited");
        assertEquals(weight, budget.stats().residentBytes(), "only B is resident");
        assertEquals(0L, budget.stats().refusals());
    }

    @Test
    void aPublicationOfTheProjectReleasesItsHeldView() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        long weight = viewWeight(projectId);
        QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1L << 40, 50, LONG);
        PostgresCodeKnowledgeSnapshotStore reader = store(ceilingBelow(weight), budget, ignored -> { });
        reader.loadActiveReadView(projectId).orElseThrow();

        CodeKnowledgeSnapshot next = PersistedSizeFixtures.withTail(projectId, "next", 0, 1_000_000);
        reader.publish(projectId, "next", next.symbols(), List.of(), List.of());

        assertEquals(0L, reader.queryCacheStats().heldWeightBytes());
        assertEquals(0L, budget.stats().residentBytes());
    }

    /** Publishes, from another store, a version of {@code projectId} three times larger than {@link #snapshot}. */
    private void publishLarger(UUID projectId, String snapshotId) {
        CodeKnowledgeSnapshot larger = PersistedSizeFixtures.withTail(projectId, snapshotId, 0, 3_000_000);
        try {
            new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                    .publish(projectId, snapshotId, larger.symbols(), List.of(), List.of());
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** Estimated weight of the active view of {@code projectId}, as an unconstrained store caches it. */
    private long viewWeight(UUID projectId) throws IOException {
        PostgresCodeKnowledgeSnapshotStore measuring = new PostgresCodeKnowledgeSnapshotStore(connections, tempDir,
                created -> { }, SnapshotLimits.production(), QueryViewMemoryBudget.of(1L << 40, 50, LONG),
                ignored -> { });
        measuring.loadActiveReadView(projectId).orElseThrow();
        return measuring.queryCacheStats().weightBytes();
    }

    /** Production limits with a cache ceiling one byte below {@code weight}: such a view is above the ceiling. */
    private static SnapshotLimits ceilingBelow(long weight) {
        return SnapshotLimits.production().withMaxQueryCacheWeightBytes(weight - 1L);
    }

    private void assertScratchDirectoryEmpty() throws IOException {
        try (Stream<Path> files = Files.list(tempDir.resolve("postgresql-snapshot-scratch"))) {
            assertEquals(List.of(), files.toList(), "no scratch file remains");
        }
    }

    private CodeKnowledgeSnapshot publish(UUID projectId) throws IOException {
        CodeKnowledgeSnapshot snapshot = snapshot(projectId);
        new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                .publish(projectId, "budget", snapshot.symbols(), List.of(), List.of());
        return snapshot;
    }

    private PostgresCodeKnowledgeSnapshotStore store(
            SnapshotLimits limits, QueryViewMemoryBudget budget, Consumer<UUID> beforePayloadFetch) throws IOException {
        return new PostgresCodeKnowledgeSnapshotStore(connections, tempDir,
                created -> scratchFiles.incrementAndGet(), limits, budget, beforePayloadFetch);
    }

    private static CompletableFuture<SnapshotReadView> load(PostgresCodeKnowledgeSnapshotStore store, UUID projectId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return store.loadActiveReadView(projectId).orElseThrow();
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }
}
