package com.minos.storage.postgresql.snapshot;

import com.minos.diagnostics.PublicErrorMessages;
import com.minos.diagnostics.SnapshotScratchSpaceExhaustedException;
import com.minos.storage.codec.PersistedSizeFixtures;
import com.minos.storage.codec.QueryViewMemoryBudget;
import com.minos.storage.codec.SnapshotCodecV3;
import com.minos.storage.codec.SnapshotLimits;
import com.minos.storage.postgresql.PostgresTestSupport;
import com.minos.store.CodeKnowledgeSnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AUD-PERF-09 — the PostgreSQL store copies a payload into its scratch area before decoding it. A scratch area that
 * cannot hold the payload refuses it before it is asked for, or during the copy, by a public error that gives both
 * sizes and no path; a write that fails for another reason keeps its own error. The usable space is injected.
 */
class PostgresCodeKnowledgeSnapshotStoreScratchSpaceTest extends PostgresTestSupport {

    private static final Duration LONG = Duration.ofSeconds(30);

    @TempDir Path tempDir;

    private final AtomicInteger payloadFetches = new AtomicInteger();
    private final AtomicInteger scratchFiles = new AtomicInteger();
    private final QueryViewMemoryBudget budget = QueryViewMemoryBudget.of(1L << 40, 50, LONG);

    @Test
    void aScratchAreaSmallerThanThePayloadRefusesItBeforeItIsFetched() throws Exception {
        UUID projectId = UUID.randomUUID();
        long payload = publish(projectId);
        PostgresCodeKnowledgeSnapshotStore reader = store(created -> scratchFiles.incrementAndGet(),
                directory -> payload - 1L);

        SnapshotScratchSpaceExhaustedException refused = assertThrows(SnapshotScratchSpaceExhaustedException.class,
                () -> reader.loadActiveReadView(projectId));

        assertTrue(refused.getMessage().startsWith("insufficient scratch space to load this snapshot"),
                refused.getMessage());
        assertTrue(refused.getMessage().contains(payload + " bytes"), refused.getMessage());
        assertTrue(refused.getMessage().contains((payload - 1L) + " bytes available"), refused.getMessage());
        assertEquals(0, payloadFetches.get(), "the payload was never asked for");
        assertEquals(0, scratchFiles.get(), "no scratch file was created");
        assertEquals(0L, budget.stats().reservedBytes());
        assertScratchDirectoryEmpty();
    }

    @Test
    void aScratchAreaFilledDuringTheCopyGivesTheSameRefusalAndRemovesThePartialFile() throws Exception {
        UUID projectId = UUID.randomUUID();
        long payload = publish(projectId);
        AtomicInteger spaceReads = new AtomicInteger();
        // Room at the check, none left once the write has failed.
        PostgresCodeKnowledgeSnapshotStore reader = store(PostgresCodeKnowledgeSnapshotStoreScratchSpaceTest::breakWrite,
                directory -> spaceReads.incrementAndGet() == 1 ? payload : 0L);

        SnapshotScratchSpaceExhaustedException refused = assertThrows(SnapshotScratchSpaceExhaustedException.class,
                () -> reader.loadActiveReadView(projectId));

        assertTrue(refused.getMessage().contains("0 bytes available"), refused.getMessage());
        assertNotNull(refused.getCause(), "the failed write is kept as the cause");
        assertEquals(0L, budget.stats().reservedBytes(), "the memory reservation is released");
        assertScratchDirectoryEmpty();
    }

    @Test
    void theRefusalMessageCarriesNoPath() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        PostgresCodeKnowledgeSnapshotStore reader = store(created -> { }, directory -> 0L);

        IOException refused = assertThrows(IOException.class, () -> reader.loadActiveReadView(projectId));

        assertFalse(PublicErrorMessages.looksSensitive(refused.getMessage()), refused.getMessage());
        assertFalse(refused.getMessage().contains(tempDir.toString()), refused.getMessage());
        assertFalse(refused.getMessage().contains("snapshot-read-"), refused.getMessage());
    }

    @Test
    void aWriteFailureThatIsNotALackOfSpaceKeepsItsOwnError() throws Exception {
        UUID projectId = UUID.randomUUID();
        publish(projectId);
        PostgresCodeKnowledgeSnapshotStore reader = store(PostgresCodeKnowledgeSnapshotStoreScratchSpaceTest::breakWrite,
                directory -> Long.MAX_VALUE);

        IOException failed = assertThrows(IOException.class, () -> reader.loadActiveReadView(projectId));

        assertFalse(failed instanceof SnapshotScratchSpaceExhaustedException, failed.toString());
        assertEquals(0L, budget.stats().reservedBytes());
        assertScratchDirectoryEmpty();
    }

    @Test
    void theProductionScratchSpaceIsTheUsableSpaceOfTheFileStore() throws Exception {
        long usable = PostgresCodeKnowledgeSnapshotStore.ScratchSpace.FILE_STORE.usableBytes(tempDir);

        assertTrue(usable > 0L);
    }

    /** Replaces the scratch file by a directory, so that opening it for writing fails. */
    private static void breakWrite(Path created) {
        try {
            Files.delete(created);
            Files.createDirectory(created);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private long publish(UUID projectId) throws IOException {
        CodeKnowledgeSnapshot snapshot = PersistedSizeFixtures.withTail(projectId, "scratch", 0, 100_000);
        new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                .publish(projectId, "scratch", snapshot.symbols(), List.of(), List.of());
        return new SnapshotCodecV3().encodedSize(snapshot);
    }

    private PostgresCodeKnowledgeSnapshotStore store(
            Consumer<Path> afterScratchFileCreated, PostgresCodeKnowledgeSnapshotStore.ScratchSpace space)
            throws IOException {
        return new PostgresCodeKnowledgeSnapshotStore(connections, tempDir, afterScratchFileCreated,
                SnapshotLimits.production(), budget, ignored -> payloadFetches.incrementAndGet(), space);
    }

    private void assertScratchDirectoryEmpty() throws IOException {
        try (Stream<Path> files = Files.list(tempDir.resolve("postgresql-snapshot-scratch"))) {
            assertEquals(List.of(), files.toList(), "no scratch file remains");
        }
    }
}
