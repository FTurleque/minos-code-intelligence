package com.minos.semantic;

import com.minos.application.MinosApplication;
import com.minos.domain.Origin;
import com.minos.domain.OriginType;
import com.minos.domain.PositionEncoding;
import com.minos.domain.ResolutionStatus;
import com.minos.domain.Symbol;
import com.minos.domain.SymbolIdentityQuality;
import com.minos.domain.SymbolKind;
import com.minos.domain.SymbolLocation;
import com.minos.registry.RegisteredProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the MINOS-03 invariant: an index built for an obsolete structural snapshot
 * cannot replace a more recent one, regardless of interleaving.
 *
 * <p>All concurrency is controlled via latches — no {@code Thread.sleep} for ordering.
 */
class SemanticSyncConsistencyTest {

    private static final Origin ORIGIN = new Origin("test", "UNIT", "1", "run-m03", OriginType.OTHER);

    // -------------------------------------------------------------------------
    // Primary race: stale writer is rejected, durable index is N+1
    // -------------------------------------------------------------------------

    @Test
    void staleWriterIsRejectedWhenSnapshotPromotedDuringEmbed(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("minos-home");
        Path project = setupProjectFiles(temp.resolve("project"));

        CountDownLatch embedStarted = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        SlowEmbeddingProvider slow = new SlowEmbeddingProvider(embedStarted, proceed);

        MinosApplication app = MinosApplication.builder(home).embeddingProvider(slow).build();
        RegisteredProject reg = app.projectRegistry().registerProject(project, "test-project");

        // Publish snap-1 as the initial active structural snapshot
        app.snapshotStore().publish(reg.id(), "snap-1", symbols(reg.id()), List.of(), List.of());

        // Launch sync A (for snap-1), which will block during embedding
        Future<Object> syncA;
        try (var executor = Executors.newSingleThreadExecutor()) {
            syncA = executor.submit(() -> {
                try {
                    app.semanticIndexService().synchronize(reg.id());
                    return null;
                } catch (IOException exception) {
                    return exception; // return, not throw, so future.get() doesn't wrap in ExecutionException
                }
            });

            // Wait for sync A to reach embed — active=snap-1 has been captured
            assertTrue(embedStarted.await(10, TimeUnit.SECONDS),
                    "sync A must start embedding before snap-2 is promoted");

            // Promote snap-2 while sync A is blocked inside embed
            app.snapshotStore().publish(reg.id(), "snap-2", symbols(reg.id()), List.of(), List.of());

            // Release sync A — embed continues, then replaceConditionally detects the staleness
            proceed.countDown();

            // Sync A must complete quickly now that the latch is released
            Object result = syncA.get(15, TimeUnit.SECONDS);

            // The result must be a StaleSemanticSyncException — not a clean UpdateReport
            assertInstanceOf(StaleSemanticSyncException.class, result,
                    "sync A should have been aborted with StaleSemanticSyncException, got: " + result);
            StaleSemanticSyncException ex = (StaleSemanticSyncException) result;
            assertNotNull(ex.getMessage());
            assertTrue(ex.getMessage().contains("snap-1"), "message should reference the stale snapshot");
            assertTrue(ex.getMessage().contains("snap-2"), "message should reference the promoted snapshot");
        }

        // The durable semantic index must be absent (sync A was rejected, sync B was never run)
        assertTrue(app.semanticVectorStore().metadata(reg.id().toString()).isEmpty(),
                "no semantic index should exist — stale writer was rejected, snap-2 was never synced");
    }

    // -------------------------------------------------------------------------
    // Two syncs for the same version: second one must also succeed
    // -------------------------------------------------------------------------

    @Test
    void twoSyncsForSameVersionBothSucceed(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("minos-home");
        Path project = setupProjectFiles(temp.resolve("project"));

        MinosApplication app = MinosApplication.builder(home).embeddingProvider(new FastEmbeddingProvider()).build();
        RegisteredProject reg = app.projectRegistry().registerProject(project, "test-project");
        app.snapshotStore().publish(reg.id(), "snap-1", symbols(reg.id()), List.of(), List.of());

        SemanticIndexService.UpdateReport first = app.semanticIndexService().synchronize(reg.id());
        assertEquals(SemanticIndexService.State.READY, first.state());
        assertEquals("snap-1", first.snapshotId());

        // Second sync on the same version: active is still snap-1, so replaceConditionally passes
        SemanticIndexService.UpdateReport second = app.semanticIndexService().synchronize(reg.id());
        assertEquals(SemanticIndexService.State.READY, second.state(),
                "second sync for the same snapshot version must also succeed");
        assertEquals("snap-1", second.snapshotId());

        // Durable index reflects snap-1
        SemanticVectorStore.IndexMetadata meta = app.semanticVectorStore().metadata(reg.id().toString()).orElseThrow();
        assertEquals("snap-1", meta.snapshotId());
    }

    // -------------------------------------------------------------------------
    // Independent projects: X and Y must not be serialized by each other
    // -------------------------------------------------------------------------

