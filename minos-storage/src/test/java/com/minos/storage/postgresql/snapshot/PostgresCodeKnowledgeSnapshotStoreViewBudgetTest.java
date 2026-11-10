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
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-15 — the PostgreSQL store reads the size and the format header of the active payload with its metadata,
 * refuses a payload above the persisted ceiling without asking the server for it, and reserves on the view budget,
 * before the payload is fetched, the larger of the view weight and the transient of the driver while it fetches the
 * row. Only the API of {@link PostgresTestSupport} is used ({@code connections}).
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
