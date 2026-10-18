package com.minos.storage.postgresql.snapshot;

import com.minos.storage.postgresql.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.minos.storage.postgresql.snapshot.PostgresCodeKnowledgeSnapshotStoreTest.symbol;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The scratch file of the PostgreSQL store is written through a link-free, hardened opening (AUD-SEC-03): a link put
 * in its place between its creation and the write is refused instead of followed, and the file it pointed to is intact.
 */
class PostgresSnapshotScratchFileLinkTest extends PostgresTestSupport {
    private static final String SCRATCH_DIRECTORY = "postgresql-snapshot-scratch";

    @TempDir Path tempDir;

    /**
     * Replaces the scratch file whose name starts with {@code prefix} by a link to {@code sentinel}, the instant after
     * it was created. The file that receives the encoded payload of a publication is written by the snapshot codec of
     * the local store, whose own migration is a separate change: it is left alone here.
     */
    private Consumer<Path> replaceByLinkTo(String prefix, Path sentinel, AtomicBoolean swapped) {
        return scratch -> {
            if (!scratch.getFileName().toString().startsWith(prefix)) return;
            try {
                Files.delete(scratch);
                Files.createSymbolicLink(scratch, sentinel);
                swapped.set(true);
            } catch (IOException | UnsupportedOperationException failure) {
                assumeTrue(false, "this platform/account cannot create symbolic links");
            }
        };
    }

    @Test
    void aLinkPutInPlaceOfTheScratchFileWhileLoadingTheActiveSnapshotIsRefused() throws Exception {
        UUID projectId = UUID.randomUUID();
        new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                .publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        Path sentinel = Files.writeString(tempDir.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);
        AtomicBoolean swapped = new AtomicBoolean();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(
                connections, tempDir, replaceByLinkTo("snapshot-read-", sentinel, swapped));

        assertThrows(IOException.class, () -> store.loadActiveKnowledge(projectId));

        assertTrue(swapped.get(), "the link was put in place");
        assertEquals("untouched", Files.readString(sentinel), "the file the link pointed to is intact");
        assertEquals(0L, scratchFiles(), "no scratch file is left behind");
    }

    @Test
    void aLinkPutInPlaceOfTheScratchFileWhileComparingAnExistingIdentityIsRefused() throws Exception {
        UUID projectId = UUID.randomUUID();
        new PostgresCodeKnowledgeSnapshotStore(connections, tempDir)
                .publish(projectId, "snap-1", List.of(symbol(projectId, "sym-a")));
        Path sentinel = Files.writeString(tempDir.resolve("sentinel.txt"), "untouched", StandardCharsets.UTF_8);
        AtomicBoolean swapped = new AtomicBoolean();
        PostgresCodeKnowledgeSnapshotStore store = new PostgresCodeKnowledgeSnapshotStore(
                connections, tempDir, replaceByLinkTo("snapshot-existing-", sentinel, swapped));
        List<com.minos.domain.Symbol> otherContent = List.of(symbol(projectId, "sym-b"));

        assertThrows(IOException.class, () -> store.publish(projectId, "snap-1", otherContent));

        assertTrue(swapped.get(), "the link was put in place");
        assertEquals("untouched", Files.readString(sentinel), "the file the link pointed to is intact");
    }

    private long scratchFiles() throws IOException {
        try (Stream<Path> scratch = Files.list(tempDir.resolve(SCRATCH_DIRECTORY))) {
            return scratch.count();
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }
}