    @Test
    void twoProjectsDoNotSerializeEachOther(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("minos-home");
        Path projectX = setupProjectFiles(temp.resolve("project-x"));
        Path projectY = setupProjectFiles(temp.resolve("project-y"));

        CountDownLatch xStarted = new CountDownLatch(1);
        CountDownLatch xProceed = new CountDownLatch(1);
        // X is slow; Y must complete without waiting for X to finish
        SlowEmbeddingProvider slowForX = new SlowEmbeddingProvider(xStarted, xProceed);

        MinosApplication appX = MinosApplication.builder(home).embeddingProvider(slowForX).build();
        MinosApplication appY = MinosApplication.builder(home).embeddingProvider(new FastEmbeddingProvider()).build();

        RegisteredProject regX = appX.projectRegistry().registerProject(projectX, "project-x");
        RegisteredProject regY = appX.projectRegistry().registerProject(projectY, "project-y");

        appX.snapshotStore().publish(regX.id(), "snap-x1", symbols(regX.id()), List.of(), List.of());
        appX.snapshotStore().publish(regY.id(), "snap-y1", symbols(regY.id()), List.of(), List.of());

        AtomicReference<SemanticIndexService.UpdateReport> reportY = new AtomicReference<>();
        AtomicReference<Throwable> errorY = new AtomicReference<>();

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> futureX = executor.submit(() -> {
                try {
                    appX.semanticIndexService().synchronize(regX.id());
                } catch (IOException ignored) {}
            });

            // Wait for X to be inside embed (holding its per-project lock), then sync Y
            assertTrue(xStarted.await(10, TimeUnit.SECONDS), "X must start before we sync Y");

            Future<?> futureY = executor.submit(() -> {
                try {
                    reportY.set(appY.semanticIndexService().synchronize(regY.id()));
                } catch (Throwable t) {
                    errorY.set(t);
                }
            });

            // Y must complete while X is still blocked — proof the locks are per-project
            futureY.get(15, TimeUnit.SECONDS);
            assertNull(errorY.get(), "project Y sync must succeed independently of X");
            assertNotNull(reportY.get());
            assertEquals(SemanticIndexService.State.READY, reportY.get().state());

            // Now release X
            xProceed.countDown();
            futureX.get(15, TimeUnit.SECONDS);
        }
    }

    // -------------------------------------------------------------------------
    // Crash without lock leak: exception during active-reader must not strand lock
    // -------------------------------------------------------------------------

    @Test
    void exceptionInActiveReaderReleasesLock(@TempDir Path temp) throws Exception {
        Path home = temp.resolve("minos-home");
        Path project = setupProjectFiles(temp.resolve("project"));

        MinosApplication app = MinosApplication.builder(home).embeddingProvider(new FastEmbeddingProvider()).build();
        RegisteredProject reg = app.projectRegistry().registerProject(project, "test-project");
        app.snapshotStore().publish(reg.id(), "snap-1", symbols(reg.id()), List.of(), List.of());

        // Sync with a reader that throws — simulates a snapshot-store I/O failure
        SemanticVectorStore.IndexSnapshot stub = new SemanticVectorStore.IndexSnapshot(
                reg.id().toString(), "snap-1", "test-provider", "test-model", 1,
                System.currentTimeMillis(), List.of());

        try {
            app.semanticVectorStore().replaceConditionally(stub, "snap-1",
                    () -> { throw new IOException("snapshot store I/O failure"); });
        } catch (IOException expected) {
            assertEquals("snapshot store I/O failure", expected.getMessage());
        }

        // The lock must have been released — a follow-up sync must complete successfully
        app.snapshotStore().publish(reg.id(), "snap-1", symbols(reg.id()), List.of(), List.of());
        SemanticIndexService.UpdateReport report = app.semanticIndexService().synchronize(reg.id());
        assertEquals(SemanticIndexService.State.READY, report.state(),
                "lock must be released even after an exception in the active-reader");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static Path setupProjectFiles(Path projectDir) throws IOException {
        Files.createDirectories(projectDir.resolve("src"));
        Files.writeString(projectDir.resolve("src").resolve("Alpha.java"),
                "class Alpha { void run() { /* runs the alpha workflow */ } }");
        return projectDir;
    }

    private static List<Symbol> symbols(UUID projectId) {
        String fileId = "src/Alpha.java";
        return List.of(new Symbol(
                "alpha-sym", "symbol:alpha", SymbolIdentityQuality.CANONICAL,
                projectId.toString(), "module", fileId, null,
                SymbolKind.METHOD, "run", "Alpha.run", "()",
                "java", new SymbolLocation(fileId, 1, 0, 1, 50, PositionEncoding.UTF16_CODE_UNITS),
                ResolutionStatus.RESOLVED, ORIGIN, false, false, Set.of()));
    }

    /** Embedding provider that signals when embedding starts and waits before producing a vector. */
    private static final class SlowEmbeddingProvider implements EmbeddingProvider {
        private final CountDownLatch started;
        private final CountDownLatch proceed;

        SlowEmbeddingProvider(CountDownLatch started, CountDownLatch proceed) {
            this.started = started;
            this.proceed = proceed;
        }

        @Override public String id() { return "slow-provider"; }
        @Override public String modelId() { return "slow-model-v1"; }
        @Override public int dimensions() { return 2; }

        @Override
        public SemanticVector embed(String stableKey, String text) {
            started.countDown();
            try { proceed.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            String lower = text.toLowerCase(Locale.ROOT);
            return new SemanticVector(stableKey, List.of(lower.contains("alpha") ? 1.0 : 0.0, 0.0));
        }
    }

    /** Fast deterministic embedding provider for non-blocking paths. */
    private static final class FastEmbeddingProvider implements EmbeddingProvider {
        @Override public String id() { return "fast-provider"; }
        @Override public String modelId() { return "fast-model-v1"; }
        @Override public int dimensions() { return 2; }

        @Override
        public SemanticVector embed(String stableKey, String text) {
            String lower = text.toLowerCase(Locale.ROOT);
            return new SemanticVector(stableKey, List.of(lower.contains("alpha") ? 1.0 : 0.0, 0.0));
        }
    }
}
